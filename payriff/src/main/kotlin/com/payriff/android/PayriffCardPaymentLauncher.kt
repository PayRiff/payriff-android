package com.payriff.android

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistry
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.payriff.android.internal.DeviceIntegrityGuard
import com.payriff.android.internal.PaymentGuard
import com.payriff.android.internal.PayriffLog
import com.payriff.android.internal.card.CardApiClient
import com.payriff.android.internal.card.CardConfirmation
import com.payriff.android.internal.card.CardCrypto
import com.payriff.android.internal.card.CardHandoffs
import com.payriff.android.internal.card.CardResults
import com.payriff.android.internal.card.CardSetup
import com.payriff.android.internal.card.CardSheetContract
import com.payriff.android.internal.card.CardSheetRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Receives the [PayriffResult] of a card payment, on the main thread: from [PayriffCardPaymentLauncher.confirm], and as
 * [PayriffPaymentView.onResultListener].
 */
public fun interface PayriffCardResultCallback {
    /**
     * Called once per confirmed payment. As [PayriffPaymentView.onResultListener], also called once with a fatal error
     * that ends the order ([PayriffError.isFatal]).
     */
    public fun onResult(result: PayriffResult)
}

/**
 * Pays an existing order with the card typed into a [PayriffCardForm] (CONTRACT §16.7). It uses only the Client API
 * with [PayriffConfig.publishableKey] and the order's `clientSecret`: the secret key is never involved.
 *
 * Create it while your activity (or fragment) is being created, like any Activity Result launcher, because the 3-D
 * Secure step runs in the SDK's own sheet and its result must survive a configuration change or process death:
 * ```
 * class CheckoutActivity : ComponentActivity() {
 *     private val cardPayment = PayriffCardPaymentLauncher(this) { result -> show(result) } // restored results
 *
 *     fun onPayClicked() {
 *         cardPayment.confirm(cardForm, orderId, clientSecret) { result -> show(result) }
 *     }
 * }
 * ```
 * A confirm validates the form (an invalid form returns [PayriffErrorCode.VALIDATION_FAILED] with
 * [PayriffError.fields] and makes no request), loads the order and checks that it is payable by card, encrypts the card
 * on the device and sends it, then runs the bank's 3-D Secure step and polls the status. The form is locked meanwhile.
 * Treat only [PayriffResult.Success] as paid, and fulfil on your server after it confirmed the order.
 *
 * Once the card was sent, the result is never `Cancelled(USER_CLOSED)`: closing the 3DS sheet checks the status once
 * and returns `Pending(NOT_FINAL)` unless the status is final.
 *
 * If your activity is recreated while the 3DS sheet is open, the result goes to `onRestoredResult` of the launcher
 * created by the new activity instance. If it is destroyed before the card was sent (for example a rotation while the
 * order loads), the payment is abandoned without a callback.
 *
 * [PayriffPaymentView] uses a launcher of its own; with it you need none.
 */
public class PayriffCardPaymentLauncher private constructor(
    register: (ActivityResultCallback<PayriffResult?>) -> ActivityResultLauncher<CardSheetRequest>,
    lifecycleOwner: LifecycleOwner?,
    private val onRestoredResult: PayriffCardResultCallback?,
) {
    /**
     * @param caller The activity or fragment that registers the 3DS sheet's result.
     * @param lifecycleOwner Its lifecycle; work that has not reached the 3DS sheet stops when it is destroyed.
     * @param onRestoredResult Receives a result that arrives after the activity was recreated, when the original
     *   callback no longer exists.
     */
    @JvmOverloads
    public constructor(caller: ActivityResultCaller, lifecycleOwner: LifecycleOwner, onRestoredResult: PayriffCardResultCallback? = null) :
        this({ callback -> caller.registerForActivityResult(CardSheetContract(), callback) }, lifecycleOwner, onRestoredResult)

    /** For an activity: `PayriffCardPaymentLauncher(this)`. */
    @JvmOverloads
    public constructor(activity: ComponentActivity, onRestoredResult: PayriffCardResultCallback? = null) :
        this(activity, activity, onRestoredResult)

    private class Pending(val form: PayriffCardForm, val callback: PayriffCardResultCallback, val orderId: String, val flowId: String) {
        /** The 3DS sheet was started; it now holds the payment guard and delivers the result. */
        var sheetStarted = false
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pending: Pending? = null
    private var destroyed = false

    // Last: registering may deliver a result that waited for this launcher (onRestoredResult) right away.
    private val sheet: ActivityResultLauncher<CardSheetRequest> = register(::onSheetResult)

    init {
        lifecycleOwner?.lifecycle?.addObserver(
            LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_DESTROY) onDestroyed() },
        )
    }

    /** `true` while a confirm of this launcher runs. */
    public val isProcessing: Boolean
        get() = pending != null

    /**
     * Confirms the payment of order [orderId] with the card in [form]. Call it on the main thread. [callback] receives
     * exactly one result on the main thread; validation problems and a payment that is already in progress
     * ([PayriffErrorCode.PAYMENT_IN_PROGRESS]) are reported to it immediately.
     *
     * @param clientSecret The order's `clientSecret` from your backend (`pr_cs_…`), sent as is after trimming.
     */
    public fun confirm(form: PayriffCardForm, orderId: String, clientSecret: String, callback: PayriffCardResultCallback) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "PayriffCardPaymentLauncher.confirm must be called on the main thread." }
        start(form, orderId, clientSecret, callback)
    }

    /**
     * Suspending variant of [confirm]. Cancelling the calling coroutine stops a confirm that has not reached the 3-D
     * Secure sheet yet; an open sheet still finishes, and its result is dropped.
     */
    public suspend fun confirm(form: PayriffCardForm, orderId: String, clientSecret: String): PayriffResult =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                val job = start(form, orderId, clientSecret) { result -> if (continuation.isActive) continuation.resume(result) }
                continuation.invokeOnCancellation { job?.cancel() }
            }
        }

    private fun start(form: PayriffCardForm, orderId: String, clientSecret: String, callback: PayriffCardResultCallback): Job? {
        fun fail(error: PayriffError): Job? {
            callback.onResult(PayriffResult.Failed(error))
            return null
        }
        if (destroyed) return fail(PayriffError(PayriffErrorCode.UNKNOWN, "The launcher's activity was destroyed."))
        if (pending != null) return fail(PaymentGuard.inProgressError())
        PaymentGuard.busyError()?.let { return fail(it) }
        Payriff.onContextAvailable(form.context)
        // CONTRACT §16.16: refuse a compromised device before anything is sent (unless allowCompromisedDevices is on).
        DeviceIntegrityGuard.blockReason(form.context, Payriff.config)?.let { return fail(it) }
        val (id, credentials) = try {
            CardSetup.orderId(orderId) to CardSetup.credentials(Payriff.config, clientSecret)
        } catch (error: PayriffError) {
            return fail(error)
        }
        val card = when (val collected = form.collectForPayment()) {
            is PayriffCardForm.Collected.Invalid -> return fail(
                PayriffError(PayriffErrorCode.VALIDATION_FAILED, "The card form is not filled in correctly.", fields = collected.errors),
            )
            is PayriffCardForm.Collected.Valid -> collected.card
        }
        val flowId = PaymentGuard.newFlowId()
        if (!PaymentGuard.reserve(flowId) || !PaymentGuard.confirm(flowId)) {
            card.wipe()
            return fail(PaymentGuard.inProgressError())
        }
        val current = Pending(form, callback, id, flowId)
        pending = current
        form.setProcessing(true)
        val language = form.effectiveLanguage()
        val confirmation = CardConfirmation(
            api = CardApiClient(credentials, Payriff.transport()),
            encrypt = { plaintext -> withContext(Dispatchers.Default) { CardCrypto.encrypt(plaintext, CardCrypto.payriffPublicKey()) } },
            // CONTRACT §16.16: a fresh runtime check right before encryption; a compromised device fails the payment.
            recheckDevice = { DeviceIntegrityGuard.blockReason(form.context, Payriff.config)?.let { throw it } },
        )
        val job = scope.launch {
            try {
                val action = confirmation.run(id, card)
                // /pay accepted: the 3DS sheet runs the next action. It travels in memory, never in the Intent.
                CardHandoffs.put(flowId, action)
                try {
                    sheet.launch(CardSheetRequest(flowId, id, credentials.clientSecret, language))
                    current.sheetStarted = true
                } catch (e: RuntimeException) {
                    CardHandoffs.take(flowId)
                    PayriffLog.error { "The 3-D Secure sheet could not start: ${e.javaClass.simpleName}" }
                    finishLocally(current, PayriffResult.Failed(PayriffError(PayriffErrorCode.PRESENTATION_FAILED, "The 3-D Secure sheet could not be shown.")))
                }
            } catch (error: PayriffError) {
                finishLocally(current, PayriffResult.Failed(error))
            } finally {
                card.wipe()
                if (!current.sheetStarted && pending === current) {
                    // Cancelled before the sheet: no callback; free the guard and unlock the form.
                    pending = null
                    PaymentGuard.release(flowId)
                    form.setProcessing(false)
                }
            }
        }
        // Also wipes the card when the job is cancelled before its body ever ran (then no finally block runs).
        job.invokeOnCompletion { card.wipe() }
        return job
    }

    private fun finishLocally(current: Pending, result: PayriffResult) {
        if (pending !== current) return
        pending = null
        PaymentGuard.release(current.flowId)
        current.form.onPaymentFinished(final = result !is PayriffResult.Failed)
        current.callback.onResult(result)
    }

    private fun onSheetResult(result: PayriffResult?) {
        val current = pending
        pending = null
        val delivered = result ?: CardResults.notFinal(current?.orderId.orEmpty(), null)
        if (current == null) {
            val restored = onRestoredResult
            if (restored != null) restored.onResult(delivered) else PayriffLog.warning { "A card payment result arrived, but no onRestoredResult callback is set." }
            return
        }
        PaymentGuard.release(current.flowId)
        current.form.onPaymentFinished(final = delivered !is PayriffResult.Failed)
        current.callback.onResult(delivered)
    }

    private fun onDestroyed() {
        destroyed = true
        val current = pending
        pending = null
        // Work that has not reached the sheet stops here. A sheet that is already open keeps the guard and delivers
        // its result to the launcher of the recreated activity.
        if (current != null && !current.sheetStarted) PaymentGuard.release(current.flowId)
        scope.cancel()
    }

    /**
     * Stops this launcher of a [PayriffPaymentView] that left its window, like an activity's destruction stops a
     * launcher registered in `onCreate`, and unregisters it. Returns `true` when an open 3-D Secure sheet will still
     * deliver a result; it goes to the next launcher registered under the same key.
     */
    internal fun release(): Boolean {
        val awaitingSheet = pending?.sheetStarted == true
        onDestroyed()
        sheet.unregister()
        return awaitingSheet
    }

    internal companion object {
        /**
         * A launcher registered on [registry] under [key] without a lifecycle, for a view that registers when it is
         * attached and calls [release] when it is detached. A result for [key] that arrived meanwhile (the activity was
         * recreated during 3-D Secure) goes to [onRestoredResult] at once.
         */
        fun forRegistry(registry: ActivityResultRegistry, key: String, onRestoredResult: PayriffCardResultCallback): PayriffCardPaymentLauncher =
            PayriffCardPaymentLauncher({ callback -> registry.register(key, CardSheetContract(), callback) }, null, onRestoredResult)
    }
}

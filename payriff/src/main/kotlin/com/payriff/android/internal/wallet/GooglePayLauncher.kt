package com.payriff.android.internal.wallet

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import com.google.android.gms.tasks.Task
import com.google.android.gms.wallet.IsReadyToPayRequest
import com.google.android.gms.wallet.PaymentData
import com.google.android.gms.wallet.PaymentDataRequest
import com.google.android.gms.wallet.PaymentsClient
import com.google.android.gms.wallet.Wallet
import com.google.android.gms.wallet.contract.ApiTaskResult
import com.google.android.gms.wallet.contract.TaskResultContracts
import com.payriff.android.Payriff
import com.payriff.android.internal.DeviceIntegrityGuard
import com.payriff.android.internal.PayriffLog
import com.payriff.android.internal.card.CardSheetContract
import com.payriff.android.internal.card.CardSheetRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Google Pay on a host activity (CONTRACT §17.5), for a view that registers it when it is attached and calls [release]
 * when it is detached, as [com.payriff.android.PayriffPaymentView] does with its card launcher:
 *
 * - [GooglePayApi] through `PaymentsClient` (`Wallet.getPaymentsClient` with [environment]);
 * - the Google Pay sheet through the host's `ActivityResultRegistry` with `TaskResultContracts.GetPaymentDataResult`
 *   ([GooglePayContract]), under `<key>:googlePay`;
 * - the card flow's 3-D Secure sheet under `<key>:googlePay:3ds`.
 *
 * A result that arrives after the activity was recreated waits in the registry for the launcher registered next under
 * the same key, so it reaches the view with the same id. The payment itself is [payment]. Main thread only.
 */
internal class GooglePayLauncher(
    activity: Activity,
    registry: ActivityResultRegistry,
    key: String,
    environment: GooglePayEnvironment,
    host: GooglePayPayment.Host,
    resumeOrderId: String?,
) : GooglePayApi {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val client: PaymentsClient =
        Wallet.getPaymentsClient(activity, Wallet.WalletOptions.Builder().setEnvironment(environment.walletConstant).build())
    private var released = false

    /** Whether the Google Pay sheet was launched and its outcome has not arrived yet. */
    private var sheetOpen = false

    val payment = GooglePayPayment(
        scope = scope,
        api = this,
        openThreeDs = { request -> threeDsSheet.launch(request) },
        host = host,
        resumeOrderId = resumeOrderId,
        // CONTRACT §16.16, §17.11: the device check on tap and right before POST /pay.
        deviceBlock = { DeviceIntegrityGuard.blockReason(activity, Payriff.config) },
    )

    // Registered after the payment exists: registering delivers a result that waited for its key right away.
    private val threeDsSheet: ActivityResultLauncher<CardSheetRequest> =
        registry.register(key + THREE_DS_SUFFIX, CardSheetContract()) { result -> payment.onThreeDsResult(result) }

    private val googlePaySheet: ActivityResultLauncher<Task<PaymentData>> =
        registry.register(key + SHEET_SUFFIX, GooglePayContract()) { result ->
            sheetOpen = false
            payment.onSheetOutcome(GooglePayOutcome.of(result.status.statusCode) { result.result?.toJson() })
        }

    override suspend fun isReadyToPay(request: String): Boolean = try {
        val task = client.isReadyToPay(IsReadyToPayRequest.fromJson(request))
        suspendCancellableCoroutine { continuation ->
            task.addOnCompleteListener { done ->
                if (continuation.isActive) continuation.resume(done.isSuccessful && done.result == true)
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: RuntimeException) {
        PayriffLog.warning { "Google Pay isReadyToPay failed: ${e.javaClass.simpleName}" }
        false
    }

    override fun loadPaymentData(request: String) {
        val task = client.loadPaymentData(PaymentDataRequest.fromJson(request))
        // The task completes once Google Pay knows whether it must show its sheet; the contract then shows it.
        task.addOnCompleteListener { completed ->
            if (released) return@addOnCompleteListener
            try {
                googlePaySheet.launch(completed)
                sheetOpen = true
            } catch (e: RuntimeException) {
                PayriffLog.error { "The Google Pay sheet could not be shown: ${e.javaClass.simpleName}" }
                payment.onSheetOutcome(GooglePayOutcome.NotShown)
            }
        }
    }

    /**
     * Stops this launcher of a view that left its window and unregisters it. Returns `true` when a Google Pay or 3-D
     * Secure sheet that is open will still deliver its result, to the launcher registered next under the same key.
     */
    fun release(): Boolean {
        released = true
        val awaiting = payment.release(googlePaySheetOpen = sheetOpen)
        googlePaySheet.unregister()
        threeDsSheet.unregister()
        scope.cancel()
        return awaiting
    }

    companion object {
        private const val SHEET_SUFFIX = ":googlePay"
        private const val THREE_DS_SUFFIX = ":googlePay:3ds"

        /** The activity behind [context], if any. */
        fun activityOf(context: Context): Activity? {
            var current: Context? = context
            while (current != null) {
                if (current is Activity) return current
                current = (current as? ContextWrapper)?.baseContext
            }
            return null
        }
    }
}

/**
 * `TaskResultContracts.GetPaymentDataResult` with fresh state for every launch. Google's contract keeps the resolution
 * of the last task it was given, so a single instance reused for a later payment could open an earlier payment's sheet
 * again; this contract gives every launch a new one. The result is Google's `ApiTaskResult<PaymentData>`.
 */
internal class GooglePayContract : ActivityResultContract<Task<PaymentData>, ApiTaskResult<PaymentData>>() {

    private var current = TaskResultContracts.GetPaymentDataResult()

    override fun getSynchronousResult(context: Context, input: Task<PaymentData>): SynchronousResult<ApiTaskResult<PaymentData>>? {
        current = TaskResultContracts.GetPaymentDataResult()
        return current.getSynchronousResult(context, input)
    }

    override fun createIntent(context: Context, input: Task<PaymentData>): Intent = current.createIntent(context, input)

    override fun parseResult(resultCode: Int, intent: Intent?): ApiTaskResult<PaymentData> =
        TaskResultContracts.GetPaymentDataResult().parseResult(resultCode, intent)
}

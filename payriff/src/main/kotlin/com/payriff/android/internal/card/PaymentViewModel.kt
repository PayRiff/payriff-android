package com.payriff.android.internal.card

import com.payriff.android.Payriff
import com.payriff.android.PayriffClientOrder
import com.payriff.android.PayriffError
import com.payriff.android.PayriffErrorCode
import com.payriff.android.PayriffLanguage
import com.payriff.android.PayriffPaymentMethod
import com.payriff.android.PayriffPaymentViewStyle
import com.payriff.android.PayriffResult
import com.payriff.android.internal.wallet.GooglePayAvailability
import com.payriff.android.internal.wallet.GooglePayTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The state of [com.payriff.android.PayriffPaymentView] and the rules that turn it into what the view shows (CONTRACT
 * §16.14, §17.9): the Pay button's title, whether it is enabled or busy, the error line under it, and the Google Pay
 * button above the form. Pure Kotlin, so the JVM unit tests check the rules.
 */
internal class PaymentViewState {

    /** The order, as `Payriff.retrieveOrder` described it when the view appeared. */
    sealed class Order {
        /** Not loaded yet, or loading. */
        object Loading : Order() {
            override fun toString(): String = "Loading"
        }

        /** The order can be paid by card. */
        class Payable(val order: PayriffClientOrder) : Order()

        /** The order exists, but its status or payment methods do not allow a card payment. */
        class NotPayable(val order: PayriffClientOrder) : Order()

        /** `retrieveOrder` failed. */
        class Failed(val error: PayriffError) : Order()
    }

    var order: Order = Order.Loading
        private set

    /** Whether every field of the card form is valid. */
    var isFormComplete: Boolean = false

    /** Whether the card form is locked by a running payment. */
    var isFormProcessing: Boolean = false

    /** Whether a payment of the view runs, from Pay until its result. */
    var isPaying: Boolean = false
        private set

    /** A payment reached a status: the view does not pay the order again. */
    var isFinished: Boolean = false
        private set

    /** The error of a payment that ended the order (a fatal error, or `ORDER_NOT_PAYABLE`); the error line shows it. */
    var endedBy: PayriffError? = null
        private set

    /** Set when the device failed the integrity check (CONTRACT §16.16): the error line shows it and Pay stays off. */
    var deviceBlock: PayriffError? = null
        private set

    /** Whether a fatal error was already reported through the result listener; it is reported once. */
    private var fatalReported = false

    /** Whether the device block was already reported through the result listener; it is reported once per order. */
    private var deviceBlockReported = false

    /** Google Pay for the loaded order (CONTRACT §17.3): its values when offered; `null` while undecided or not offered. */
    var googlePayOffer: GooglePayTransaction? = null
        private set

    /** Whether CONTRACT §17.3 was decided for the loaded order. Until then the Google Pay button does not show. */
    var isGooglePayDecided: Boolean = false
        private set

    /** The order once loaded, payable or not. */
    val loadedOrder: PayriffClientOrder?
        get() = when (val current = order) {
            is Order.Payable -> current.order
            is Order.NotPayable -> current.order
            else -> null
        }

    /**
     * The Pay button's title in [language]: "Pay 1.02 AZN" once the order is loaded, "Pay" before. The style's [label]
     * replaces the word "Pay" when it is set.
     */
    fun payTitle(language: PayriffLanguage, label: String? = null): String = PaymentViewPlan.payTitle(language, loadedOrder, label)

    /** The Pay button's title for [style] with [texts] (CONTRACT §18.1): [PaymentViewPlan.payTitle]. */
    fun payTitle(texts: ResolvedStrings, style: PayriffPaymentViewStyle): String = PaymentViewPlan.payTitle(texts, loadedOrder, style)

    /** Whether the Google Pay button shows above the form: Google Pay is offered and the device was not blocked. */
    val isGooglePayShown: Boolean
        get() = googlePayOffer != null && deviceBlock == null

    /**
     * Whether the Google Pay button can be tapped: it shows, the order is not paid from this view yet and no payment
     * runs, card or wallet. Otherwise it is dimmed to 50% (CONTRACT §17.9).
     */
    val isGooglePayEnabled: Boolean
        get() = isGooglePayShown && !isFinished && endedBy == null && !isPaying && !isFormProcessing

    /** Whether Google Pay still has to be decided for the loaded order. */
    val needsGooglePayDecision: Boolean
        get() = loadedOrder != null && !isGooglePayDecided

    /**
     * Whether Pay can be tapped: the order is payable and not paid from this view yet, every field is valid and no
     * payment runs. Otherwise the button is dimmed to 50%.
     */
    val isPayEnabled: Boolean
        get() = deviceBlock == null && order is Order.Payable && !isFinished && endedBy == null && isFormComplete && !isPaying && !isFormProcessing

    /** Whether a spinner replaces the Pay button's title. */
    val isBusy: Boolean
        get() = isPaying || isFormProcessing

    /**
     * The error line under the button in [language]: why a payment ended the order, `retrieveOrder`'s error message,
     * or "This order cannot be paid by card" (`view.notPayable`); `null` while the order loads or can be paid.
     */
    fun errorMessage(language: PayriffLanguage): String? = errorMessage(ResolvedStrings(language))

    /**
     * The error line with the app's texts ([texts], CONTRACT §18.6): [com.payriff.android.PayriffStrings.errorMessages]
     * for the error's code in place of its developer-facing message; for the lines that have a text of their own
     * (`notPayable`, `deviceNotSupported`), that text first, then the code's entry (`ORDER_NOT_PAYABLE`,
     * `DEVICE_COMPROMISED`).
     */
    fun errorMessage(texts: ResolvedStrings): String? {
        // The device block takes precedence: it is shown while the order never loads (CONTRACT §16.16).
        if (deviceBlock != null) return texts.errorLine(StringField.DEVICE_NOT_SUPPORTED, PayriffErrorCode.DEVICE_COMPROMISED)
        endedBy?.let { return messageOf(it, texts) }
        return when (val current = order) {
            is Order.NotPayable -> texts.errorLine(StringField.NOT_PAYABLE, PayriffErrorCode.ORDER_NOT_PAYABLE)
            is Order.Failed -> messageOf(current.error, texts)
            else -> null
        }
    }

    /**
     * Whether the order should be loaded when the view appears: it was never loaded, or loading failed with an error
     * that is not fatal, such as `NETWORK_ERROR`.
     */
    val needsOrder: Boolean
        get() = when (val current = order) {
            Order.Loading -> true
            is Order.Failed -> !current.error.isFatal
            else -> false
        }

    /** Another order was set: everything about the previous one is forgotten. */
    fun newOrder() {
        order = Order.Loading
        isFinished = false
        endedBy = null
        fatalReported = false
        deviceBlock = null
        deviceBlockReported = false
        googlePayOffer = null
        isGooglePayDecided = false
    }

    /** CONTRACT §17.3 was decided for the loaded order: [offer] when Google Pay is offered, else `null`. */
    fun googlePayDidDecide(offer: GooglePayTransaction?) {
        googlePayOffer = offer
        isGooglePayDecided = true
    }

    /**
     * The device failed the integrity check (CONTRACT §16.16). Returns the failure to report through the result
     * listener, only the first time per order; afterwards the view only keeps showing the message.
     */
    fun deviceDidBlock(error: PayriffError): PayriffResult? {
        deviceBlock = error
        if (deviceBlockReported) return null
        deviceBlockReported = true
        return PayriffResult.Failed(error)
    }

    /** The device passed the integrity check: clears a block left from a previous check. */
    fun deviceDidPass() {
        deviceBlock = null
    }

    /** A load starts. */
    fun orderLoading() {
        order = Order.Loading
    }

    fun orderDidLoad(loaded: PayriffClientOrder) {
        order = if (loaded.isPayableByCard) Order.Payable(loaded) else Order.NotPayable(loaded)
    }

    /** Takes a failed load. Returns the result to report: a fatal error (CONTRACT §16.9), only the first time. */
    fun orderDidFail(error: PayriffError): PayriffResult? {
        order = Order.Failed(error)
        if (!error.isFatal || fatalReported) return null
        fatalReported = true
        return PayriffResult.Failed(error)
    }

    fun paymentDidStart() {
        isPaying = true
    }

    /**
     * Takes a payment's result. A result with a status ends the payments of this view; a fatal error or
     * `ORDER_NOT_PAYABLE` also ends the order and shows why. Any other failure (for example `PAYMENT_FAILED`) lets the
     * customer try again.
     */
    fun paymentDidFinish(result: PayriffResult) {
        isPaying = false
        if (result !is PayriffResult.Failed) isFinished = true
        val error = result.error ?: return
        if (error.isFatal || error.code == PayriffErrorCode.ORDER_NOT_PAYABLE) endedBy = error
        if (error.isFatal) fatalReported = true
    }

    /**
     * The payment stopped without a result: before the 3-D Secure sheet (the view left its window), or the customer closed
     * the Google Pay sheet (CONTRACT §17.8).
     */
    fun paymentAbandoned() {
        isPaying = false
    }

    private fun messageOf(error: PayriffError, texts: ResolvedStrings): String =
        texts.errorMessage(error.code) ?: error.message.trim().ifEmpty { texts.view(CardStrings.VIEW_NOT_PAYABLE) }
}

/**
 * What [com.payriff.android.PayriffPaymentView] does (CONTRACT §16.14, §17.9), apart from drawing: it loads the order
 * when the view appears and decides Google Pay for it, runs Pay and Google Pay, and reports every payment result, and a
 * fatal load error once, to [deliver]. Pure Kotlin with coroutines; the view passes the main-thread scope,
 * `Payriff.retrieveOrder`, the Google Pay decision and the launchers, the JVM unit tests a test scope and fakes. Main
 * thread only.
 *
 * @param onStateChanged Called after every change of [state], before [deliver].
 * @param decideGooglePay CONTRACT §17.3 for a loaded order ([GooglePayAvailability.decide]): the Google Pay values when
 *   it is offered, else `null`.
 * @param onPaymentStarted CONTRACT §18.5: once per attempt, when Pay or the Google Pay button starts a payment, before
 *   anything is sent or the Google Pay sheet opens.
 * @param onOrderLoaded CONTRACT §18.5: after every successful load of the order, payable by card or not.
 * @param onLoadFailed CONTRACT §18.5: when the view gets no order: every failed load, and a device block once per
 *   order (the order is not loaded then). Called before a fatal error goes to [deliver].
 */
internal class PaymentViewController(
    private val scope: CoroutineScope,
    private val loadOrder: suspend (orderId: String, clientSecret: String) -> PayriffClientOrder,
    private val onStateChanged: () -> Unit,
    private val deliver: (PayriffResult) -> Unit,
    private val deviceBlock: () -> PayriffError? = { null },
    private val decideGooglePay: suspend (PayriffClientOrder) -> GooglePayTransaction? = { null },
    private val onPaymentStarted: (PayriffPaymentMethod) -> Unit = {},
    private val onOrderLoaded: (PayriffClientOrder) -> Unit = {},
    private val onLoadFailed: (PayriffError) -> Unit = {},
) {
    val state = PaymentViewState()

    /** The order id of [setOrder]. */
    var orderId: String? = null
        private set

    /** The order's `clientSecret` of [setOrder]. */
    var clientSecret: String? = null
        private set
    private var onScreen = false
    private var loadJob: Job? = null

    /** Bumped for every load and every cancel, so a late answer of an older load is dropped. */
    private var loadGeneration = 0

    /** Sets the order to pay. Another order resets the view, and is loaded at once while the view is on screen. */
    fun setOrder(orderId: String, clientSecret: String) {
        if (orderId == this.orderId && clientSecret == this.clientSecret) return
        this.orderId = orderId
        this.clientSecret = clientSecret
        cancelLoad()
        state.newOrder()
        onStateChanged()
        if (onScreen) load()
    }

    /**
     * The view is on screen. First the device-integrity check (CONTRACT §16.16, §17.11): a block shows the message, keeps
     * Pay disabled, hides Google Pay, skips the order load and reports the failure once. Otherwise it loads the order
     * when it needs one ([PaymentViewState.needsOrder]), or decides Google Pay for a loaded order whose decision was
     * interrupted.
     */
    fun appear() {
        onScreen = true
        val blocked = deviceBlock()
        if (blocked != null) {
            cancelLoad()
            val report = state.deviceDidBlock(blocked)
            onStateChanged()
            if (report != null) {
                GooglePayAvailability.deviceBlocked(Payriff.config)
                onLoadFailed(blocked)
                deliver(report)
            }
            return
        }
        state.deviceDidPass()
        if (loadJob?.isActive == true) return
        if (state.needsOrder) {
            load()
        } else {
            state.loadedOrder?.takeIf { state.needsGooglePayDecision }?.let { decide(it, ++loadGeneration) }
        }
    }

    /** The view left its window: a running load stops, and starts again when the view appears. */
    fun disappear() {
        onScreen = false
        cancelLoad()
    }

    /**
     * Pay was tapped. When Pay is enabled, [confirm] runs the payment of the order and calls back with its result,
     * which goes to [deliver]; a second call back is ignored.
     */
    fun pay(confirm: (orderId: String, clientSecret: String, done: (PayriffResult) -> Unit) -> Unit) {
        val id = orderId ?: return
        val secret = clientSecret ?: return
        if (!state.isPayEnabled) return
        state.paymentDidStart()
        onStateChanged()
        onPaymentStarted(PayriffPaymentMethod.CARD)
        if (replacedDuring(id, secret)) return
        var done = false
        confirm(id, secret) { result ->
            if (!done) {
                done = true
                finishPayment(result)
            }
        }
    }

    /**
     * The Google Pay button was tapped (CONTRACT §17.5). When it is enabled, [start] opens Google Pay for the order with
     * its Google Pay values; the payment then ends in [finishPayment], or in [paymentAbandoned] when the customer closes
     * the Google Pay sheet.
     */
    fun payWithGooglePay(start: (orderId: String, clientSecret: String, transaction: GooglePayTransaction) -> Unit) {
        val id = orderId ?: return
        val secret = clientSecret ?: return
        val offer = state.googlePayOffer ?: return
        if (!state.isGooglePayEnabled) return
        state.paymentDidStart()
        onStateChanged()
        onPaymentStarted(PayriffPaymentMethod.GOOGLE_PAY)
        if (replacedDuring(id, secret)) return
        start(id, secret, offer)
    }

    /**
     * Whether the started-listener set another order than [id] with [secret]: then the payment does not start, and the
     * view is no longer busy.
     */
    private fun replacedDuring(id: String, secret: String): Boolean {
        if (orderId == id && clientSecret == secret) return false
        paymentAbandoned()
        return true
    }

    /** A Google Pay payment continues after the activity was recreated: the view is busy until its result. */
    fun paymentResumed() {
        if (state.isPaying) return
        state.paymentDidStart()
        onStateChanged()
    }

    /** A payment's result: also one that arrived after the activity was recreated during 3-D Secure. */
    fun finishPayment(result: PayriffResult) {
        state.paymentDidFinish(result)
        onStateChanged()
        deliver(result)
    }

    /** The payment stopped without a result: before the 3-D Secure sheet, or the Google Pay sheet was closed. */
    fun paymentAbandoned() {
        if (!state.isPaying) return
        state.paymentAbandoned()
        onStateChanged()
    }

    private fun load() {
        val id = orderId ?: return
        val secret = clientSecret ?: return
        val generation = ++loadGeneration
        state.orderLoading()
        onStateChanged()
        // The job may finish inside launch (an invalid order id throws before suspending); isActive covers that.
        loadJob = scope.launch {
            val loaded = try {
                loadOrder(id, secret)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: PayriffError) {
                if (generation == loadGeneration) loadFailed(error)
                return@launch
            } catch (unexpected: Exception) {
                if (generation == loadGeneration) loadFailed(PayriffError(PayriffErrorCode.UNKNOWN, LOAD_FAILED_MESSAGE))
                return@launch
            }
            if (generation != loadGeneration) return@launch
            state.orderDidLoad(loaded)
            onStateChanged()
            onOrderLoaded(loaded)
            if (generation != loadGeneration) return@launch
            decideGooglePayNow(loaded, generation)
        }
    }

    /** Decides Google Pay for the loaded [order] (CONTRACT §17.3), in a job of its own. */
    private fun decide(order: PayriffClientOrder, generation: Int) {
        loadJob = scope.launch { decideGooglePayNow(order, generation) }
    }

    private suspend fun decideGooglePayNow(order: PayriffClientOrder, generation: Int) {
        val offer = try {
            decideGooglePay(order)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unexpected: Exception) {
            GooglePayAvailability.notOffered("the check failed (${unexpected.javaClass.simpleName})")
        }
        if (generation != loadGeneration) return
        state.googlePayDidDecide(offer)
        onStateChanged()
    }

    private fun loadFailed(error: PayriffError) {
        val fatal = state.orderDidFail(error)
        onStateChanged()
        onLoadFailed(error)
        fatal?.let(deliver)
    }

    private fun cancelLoad() {
        loadGeneration++
        loadJob?.cancel()
        loadJob = null
    }

    private companion object {
        const val LOAD_FAILED_MESSAGE = "The order could not be loaded."
    }
}

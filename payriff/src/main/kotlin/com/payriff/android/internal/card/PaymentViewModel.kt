package com.payriff.android.internal.card

import com.payriff.android.PayriffClientOrder
import com.payriff.android.PayriffError
import com.payriff.android.PayriffErrorCode
import com.payriff.android.PayriffLanguage
import com.payriff.android.PayriffResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The state of [com.payriff.android.PayriffPaymentView] and the rules that turn it into what the view shows (CONTRACT
 * §16.14): the Pay button's title, whether it is enabled or busy, and the error line under it. Pure Kotlin, so the JVM
 * unit tests check the rules.
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

    /** Whether a fatal error was already reported through the result listener; it is reported once. */
    private var fatalReported = false

    /** The order once loaded, payable or not. */
    val loadedOrder: PayriffClientOrder?
        get() = when (val current = order) {
            is Order.Payable -> current.order
            is Order.NotPayable -> current.order
            else -> null
        }

    /** The Pay button's title in [language]: "Pay 1.02 AZN" once the order is loaded, "Pay" before. */
    fun payTitle(language: PayriffLanguage): String = PaymentViewPlan.payTitle(language, loadedOrder)

    /**
     * Whether Pay can be tapped: the order is payable and not paid from this view yet, every field is valid and no
     * payment runs. Otherwise the button is dimmed to 50%.
     */
    val isPayEnabled: Boolean
        get() = order is Order.Payable && !isFinished && endedBy == null && isFormComplete && !isPaying && !isFormProcessing

    /** Whether a spinner replaces the Pay button's title. */
    val isBusy: Boolean
        get() = isPaying || isFormProcessing

    /**
     * The error line under the button in [language]: why a payment ended the order, `retrieveOrder`'s error message,
     * or "This order cannot be paid by card" (`view.notPayable`); `null` while the order loads or can be paid.
     */
    fun errorMessage(language: PayriffLanguage): String? {
        endedBy?.let { return messageOf(it, language) }
        return when (val current = order) {
            is Order.NotPayable -> PaymentViewPlan.notPayableMessage(language)
            is Order.Failed -> messageOf(current.error, language)
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

    /** The payment stopped before the 3-D Secure sheet, without a result (the view left its window). */
    fun paymentAbandoned() {
        isPaying = false
    }

    private fun messageOf(error: PayriffError, language: PayriffLanguage): String =
        error.message.trim().ifEmpty { PaymentViewPlan.notPayableMessage(language) }
}

/**
 * What [com.payriff.android.PayriffPaymentView] does (CONTRACT §16.14), apart from drawing: it loads the order when the
 * view appears, runs Pay, and reports every payment result, and a fatal load error once, to [deliver]. Pure Kotlin with
 * coroutines; the view passes the main-thread scope, `Payriff.retrieveOrder` and the 3-D Secure launcher, the JVM unit
 * tests a test scope and fakes. Main thread only.
 *
 * @param onStateChanged Called after every change of [state], before [deliver].
 */
internal class PaymentViewController(
    private val scope: CoroutineScope,
    private val loadOrder: suspend (orderId: String, clientSecret: String) -> PayriffClientOrder,
    private val onStateChanged: () -> Unit,
    private val deliver: (PayriffResult) -> Unit,
) {
    val state = PaymentViewState()

    /** The order id of [setOrder]. */
    var orderId: String? = null
        private set

    private var clientSecret: String? = null
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

    /** The view is on screen: loads the order when it needs one ([PaymentViewState.needsOrder]). */
    fun appear() {
        onScreen = true
        if (state.needsOrder && loadJob?.isActive != true) load()
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
        var done = false
        confirm(id, secret) { result ->
            if (!done) {
                done = true
                finishPayment(result)
            }
        }
    }

    /** A payment's result: also one that arrived after the activity was recreated during 3-D Secure. */
    fun finishPayment(result: PayriffResult) {
        state.paymentDidFinish(result)
        onStateChanged()
        deliver(result)
    }

    /** The payment stopped before the 3-D Secure sheet without a result. */
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
        }
    }

    private fun loadFailed(error: PayriffError) {
        val fatal = state.orderDidFail(error)
        onStateChanged()
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

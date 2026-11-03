package com.payriff.android.internal.wallet

import com.payriff.android.PayriffCancelReason
import com.payriff.android.PayriffClientOrder
import com.payriff.android.PayriffError
import com.payriff.android.PayriffErrorCode
import com.payriff.android.PayriffPayment
import com.payriff.android.PayriffPaymentStatus
import com.payriff.android.PayriffResult
import com.payriff.android.internal.PayriffLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The state of [com.payriff.android.PayriffGooglePayButton] (CONTRACT §17.10). Pure Kotlin, for the JVM unit tests. */
internal class GooglePayButtonState {

    /** The Google Pay values of the order while Google Pay is offered; `null` while undecided or not offered. */
    var offer: GooglePayTransaction? = null
        private set

    /** Whether CONTRACT §17.3 was decided for the order: offered, or not (the device, the order, or its load). */
    var isDecided: Boolean = false
        private set

    /** Whether the order should be loaded again when the button appears: it never loaded, or failed with a non-fatal error. */
    var needsOrder: Boolean = true
        private set

    /**
     * Why Google Pay was not offered when the button last appeared, without loading the order: the device failed the
     * integrity check, or Google Pay is not configured or has no activity host. A later appearance without it decides
     * again.
     */
    var blockedBy: String? = null
        private set

    /** `true` from the tap until the result, the Google Pay and 3-D Secure sheets included. */
    var isPaying: Boolean = false
        private set

    /** A payment ended the order (a status, a fatal error or `ORDER_NOT_PAYABLE`): the button does not pay it again. */
    var isFinished: Boolean = false
        private set

    /** Whether the button shows: Google Pay is offered. Otherwise it has zero size. */
    val isShown: Boolean
        get() = offer != null

    /** Whether the button can be tapped: it shows, no payment runs and none ended the order. Otherwise it is dimmed. */
    val isEnabled: Boolean
        get() = offer != null && !isPaying && !isFinished

    /**
     * Another order: everything about the previous one is forgotten, except a payment that still runs, which reports its
     * result.
     */
    fun newOrder() {
        offer = null
        isDecided = false
        needsOrder = true
        blockedBy = null
        isFinished = false
    }

    fun loaded() {
        needsOrder = false
    }

    /** The load failed: Google Pay is not offered; a non-fatal error ([retry]) loads again when the button appears. */
    fun loadFailed(retry: Boolean) {
        offer = null
        isDecided = true
        needsOrder = retry
    }

    fun decided(offer: GooglePayTransaction?) {
        this.offer = offer
        isDecided = true
        blockedBy = null
    }

    /** Not offered for [reason], decided without the order. */
    fun blocked(reason: String) {
        offer = null
        isDecided = true
        blockedBy = reason
    }

    fun paymentDidStart() {
        isPaying = true
    }

    /** The payment stopped without a result (the customer closed Google Pay, or the button left its window). */
    fun paymentStopped() {
        isPaying = false
    }

    /**
     * Takes a payment's result. A status (other than the sheet closed without paying), a fatal error or
     * `ORDER_NOT_PAYABLE` ends the order; any other failure lets the customer try again.
     */
    fun paymentDidFinish(result: PayriffResult) {
        isPaying = false
        val error = result.error
        if (error == null) {
            if (!(result is PayriffResult.Cancelled && result.reason == PayriffCancelReason.USER_CLOSED)) isFinished = true
        } else if (error.isFatal || error.code == PayriffErrorCode.ORDER_NOT_PAYABLE) {
            isFinished = true
        }
    }
}

/**
 * What [com.payriff.android.PayriffGooglePayButton] does (CONTRACT §17.10), apart from drawing: when it appears,
 * CONTRACT §17.3 in its order (Google Pay possible at all, the device-integrity check, the order from
 * `Payriff.retrieveOrder`, then the device's readiness); on tap the Google Pay payment; every payment result to
 * [deliver], and `cancelled(userClosed)` when the customer closes the Google Pay sheet (CONTRACT §17.8). A failed load
 * is not reported: the button just does not show. Pure Kotlin with coroutines, so the JVM unit tests run it with a test
 * scope and fakes. Main thread only.
 *
 * @param onStateChanged Called after every change of [state], before [deliver].
 * @param unavailable Why Google Pay cannot be offered whatever the order says (not configured, no activity host), or
 *   `null`.
 */
internal class GooglePayButtonController(
    private val scope: CoroutineScope,
    private val loadOrder: suspend (orderId: String, clientSecret: String) -> PayriffClientOrder,
    private val decide: suspend (PayriffClientOrder) -> GooglePayTransaction?,
    private val onStateChanged: () -> Unit,
    private val deliver: (PayriffResult) -> Unit,
    private val deviceBlock: () -> PayriffError? = { null },
    private val unavailable: () -> String? = { null },
) {
    val state = GooglePayButtonState()

    /** The order id of [setOrder]. */
    var orderId: String? = null
        private set

    /** The order's `clientSecret` of [setOrder]. */
    var clientSecret: String? = null
        private set

    private var order: PayriffClientOrder? = null
    private var onScreen = false
    private var job: Job? = null

    /** Bumped for every load and every cancel, so a late answer for an older order is dropped. */
    private var generation = 0

    /** Sets the order to pay. Another order resets the button, and is loaded at once while it is on screen. */
    fun setOrder(orderId: String, clientSecret: String) {
        if (orderId == this.orderId && clientSecret == this.clientSecret) return
        this.orderId = orderId
        this.clientSecret = clientSecret
        cancel()
        order = null
        state.newOrder()
        onStateChanged()
        if (onScreen) refresh()
    }

    /**
     * The button is on screen. Google Pay must be possible at all ([unavailable]: configured, with an activity host),
     * then the device-integrity check runs (CONTRACT §16.16, §17.11): otherwise the button stays hidden and loads
     * nothing. Then the order is loaded when needed and Google Pay decided for it.
     */
    fun appear() {
        onScreen = true
        val reason = unavailable() ?: GooglePayAvailability.DEVICE_BLOCKED.takeIf { deviceBlock() != null }
        if (reason != null) {
            block(reason)
            return
        }
        if (job?.isActive == true) return
        if (state.needsOrder || !state.isDecided || state.blockedBy != null) refresh()
    }

    /** The button left its window: a running load or decision stops, and starts again when it appears. */
    fun disappear() {
        onScreen = false
        cancel()
    }

    /** The tap: when the button is enabled, [start] runs the Google Pay payment of the order (CONTRACT §17.5). */
    fun pay(start: (orderId: String, clientSecret: String, transaction: GooglePayTransaction) -> Unit) {
        val id = orderId ?: return
        val secret = clientSecret ?: return
        val offer = state.offer ?: return
        if (!state.isEnabled) return
        state.paymentDidStart()
        onStateChanged()
        start(id, secret, offer)
    }

    /** A payment continues after the activity was recreated. */
    fun paymentResumed() {
        if (state.isPaying) return
        state.paymentDidStart()
        onStateChanged()
    }

    /** The customer closed the Google Pay sheet of [orderId] (CONTRACT §17.8): `cancelled(userClosed)`, status unknown. */
    fun paymentClosed(orderId: String) {
        finishPayment(PayriffResult.Cancelled(PayriffPayment(orderId, PayriffPaymentStatus.UNKNOWN), PayriffCancelReason.USER_CLOSED))
    }

    /** A payment's result: also one that arrived after the activity was recreated during 3-D Secure. */
    fun finishPayment(result: PayriffResult) {
        state.paymentDidFinish(result)
        onStateChanged()
        deliver(result)
    }

    /** The payment stopped without a result (the button left its window before `/pay` answered). */
    fun paymentAbandoned() {
        if (!state.isPaying) return
        state.paymentStopped()
        onStateChanged()
    }

    /** Not offered for [reason], without loading the order; logged once until the reason changes. */
    private fun block(reason: String) {
        cancel()
        if (state.blockedBy != reason) GooglePayAvailability.notOffered(reason)
        state.blocked(reason)
        onStateChanged()
    }

    /** Loads the order unless it is loaded, then decides CONTRACT §17.3 for it. */
    private fun refresh() {
        unavailable()?.let { return block(it) }
        val id = orderId ?: return
        val secret = clientSecret ?: return
        val current = ++generation
        job = scope.launch {
            val loaded = order ?: try {
                loadOrder(id, secret)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: PayriffError) {
                if (current == generation) loadFailed(error)
                return@launch
            } catch (unexpected: Exception) {
                if (current == generation) loadFailed(PayriffError(PayriffErrorCode.UNKNOWN, "The order could not be loaded."))
                return@launch
            }
            if (current != generation) return@launch
            order = loaded
            state.loaded()
            val offer = try {
                decide(loaded)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (unexpected: Exception) {
                GooglePayAvailability.notOffered("the check failed (${unexpected.javaClass.simpleName})")
            }
            if (current != generation) return@launch
            state.decided(offer)
            onStateChanged()
        }
    }

    private fun loadFailed(error: PayriffError) {
        PayriffLog.warning { "PayriffGooglePayButton: the order could not be loaded (${error.code})." }
        GooglePayAvailability.notOffered("the order could not be loaded (${error.code})")
        state.loadFailed(retry = !error.isFatal)
        onStateChanged()
    }

    private fun cancel() {
        generation++
        job?.cancel()
        job = null
    }
}

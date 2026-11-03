package com.payriff.android.internal.wallet

import com.payriff.android.Payriff
import com.payriff.android.PayriffError
import com.payriff.android.PayriffErrorCode
import com.payriff.android.PayriffLanguage
import com.payriff.android.PayriffResult
import com.payriff.android.internal.PaymentGuard
import com.payriff.android.internal.PayriffLog
import com.payriff.android.internal.card.CardApi
import com.payriff.android.internal.card.CardApiClient
import com.payriff.android.internal.card.CardCredentials
import com.payriff.android.internal.card.CardHandoffs
import com.payriff.android.internal.card.CardResults
import com.payriff.android.internal.card.CardSetup
import com.payriff.android.internal.card.CardSheetRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * One Google Pay payment of [com.payriff.android.PayriffPaymentView] or [com.payriff.android.PayriffGooglePayButton]
 * (CONTRACT §17.5), from the tap to the result:
 *
 * 1. The tap ([start]): one payment at a time (the payment guard of the card flow), the device-integrity check, the
 *    Client API setup, then the Google Pay sheet ([GooglePayApi.loadPaymentData]).
 * 2. The sheet's outcome ([onSheetOutcome]): the token of an OK, nothing for a cancel ([Host.onClosed]), or
 *    `failed(WALLET_FAILED)` for an error or a missing token.
 * 3. The device-integrity check again, then `POST /pay` with the token ([CardApi.payWithWallet]: no `x-secret-key`, a
 *    new idempotency key per token, the card flow's retries).
 * 4. The next action runs in the card flow's 3-D Secure sheet, which also polls the status; its result arrives in
 *    [onThreeDsResult].
 *
 * A sheet that is open when the owner leaves its window ([release]) delivers its result to the payment registered next
 * under the same key: the 3-D Secure sheet's result is reported as it is, and a Google Pay token is used only for the
 * order its sheet was opened for ([resumeOrderId], which the owner keeps in its saved state).
 *
 * The token and Google's `PaymentData` are never logged and never kept after `/pay`. Main thread only. Pure Kotlin with
 * coroutines, so the JVM unit tests run it with a fake [GooglePayApi] and a fake Client API.
 *
 * @param openThreeDs Starts the 3-D Secure sheet; throws [RuntimeException] when it cannot.
 * @param resumeOrderId The order whose Google Pay sheet was open when the previous owner left its window or the activity
 *   was recreated, or `null`.
 */
internal class GooglePayPayment(
    private val scope: CoroutineScope,
    private val api: GooglePayApi,
    private val openThreeDs: (CardSheetRequest) -> Unit,
    private val host: Host,
    resumeOrderId: String? = null,
    private val deviceBlock: () -> PayriffError? = { null },
    private val credentials: (clientSecret: String) -> CardCredentials = { CardSetup.credentials(Payriff.config, it) },
    private val clientApi: (CardCredentials) -> CardApi = { CardApiClient(it, Payriff.transport()) },
    private val newIdempotencyKey: () -> String = { UUID.randomUUID().toString() },
) {
    /** An order to pay: its id and `clientSecret`, and the language of the 3-D Secure sheet. */
    class Order(val orderId: String, val clientSecret: String, val language: PayriffLanguage?) {
        override fun toString(): String = "Order(orderId=$orderId)"
    }

    /** The owner of the payment: the payment view or the standalone button. Called on the main thread. */
    interface Host {
        /** The order to pay with a token that arrived after a recreation, when the owner still shows [orderId]; else `null`. */
        fun resumableOrder(orderId: String): Order?

        /** A payment of [orderId] continues with a token that arrived after a recreation: lock the user interface. */
        fun onResumed(orderId: String)

        /** The customer closed the Google Pay sheet of [orderId] without paying (CONTRACT §17.8). */
        fun onClosed(orderId: String)

        /** The payment ended with [result]. */
        fun onFinished(result: PayriffResult)
    }

    private enum class Phase { SHEET, PAYING, THREE_DS }

    private class Pending(val flowId: String, val orderId: String, val credentials: CardCredentials, val language: PayriffLanguage?) {
        var phase = Phase.SHEET
        var job: Job? = null
    }

    private var pending: Pending? = null
    private var resumeOrderId: String? = resumeOrderId

    /** `true` from the tap until the result, the Google Pay and 3-D Secure sheets included. */
    val isRunning: Boolean
        get() = pending != null

    /**
     * The order whose Google Pay sheet is open, or was open when the previous owner left: its token may still arrive.
     * The owner keeps it in its saved state, so the token is used only for this order after a recreation.
     */
    val sheetOrderId: String?
        get() = pending?.takeIf { it.phase == Phase.SHEET }?.orderId ?: resumeOrderId

    /**
     * The tap (CONTRACT §17.5 step 1): one payment at a time, the device-integrity check, then the Google Pay sheet for
     * [transaction]. A failure goes to [Host.onFinished] at once.
     */
    fun start(order: Order, transaction: GooglePayTransaction) {
        if (pending != null || PaymentGuard.isBusy()) return host.onFinished(PayriffResult.Failed(PaymentGuard.inProgressError()))
        // CONTRACT §16.16, §17.11: the start point.
        deviceBlock()?.let { return host.onFinished(PayriffResult.Failed(it)) }
        val current = try {
            pendingFor(order)
        } catch (error: PayriffError) {
            return host.onFinished(PayriffResult.Failed(error))
        }
        if (!PaymentGuard.reserve(current.flowId) || !PaymentGuard.confirm(current.flowId)) {
            return host.onFinished(PayriffResult.Failed(PaymentGuard.inProgressError()))
        }
        pending = current
        resumeOrderId = null
        try {
            api.loadPaymentData(GooglePayRequests.paymentDataRequest(transaction).toString())
        } catch (e: RuntimeException) {
            PayriffLog.error { "The Google Pay sheet could not be shown: ${e.javaClass.simpleName}" }
            finish(current, walletFailed(NOT_SHOWN_MESSAGE))
        }
    }

    /** The Google Pay sheet's outcome (CONTRACT §17.5 step 3), also one that arrived after a recreation. */
    fun onSheetOutcome(outcome: GooglePayOutcome) {
        val current = pending
        if (current != null && current.phase == Phase.SHEET) handle(current, outcome) else resume(outcome)
    }

    /** The 3-D Secure sheet's result; `null` when it ended without one. Also a result that arrived after a recreation. */
    fun onThreeDsResult(result: PayriffResult?) {
        val current = pending
        if (current == null || current.phase != Phase.THREE_DS) {
            host.onFinished(result ?: CardResults.notFinal("", null))
            return
        }
        pending = null
        PaymentGuard.release(current.flowId)
        host.onFinished(result ?: CardResults.notFinal(current.orderId, null))
    }

    /**
     * The owner leaves its window, like the card launcher's activity is destroyed. A payment whose `/pay` is still running
     * (or whose Google Pay sheet never opened) stops without a result. Returns `true` when a sheet that is open
     * ([googlePaySheetOpen], the 3-D Secure sheet, or a Google Pay sheet still awaited from before a recreation) will
     * still deliver its result, to the payment registered next under the same key.
     */
    fun release(googlePaySheetOpen: Boolean): Boolean {
        val current = pending ?: return resumeOrderId != null
        pending = null
        return when (current.phase) {
            Phase.SHEET -> {
                PaymentGuard.release(current.flowId)
                if (googlePaySheetOpen) resumeOrderId = current.orderId
                googlePaySheetOpen
            }
            Phase.PAYING -> {
                current.job?.cancel()
                PaymentGuard.release(current.flowId)
                false
            }
            // The 3-D Secure sheet holds the guard and releases it when it finishes.
            Phase.THREE_DS -> true
        }
    }

    private fun pendingFor(order: Order): Pending =
        Pending(PaymentGuard.newFlowId(), CardSetup.orderId(order.orderId), credentials(order.clientSecret), order.language)

    /** A token that arrived after a recreation: it pays only the order its sheet was opened for, if the owner still shows it. */
    private fun resume(outcome: GooglePayOutcome) {
        val orderId = resumeOrderId
        resumeOrderId = null
        if (orderId == null || pending != null) {
            PayriffLog.warning { "A Google Pay result arrived that no payment waits for; it was not used." }
            return
        }
        val order = host.resumableOrder(orderId)
        if (order == null) {
            PayriffLog.warning { "A Google Pay result arrived after the screen was recreated for another order; it was not used." }
            return
        }
        val current = try {
            pendingFor(order)
        } catch (error: PayriffError) {
            PayriffLog.warning { "A Google Pay result arrived after the screen was recreated, but the payment cannot continue: ${error.code}." }
            return
        }
        if (!PaymentGuard.reserve(current.flowId) || !PaymentGuard.confirm(current.flowId)) {
            PayriffLog.warning { "A Google Pay result arrived while another payment runs; it was not used." }
            return
        }
        pending = current
        PayriffLog.info { "Google Pay result restored for orderId=${current.orderId}." }
        host.onResumed(current.orderId)
        handle(current, outcome)
    }

    private fun handle(current: Pending, outcome: GooglePayOutcome) {
        when (outcome) {
            is GooglePayOutcome.Ok -> {
                PayriffLog.info { "Google Pay: ok" }
                val token = GooglePayTokens.token(outcome.paymentData)
                if (token == null) finish(current, walletFailed(NO_TOKEN_MESSAGE)) else pay(current, token)
            }
            GooglePayOutcome.Cancelled -> {
                PayriffLog.info { "Google Pay: cancelled" }
                close(current)
            }
            is GooglePayOutcome.Error -> {
                PayriffLog.warning { "Google Pay: error ${outcome.statusCode}" }
                finish(current, walletFailed("Google Pay failed (status ${outcome.statusCode})."))
            }
            GooglePayOutcome.NotShown -> finish(current, walletFailed(NOT_SHOWN_MESSAGE))
        }
    }

    /** CONTRACT §17.5 steps 4–5: the device check, `POST /pay` with [token], then the 3-D Secure sheet. */
    private fun pay(current: Pending, token: String) {
        // CONTRACT §16.16, §17.11: the last point, right before POST /pay.
        deviceBlock()?.let { return finish(current, PayriffResult.Failed(it)) }
        current.phase = Phase.PAYING
        val client = clientApi(current.credentials)
        current.job = scope.launch {
            val action = try {
                client.payWithWallet(current.orderId, GooglePayRequests.METHOD, token, newIdempotencyKey())
            } catch (error: PayriffError) {
                finish(current, PayriffResult.Failed(error))
                return@launch
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (unexpected: RuntimeException) {
                // Never the exception's message: it may quote the request.
                finish(current, PayriffResult.Failed(PayriffError(PayriffErrorCode.UNKNOWN, "The Google Pay payment could not be sent (${unexpected.javaClass.simpleName}).")))
                return@launch
            }
            if (pending !== current) return@launch
            PayriffLog.info { "Google Pay payment submitted for orderId=${current.orderId}; next: $action" }
            // /pay accepted: the 3-D Secure sheet runs the next action. It travels in memory, never in the Intent.
            CardHandoffs.put(current.flowId, action)
            try {
                openThreeDs(CardSheetRequest(current.flowId, current.orderId, current.credentials.clientSecret, current.language))
                current.phase = Phase.THREE_DS
            } catch (e: RuntimeException) {
                CardHandoffs.take(current.flowId)
                PayriffLog.error { "The 3-D Secure sheet could not start: ${e.javaClass.simpleName}" }
                finish(current, PayriffResult.Failed(PayriffError(PayriffErrorCode.PRESENTATION_FAILED, "The 3-D Secure sheet could not be shown.")))
            }
        }
    }

    private fun finish(current: Pending, result: PayriffResult) {
        if (pending !== current) return
        pending = null
        PaymentGuard.release(current.flowId)
        host.onFinished(result)
    }

    private fun close(current: Pending) {
        if (pending !== current) return
        pending = null
        PaymentGuard.release(current.flowId)
        host.onClosed(current.orderId)
    }

    private fun walletFailed(message: String) = PayriffResult.Failed(PayriffError(PayriffErrorCode.WALLET_FAILED, message))

    companion object {
        const val NOT_SHOWN_MESSAGE = "Google Pay could not be shown."
        const val NO_TOKEN_MESSAGE = "Google Pay returned no payment token."
    }
}

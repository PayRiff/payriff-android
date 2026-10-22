package com.payriff.android.internal.card

import com.payriff.android.PayriffCancelReason
import com.payriff.android.PayriffClientOrder
import com.payriff.android.PayriffError
import com.payriff.android.PayriffErrorCode
import com.payriff.android.PayriffPayment
import com.payriff.android.PayriffPaymentStatus
import com.payriff.android.PayriffPendingReason
import com.payriff.android.PayriffResult
import com.payriff.android.internal.PayriffLog
import com.payriff.android.internal.StatusOutcome
import kotlinx.coroutines.CancellationException
import java.security.GeneralSecurityException
import java.util.UUID

/**
 * Steps 2–3 of CONTRACT §16.7, after the form was validated: load the order, check that it is payable by card, encrypt
 * the card and POST `/pay`. Returns the next action, which the 3DS sheet runs. Throws [PayriffError]; the plaintext is
 * wiped on every path.
 */
internal class CardConfirmation(
    private val api: CardApi,
    private val encrypt: suspend (CardPlaintext) -> EncryptedCard,
    private val newIdempotencyKey: () -> String = { UUID.randomUUID().toString() },
    private val recheckDevice: () -> Unit = {},
) {

    suspend fun run(orderId: String, card: CardPlaintext): NextAction {
        try {
            val order = api.order(orderId)
            if (!order.isPayableByCard) throw notPayable(order)
            // CONTRACT §16.16: a fresh device-integrity check right before the card is encrypted. Throws on a block.
            recheckDevice()
            val encrypted = encryptOrFail(card)
            card.wipe()
            try {
                val action = api.pay(orderId, encrypted, newIdempotencyKey())
                PayriffLog.info { "Card payment submitted for orderId=$orderId; next: $action" }
                return action
            } finally {
                encrypted.wipe()
            }
        } finally {
            card.wipe()
        }
    }

    private suspend fun encryptOrFail(card: CardPlaintext): EncryptedCard = try {
        encrypt(card)
    } catch (e: GeneralSecurityException) {
        throw encryptionError(e)
    } catch (e: RuntimeException) {
        if (e is CancellationException) throw e
        throw encryptionError(e)
    }

    /** Never carries the exception or its message: neither may hold card data (CONTRACT §11). */
    private fun encryptionError(e: Exception) =
        PayriffError(PayriffErrorCode.UNKNOWN, "The card could not be encrypted on this device (${e.javaClass.simpleName}).")

    private fun notPayable(order: PayriffClientOrder): PayriffError {
        val reason = if (order.status == PayriffPaymentStatus.CREATED || order.status == PayriffPaymentStatus.PENDING) {
            "card payments are not enabled for it"
        } else {
            "its status is ${order.rawStatus ?: "unknown"}"
        }
        return PayriffError(PayriffErrorCode.ORDER_NOT_PAYABLE, "The order cannot be paid by card: $reason.")
    }
}

/** Results of the card flow (CONTRACT §16.7–§16.9). */
internal object CardResults {

    /** The result of a final status (§8.2), or `null` while it is not final. */
    fun finalResult(orderId: String, status: CardStatus): PayriffResult? {
        if (!status.isFinal) return null
        val payment = payment(orderId, status)
        return when (StatusOutcome.of(status.status)) {
            StatusOutcome.SUCCESS -> PayriffResult.Success(payment)
            StatusOutcome.DECLINED -> PayriffResult.Declined(payment)
            StatusOutcome.CANCELLED -> PayriffResult.Cancelled(
                payment,
                StatusOutcome.cancelReasonOf(status.status) ?: PayriffCancelReason.CANCELED,
            )
            StatusOutcome.UNEXPECTED_FINAL, StatusOutcome.NON_FINAL -> PayriffResult.Pending(payment, PayriffPendingReason.UNEXPECTED_STATUS)
        }
    }

    /** `pending(notFinal)`: the card was submitted, but no final status was seen. */
    fun notFinal(orderId: String, last: CardStatus?): PayriffResult =
        PayriffResult.Pending(payment(orderId, last), PayriffPendingReason.NOT_FINAL)

    fun payment(orderId: String, status: CardStatus?): PayriffPayment = PayriffPayment(
        orderId = orderId,
        status = status?.status ?: PayriffPaymentStatus.UNKNOWN,
        rawStatus = status?.rawStatus,
        amount = status?.amount,
        currency = status?.currency,
        transactionId = null,
    )
}

package com.payriff.android

import com.payriff.android.internal.ResultJson

/** Why a payment ended as [PayriffResult.Cancelled]. */
public enum class PayriffCancelReason {
    /**
     * The customer closed the payment before anything was paid. Part of the result shape shared by every Payriff SDK;
     * the card flow does not return it, because closing the 3-D Secure sheet after the card was sent checks the status
     * and ends as [PayriffResult.Pending] unless the status is final.
     */
    USER_CLOSED,

    /** Payriff reported the order as [PayriffPaymentStatus.CANCELED]. */
    CANCELED,

    /** Payriff reported the order as [PayriffPaymentStatus.EXPIRED] or [PayriffPaymentStatus.PREAUTH_EXPIRED]. */
    EXPIRED,
}

/** Why a payment ended as [PayriffResult.Pending]. Verify the order on your backend. */
public enum class PayriffPendingReason {
    /**
     * The card was sent, but the status was still not final when status polling ended (after 120 s), or when the
     * customer closed the 3-D Secure sheet (for example `PENDING`: the payment may still complete).
     */
    NOT_FINAL,

    /** There was no way to verify the status. Part of the shared result shape; the card flow does not return it. */
    UNVERIFIED,

    /** Every status request failed. Part of the shared result shape; the card flow reports [NOT_FINAL] instead. */
    VERIFICATION_FAILED,

    /** A final status outside the success, declined and cancelled groups, such as [PayriffPaymentStatus.REFUNDED]. */
    UNEXPECTED_STATUS,
}

/**
 * Outcome of a card payment ([PayriffCardPaymentLauncher.confirm]). Exactly one result is delivered per confirm.
 *
 * Treat **only [Success] as paid**. [Pending] means "verify on your backend". Payment outcomes are
 * returned, never thrown.
 *
 * ```
 * when (result) {
 *     is PayriffResult.Success -> showReceipt(result.payment)
 *     is PayriffResult.Declined -> showDeclined()
 *     is PayriffResult.Cancelled -> Unit
 *     is PayriffResult.Pending -> verifyOnBackend(result.payment.orderId)
 *     is PayriffResult.Failed -> showError(result.error)
 * }
 * ```
 */
public sealed class PayriffResult {

    /** The order, when one is known. `null` for [Failed] and for a [Cancelled] payment without a known order id. */
    public abstract val payment: PayriffPayment?

    /** The error of a [Failed] result, otherwise `null`. */
    public open val error: PayriffError?
        get() = null

    /** `true` only for [Success], the one paid outcome. */
    public val isSuccess: Boolean
        get() = this is Success

    /**
     * The resolved status is [PayriffPaymentStatus.APPROVED] or [PayriffPaymentStatus.PREAUTH_APPROVED].
     *
     * @property payment The paid order.
     */
    public class Success(override val payment: PayriffPayment) : PayriffResult() {
        override fun equals(other: Any?): Boolean = other is Success && payment == other.payment
        override fun hashCode(): Int = payment.hashCode()
        override fun toString(): String = "PayriffResult.Success(payment=$payment)"
    }

    /**
     * The resolved status is [PayriffPaymentStatus.DECLINED].
     *
     * @property payment The declined order.
     */
    public class Declined(override val payment: PayriffPayment) : PayriffResult() {
        override fun equals(other: Any?): Boolean = other is Declined && payment == other.payment
        override fun hashCode(): Int = payment.hashCode()
        override fun toString(): String = "PayriffResult.Declined(payment=$payment)"
    }

    /**
     * The payment was cancelled.
     *
     * @property payment The order; `null` only when no order id is known.
     * @property reason Why the payment was cancelled.
     */
    public class Cancelled(
        override val payment: PayriffPayment?,
        public val reason: PayriffCancelReason,
    ) : PayriffResult() {
        override fun equals(other: Any?): Boolean =
            other is Cancelled && payment == other.payment && reason == other.reason

        override fun hashCode(): Int = 31 * (payment?.hashCode() ?: 0) + reason.hashCode()
        override fun toString(): String = "PayriffResult.Cancelled(reason=$reason, payment=$payment)"
    }

    /**
     * The outcome could not be confirmed. Verify the order on your backend.
     *
     * @property payment The order and the last status the SDK saw.
     * @property reason Why the outcome is not confirmed.
     */
    public class Pending(
        override val payment: PayriffPayment,
        public val reason: PayriffPendingReason,
    ) : PayriffResult() {
        override fun equals(other: Any?): Boolean =
            other is Pending && payment == other.payment && reason == other.reason

        override fun hashCode(): Int = 31 * payment.hashCode() + reason.hashCode()
        override fun toString(): String = "PayriffResult.Pending(reason=$reason, payment=$payment)"
    }

    /**
     * The payment could not start or run: an invalid configuration or order, an invalid card form
     * ([PayriffErrorCode.VALIDATION_FAILED]), an order that is not payable, a payment request Payriff refused, a
     * presentation problem, or [PayriffErrorCode.PAYMENT_IN_PROGRESS].
     *
     * @property error What went wrong.
     */
    public class Failed(override val error: PayriffError) : PayriffResult() {
        override val payment: PayriffPayment? get() = null

        override fun equals(other: Any?): Boolean =
            other is Failed &&
                error.code == other.error.code &&
                error.message == other.error.message &&
                error.isRetryable == other.error.isRetryable &&
                error.httpStatus == other.error.httpStatus &&
                error.payriffCode == other.error.payriffCode &&
                error.supportTicket == other.error.supportTicket &&
                error.isFatal == other.error.isFatal &&
                error.fields == other.error.fields

        override fun hashCode(): Int = 31 * error.code.hashCode() + error.message.hashCode()
        override fun toString(): String = "PayriffResult.Failed(error=$error)"
    }

    /**
     * Serializes this result to the JSON shape shared by every Payriff SDK, for example
     * `{"type":"success","payment":{"orderId":"…","status":"APPROVED",…}}`. Every key is present; unknown values
     * are `null`. `reason` appears only on [Cancelled] and [Pending].
     */
    public fun toJson(): String = ResultJson.encode(this)
}

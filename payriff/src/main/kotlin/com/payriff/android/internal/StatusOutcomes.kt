package com.payriff.android.internal

import com.payriff.android.PayriffCancelReason
import com.payriff.android.PayriffPaymentStatus

/** Outcome groups of CONTRACT §8.2. */
internal enum class StatusOutcome {
    SUCCESS,
    DECLINED,
    CANCELLED,
    UNEXPECTED_FINAL,
    NON_FINAL,
    ;

    val isFinal: Boolean get() = this != NON_FINAL

    companion object {
        fun of(status: PayriffPaymentStatus): StatusOutcome = when (status) {
            PayriffPaymentStatus.APPROVED, PayriffPaymentStatus.PREAUTH_APPROVED -> SUCCESS
            PayriffPaymentStatus.DECLINED -> DECLINED
            PayriffPaymentStatus.CANCELED, PayriffPaymentStatus.EXPIRED, PayriffPaymentStatus.PREAUTH_EXPIRED -> CANCELLED
            PayriffPaymentStatus.REFUNDED,
            PayriffPaymentStatus.PARTIAL_REFUND,
            PayriffPaymentStatus.REFUND_IN_PROGRESS,
            PayriffPaymentStatus.REVERSE,
            PayriffPaymentStatus.CASH,
            -> UNEXPECTED_FINAL
            PayriffPaymentStatus.CREATED,
            PayriffPaymentStatus.PENDING,
            PayriffPaymentStatus.ACCEPTED,
            PayriffPaymentStatus.PARTIAL,
            PayriffPaymentStatus.UNKNOWN,
            -> NON_FINAL
        }

        /** The cancel reason for a status in the [CANCELLED] group, otherwise `null`. */
        fun cancelReasonOf(status: PayriffPaymentStatus): PayriffCancelReason? = when (status) {
            PayriffPaymentStatus.CANCELED -> PayriffCancelReason.CANCELED
            PayriffPaymentStatus.EXPIRED, PayriffPaymentStatus.PREAUTH_EXPIRED -> PayriffCancelReason.EXPIRED
            else -> null
        }
    }
}

package com.payriff.android

import java.util.Locale

/**
 * Payriff order status, as the Client API reports it for an order and in its status answers.
 *
 * Only [PayriffResult.Success] means paid; use the result variant rather than this status to decide.
 * [PayriffPayment.rawStatus] keeps the exact string Payriff returned.
 */
public enum class PayriffPaymentStatus {
    /** Order created, not paid yet. */
    CREATED,

    /** Payment in progress. */
    PENDING,

    /** Accepted, not final yet. */
    ACCEPTED,

    /** Paid. */
    APPROVED,

    /** Amount held (pre-authorization). */
    PREAUTH_APPROVED,

    /** Declined by the bank or Payriff. */
    DECLINED,

    /** Cancelled. */
    CANCELED,

    /** The order expired before it was paid. */
    EXPIRED,

    /** The pre-authorization expired. */
    PREAUTH_EXPIRED,

    /** Refunded. */
    REFUNDED,

    /** Partially refunded. */
    PARTIAL_REFUND,

    /** Refund in progress. */
    REFUND_IN_PROGRESS,

    /** Reversed. */
    REVERSE,

    /** Partially paid. */
    PARTIAL,

    /** Paid in cash. */
    CASH,

    /** Nothing was resolved, or Payriff returned a status this SDK does not know. */
    UNKNOWN,
    ;

    /** Parsing of raw status strings. */
    public companion object {
        private val ALIASES = mapOf(
            "PAID" to APPROVED,
            "COMPLETED" to APPROVED,
            "SETTLED" to APPROVED,
            "SETTLE_PENDING" to APPROVED,
            "FAILED" to DECLINED,
            "CANCELLED" to CANCELED,
            "REVERSED" to REVERSE,
        )

        private val BY_NAME: Map<String, PayriffPaymentStatus> =
            entries.filter { it != UNKNOWN }.associateBy { it.name }

        /**
         * Parses a raw Payriff status string. The string is trimmed, upper-cased, and `-` and spaces become
         * `_`; known aliases are mapped (for example `PAID` to [APPROVED]). `null`, blank and unrecognised
         * values give [UNKNOWN].
         */
        @JvmStatic
        public fun fromRaw(raw: String?): PayriffPaymentStatus {
            if (raw == null) return UNKNOWN
            val normalized = raw.trim().uppercase(Locale.ROOT).replace('-', '_').replace(' ', '_')
            return ALIASES[normalized] ?: BY_NAME[normalized] ?: UNKNOWN
        }
    }
}

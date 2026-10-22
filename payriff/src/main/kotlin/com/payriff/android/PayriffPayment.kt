package com.payriff.android

import java.math.BigDecimal

/**
 * What the SDK knows about the order when a payment ends.
 *
 * @property orderId Payriff order id.
 * @property status Resolved status; [PayriffPaymentStatus.UNKNOWN] when nothing could be resolved.
 * @property rawStatus The exact status string Payriff returned, if any.
 * @property amount Amount from the last status answer, when Payriff returned one.
 * @property currency Currency from the last status answer, when Payriff returned one.
 * @property transactionId Part of the result shape shared by every Payriff SDK. The card flow does not learn it, so it
 *   is always `null` there.
 */
public class PayriffPayment(
    public val orderId: String,
    public val status: PayriffPaymentStatus,
    public val rawStatus: String? = null,
    public val amount: BigDecimal? = null,
    public val currency: String? = null,
    public val transactionId: String? = null,
) {
    override fun equals(other: Any?): Boolean =
        other is PayriffPayment &&
            orderId == other.orderId &&
            status == other.status &&
            rawStatus == other.rawStatus &&
            amountsEqual(amount, other.amount) &&
            currency == other.currency &&
            transactionId == other.transactionId

    override fun hashCode(): Int {
        var result = orderId.hashCode()
        result = 31 * result + status.hashCode()
        result = 31 * result + (rawStatus?.hashCode() ?: 0)
        result = 31 * result + (amount?.stripTrailingZeros()?.hashCode() ?: 0)
        result = 31 * result + (currency?.hashCode() ?: 0)
        result = 31 * result + (transactionId?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "PayriffPayment(orderId=$orderId, status=$status, rawStatus=$rawStatus, amount=${amount?.toPlainString()}, " +
            "currency=$currency, transactionId=$transactionId)"
}

internal fun amountsEqual(a: BigDecimal?, b: BigDecimal?): Boolean =
    if (a == null || b == null) a == b else a.compareTo(b) == 0

package com.payriff.android

import java.math.BigDecimal

/**
 * An order as the Client API shows it to the app (`GET /api/v3/client/orders/{orderId}`), returned by
 * [Payriff.retrieveOrder]. Needs only the publishable key and the order's `clientSecret`.
 *
 * @property orderId Payriff order id.
 * @property amount Order amount, when Payriff returned one.
 * @property currency ISO currency code, for example `AZN`.
 * @property description The order description.
 * @property merchantName The merchant's display name.
 * @property status The order status; [PayriffPaymentStatus.UNKNOWN] when Payriff returned none or an unknown one.
 * @property rawStatus The exact status string Payriff returned.
 * @property language The order's payment language, when it is one the SDK knows.
 * @property paymentMethods Payment methods enabled for the order, for example `CARD` and `GOOGLE_PAY`.
 */
public class PayriffClientOrder(
    public val orderId: String,
    public val amount: BigDecimal?,
    public val currency: String?,
    public val description: String?,
    public val merchantName: String?,
    public val status: PayriffPaymentStatus,
    public val rawStatus: String?,
    public val language: PayriffLanguage?,
    public val paymentMethods: List<String>,
) {
    /**
     * Whether [PayriffCardPaymentLauncher.confirm] can pay this order: its status is `CREATED` or `PENDING` and `CARD`
     * is one of its [paymentMethods] (CONTRACT §16.7).
     */
    public val isPayableByCard: Boolean
        get() = (status == PayriffPaymentStatus.CREATED || status == PayriffPaymentStatus.PENDING) && CARD in paymentMethods

    override fun equals(other: Any?): Boolean =
        other is PayriffClientOrder &&
            orderId == other.orderId &&
            amountsEqual(amount, other.amount) &&
            currency == other.currency &&
            description == other.description &&
            merchantName == other.merchantName &&
            status == other.status &&
            rawStatus == other.rawStatus &&
            language == other.language &&
            paymentMethods == other.paymentMethods

    override fun hashCode(): Int {
        var result = orderId.hashCode()
        result = 31 * result + (amount?.stripTrailingZeros()?.hashCode() ?: 0)
        result = 31 * result + (currency?.hashCode() ?: 0)
        result = 31 * result + status.hashCode()
        result = 31 * result + paymentMethods.hashCode()
        return result
    }

    override fun toString(): String =
        "PayriffClientOrder(orderId=$orderId, amount=${amount?.toPlainString()}, currency=$currency, " +
            "description=$description, merchantName=$merchantName, status=$status, rawStatus=$rawStatus, " +
            "language=$language, paymentMethods=$paymentMethods)"

    private companion object {
        const val CARD = "CARD"
    }
}

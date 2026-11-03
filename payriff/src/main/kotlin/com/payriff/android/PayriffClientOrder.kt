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
 * @property applePay The order's Apple Pay details, when Payriff returned them (CONTRACT §17.3). Android has no Apple
 *   Pay; the SDK reads them as the web SDK does and never uses them.
 * @property googlePay The order's Google Pay gateway, when Payriff returned one (CONTRACT §17.3). Google Pay is offered
 *   only when both of its values are set.
 */
public class PayriffClientOrder @JvmOverloads constructor(
    public val orderId: String,
    public val amount: BigDecimal?,
    public val currency: String?,
    public val description: String?,
    public val merchantName: String?,
    public val status: PayriffPaymentStatus,
    public val rawStatus: String?,
    public val language: PayriffLanguage?,
    public val paymentMethods: List<String>,
    public val applePay: ApplePay? = null,
    public val googlePay: GooglePay? = null,
) {
    /**
     * The `applePay` object of the order (CONTRACT §17.3), read as the web SDK reads it.
     *
     * @property merchantIdentifier The Apple merchant identifier Payriff returned, for example `merchant.com.payriff`.
     */
    public class ApplePay(public val merchantIdentifier: String?) {
        override fun equals(other: Any?): Boolean = other is ApplePay && merchantIdentifier == other.merchantIdentifier

        override fun hashCode(): Int = merchantIdentifier?.hashCode() ?: 0

        override fun toString(): String = "ApplePay(merchantIdentifier=$merchantIdentifier)"
    }

    /**
     * The `googlePay` object of the order (CONTRACT §17.3, §17.5): the payment gateway that receives the Google Pay
     * token, read as the web SDK reads it. Neither value is secret.
     *
     * @property gateway The gateway of the Google Pay tokenization, for example `payriff`.
     * @property gatewayMerchantId The merchant's id at that gateway.
     */
    public class GooglePay(public val gateway: String?, public val gatewayMerchantId: String?) {
        override fun equals(other: Any?): Boolean =
            other is GooglePay && gateway == other.gateway && gatewayMerchantId == other.gatewayMerchantId

        override fun hashCode(): Int = 31 * (gateway?.hashCode() ?: 0) + (gatewayMerchantId?.hashCode() ?: 0)

        override fun toString(): String = "GooglePay(gateway=$gateway, gatewayMerchantId=$gatewayMerchantId)"
    }

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
            paymentMethods == other.paymentMethods &&
            applePay == other.applePay &&
            googlePay == other.googlePay

    override fun hashCode(): Int {
        var result = orderId.hashCode()
        result = 31 * result + (amount?.stripTrailingZeros()?.hashCode() ?: 0)
        result = 31 * result + (currency?.hashCode() ?: 0)
        result = 31 * result + status.hashCode()
        result = 31 * result + paymentMethods.hashCode()
        result = 31 * result + (googlePay?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "PayriffClientOrder(orderId=$orderId, amount=${amount?.toPlainString()}, currency=$currency, " +
            "description=$description, merchantName=$merchantName, status=$status, rawStatus=$rawStatus, " +
            "language=$language, paymentMethods=$paymentMethods, applePay=$applePay, googlePay=$googlePay)"

    private companion object {
        const val CARD = "CARD"
    }
}

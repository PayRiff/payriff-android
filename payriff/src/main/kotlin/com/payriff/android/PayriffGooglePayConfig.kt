package com.payriff.android

/**
 * Google Pay of [PayriffPaymentView] and [PayriffGooglePayButton] (CONTRACT §17.2), set as [PayriffConfig.googlePay].
 * Without it (`null`, the default) Google Pay is never offered. With it, Google Pay is offered for every order that can
 * take it (CONTRACT §17.3): the order's `paymentMethods` are not checked.
 *
 * ```
 * PayriffConfig(
 *     publishableKey = "pr_pk_live_…",
 *     googlePay = PayriffGooglePayConfig(
 *         merchantId = "BCR2DN4T…",           // optional: your Google merchant ID
 *         merchantName = "Example Shop",      // optional
 *         gatewayMerchantId = "PRF0001",      // when Payriff's orders have no googlePay object
 *     ),
 * )
 * ```
 *
 * Google Pay needs your app's production access in the Google Pay & Wallet Console, integration type *Gateway*; the
 * SDK's manifest already enables the Google Pay API (`com.google.android.gms.wallet.api.enabled`). The token is
 * tokenized for a payment gateway: the order's `googlePay` object names it, and when the order has none, [gateway] and
 * [gatewayMerchantId] do (`wallet.json` → `googlePayGateway`). Merchant ids are not secret. Java uses the constructors
 * or [Builder].
 *
 * @param merchantId Your Google merchant id from the Google Pay & Wallet Console. See [PayriffGooglePayConfig.merchantId].
 * @param merchantName The name the Google Pay sheet shows. See [PayriffGooglePayConfig.merchantName].
 * @param gatewayMerchantId Payriff's merchant id for the tokenization. See [PayriffGooglePayConfig.gatewayMerchantId].
 * @param gateway The tokenization gateway. See [PayriffGooglePayConfig.gateway].
 */
public class PayriffGooglePayConfig @JvmOverloads constructor(
    merchantId: String? = null,
    merchantName: String? = null,
    gatewayMerchantId: String? = null,
    gateway: String = DEFAULT_GATEWAY,
) {
    /**
     * Your Google merchant id from the Google Pay & Wallet Console, trimmed; blank means `null`. When set, it is sent as
     * `merchantInfo.merchantId` of every Google Pay request (`wallet.json` → `googlePayMerchantIds`).
     */
    public val merchantId: String? = merchantId?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * The merchant name of the Google Pay sheet (`merchantInfo.merchantName`), trimmed; blank means `null`. `null` uses
     * the order's `merchantName`, then your app's label (CONTRACT §17.4).
     */
    public val merchantName: String? = merchantName?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Payriff's merchant id for Google Pay's `PAYMENT_GATEWAY` tokenization (CONTRACT §17.5), trimmed; blank means `null`.
     * It is used when the order has none (`PayriffClientOrder.googlePay`); without either, Google Pay is not offered.
     */
    public val gatewayMerchantId: String? = gatewayMerchantId?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * The tokenization `gateway` when the order gives none, trimmed; blank means [DEFAULT_GATEWAY] (`"payriff"`).
     */
    public val gateway: String = gateway.trim().ifEmpty { DEFAULT_GATEWAY }

    /** Returns a [Builder] pre-filled with this configuration. */
    public fun newBuilder(): Builder = Builder()
        .merchantId(merchantId)
        .merchantName(merchantName)
        .gatewayMerchantId(gatewayMerchantId)
        .gateway(gateway)

    override fun equals(other: Any?): Boolean =
        other is PayriffGooglePayConfig && merchantId == other.merchantId && merchantName == other.merchantName &&
            gatewayMerchantId == other.gatewayMerchantId && gateway == other.gateway

    override fun hashCode(): Int {
        var result = merchantId?.hashCode() ?: 0
        result = 31 * result + (merchantName?.hashCode() ?: 0)
        result = 31 * result + (gatewayMerchantId?.hashCode() ?: 0)
        return 31 * result + gateway.hashCode()
    }

    override fun toString(): String =
        "PayriffGooglePayConfig(merchantId=$merchantId, merchantName=$merchantName, gatewayMerchantId=$gatewayMerchantId, gateway=$gateway)"

    /** Java-friendly builder for [PayriffGooglePayConfig]. Every setter mirrors the constructor parameter of the same name. */
    public class Builder {
        private var merchantId: String? = null
        private var merchantName: String? = null
        private var gatewayMerchantId: String? = null
        private var gateway: String = DEFAULT_GATEWAY

        /** Sets [PayriffGooglePayConfig.merchantId]. */
        public fun merchantId(value: String?): Builder = apply { merchantId = value }

        /** Sets [PayriffGooglePayConfig.merchantName]. */
        public fun merchantName(value: String?): Builder = apply { merchantName = value }

        /** Sets [PayriffGooglePayConfig.gatewayMerchantId]. */
        public fun gatewayMerchantId(value: String?): Builder = apply { gatewayMerchantId = value }

        /** Sets [PayriffGooglePayConfig.gateway]; `null` or blank keeps [DEFAULT_GATEWAY]. */
        public fun gateway(value: String?): Builder = apply { gateway = value ?: DEFAULT_GATEWAY }

        /** Creates the configuration. */
        public fun build(): PayriffGooglePayConfig = PayriffGooglePayConfig(merchantId, merchantName, gatewayMerchantId, gateway)
    }

    public companion object {
        /** The tokenization gateway when neither the order nor [PayriffGooglePayConfig.gateway] names one. */
        public const val DEFAULT_GATEWAY: String = "payriff"
    }
}

package com.payriff.android

/**
 * Google Pay of [PayriffPaymentView] and [PayriffGooglePayButton] (CONTRACT §17.2), set as [PayriffConfig.googlePay].
 * Without it (`null`, the default) Google Pay is never offered.
 *
 * ```
 * PayriffConfig(
 *     publishableKey = "pr_pk_live_…",
 *     googlePay = PayriffGooglePayConfig(merchantId = "BCR2DN4T…", merchantName = "Example Shop"),
 * )
 * ```
 *
 * Google Pay needs your app's production access in the Google Pay & Wallet Console, integration type *Gateway*; the
 * SDK's manifest already enables the Google Pay API (`com.google.android.gms.wallet.api.enabled`). The gateway and the
 * gateway merchant id come from the order, so nothing else is needed here. Merchant ids are not secret.
 *
 * @param merchantId Your Google merchant id from the Google Pay & Wallet Console. See [PayriffGooglePayConfig.merchantId].
 * @param merchantName The name the Google Pay sheet shows. See [PayriffGooglePayConfig.merchantName].
 */
public class PayriffGooglePayConfig @JvmOverloads constructor(
    merchantId: String? = null,
    merchantName: String? = null,
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

    override fun equals(other: Any?): Boolean =
        other is PayriffGooglePayConfig && merchantId == other.merchantId && merchantName == other.merchantName

    override fun hashCode(): Int = 31 * (merchantId?.hashCode() ?: 0) + (merchantName?.hashCode() ?: 0)

    override fun toString(): String = "PayriffGooglePayConfig(merchantId=$merchantId, merchantName=$merchantName)"
}

package com.payriff.android

/**
 * How a payment of [PayriffPaymentView] is made (CONTRACT §18.5), as
 * [PayriffPaymentView.setOnPaymentStartedListener] reports it. The names are the same on every Payriff SDK.
 */
public enum class PayriffPaymentMethod {
    /** The card typed into the view's form. */
    CARD,

    /** Apple Pay. iOS only: the Android SDK never reports it. */
    APPLE_PAY,

    /** Google Pay ([PayriffConfig.googlePay]). */
    GOOGLE_PAY,
}

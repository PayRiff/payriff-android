package com.payriff.android

/**
 * Language of the card form, [PayriffPaymentView] and the 3-D Secure sheet.
 *
 * The effective language is `PayriffCardForm.language ?: config.language ?: device language`. The device language
 * maps `az*` to [AZ], `en*` to [EN], `ru*` to [RU] and `ar*` to [AR]; any other language gives [EN] in
 * [PayriffRegion.AZ] and [AR] in [PayriffRegion.SA] (CONTRACT §19.3). For other languages, pass your own texts with
 * [PayriffStrings].
 */
public enum class PayriffLanguage {
    /** Azerbaijani. */
    AZ,

    /** English. */
    EN,

    /** Russian. */
    RU,

    /**
     * Arabic (CONTRACT §19.3). The SDK's views and the 3-D Secure sheet are right-to-left in it, as in a right-to-left
     * layout, when the app supports right-to-left layouts (`android:supportsRtl="true"`). The card number, expiry and CVC
     * stay left-to-right, with Latin placeholders.
     */
    AR,
}

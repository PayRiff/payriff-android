package com.payriff.android

/**
 * Language of the card form and the 3-D Secure sheet.
 *
 * The effective language is `PayriffCardForm.language ?: config.language ?: device language`. The device language
 * maps `az*` to [AZ], `ru*` to [RU] and everything else to [EN].
 */
public enum class PayriffLanguage {
    /** Azerbaijani. */
    AZ,

    /** English. */
    EN,

    /** Russian. */
    RU,
}

package com.payriff.android

/**
 * The Payriff region the SDK talks to (CONTRACT §16.2). With [PayriffConfig.environment] it selects the API host of
 * every card call, [PayriffConfig.apiBaseUrl].
 */
public enum class PayriffRegion {
    /** Azerbaijan: `https://api.payriff.com`, and `https://sb-api.payriff.com` in the sandbox. This is the default. */
    AZ,

    /**
     * Saudi Arabia (GCC Pay): `https://api.gccpay.sa`. There is no Saudi sandbox: with [PayriffEnvironment.SANDBOX]
     * card payments also go to `https://api.gccpay.sa` and charge real cards, and [Payriff.configure] logs a warning.
     */
    SA,
}

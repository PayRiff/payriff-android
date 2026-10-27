package com.payriff.android

/**
 * The Payriff environment the SDK talks to (CONTRACT §2, §16.2). With [PayriffConfig.region] it selects the API host
 * of every card call, [PayriffConfig.apiBaseUrl].
 */
public enum class PayriffEnvironment {
    /**
     * Live payments: `https://api.payriff.com` in [PayriffRegion.AZ], `https://api.gccpay.sa` in [PayriffRegion.SA].
     * This is the default.
     */
    PRODUCTION,

    /**
     * Test payments: `https://sb-api.payriff.com`, the sandbox host of [PayriffRegion.AZ], provisional until Payriff
     * confirms it. [PayriffRegion.SA] has no sandbox: its card payments go to production (`https://api.gccpay.sa`)
     * and charge real cards.
     */
    SANDBOX,
}

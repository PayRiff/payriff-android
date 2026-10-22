package com.payriff.android

/** The Payriff environment the SDK talks to (CONTRACT §2, §16.2). */
public enum class PayriffEnvironment(
    /** API base URL of this environment, for example `https://api.payriff.com`: the host of every card call. */
    public val apiBaseUrl: String,
) {
    /** Live payments: `https://api.payriff.com`. This is the default. */
    PRODUCTION("https://api.payriff.com"),

    /** Test payments: `https://sb-api.payriff.com`, provisional until Payriff confirms the sandbox host. */
    SANDBOX("https://sb-api.payriff.com"),
}

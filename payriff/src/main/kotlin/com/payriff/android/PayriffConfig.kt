package com.payriff.android

/**
 * SDK configuration, passed to [Payriff.configure].
 *
 * Kotlin uses named arguments; Java uses [Builder]:
 * ```
 * Payriff.configure(
 *     PayriffConfig(
 *         publishableKey = "pr_pk_live_…",
 *         environment = PayriffEnvironment.SANDBOX,
 *     ),
 * )
 * ```
 *
 * Creating a configuration never throws. A [publishableKey] that is missing, or is not a publishable key
 * (`pr_pk_live_…`), makes every card call fail with [PayriffErrorCode.INVALID_CONFIG]. The secret key never belongs in
 * the app: your server creates orders with it and gives the app each order's `orderId` and `clientSecret`.
 *
 * @param publishableKey Your publishable key. See [PayriffConfig.publishableKey].
 * @property environment [PayriffEnvironment.PRODUCTION] (default) or [PayriffEnvironment.SANDBOX]. Selects the API host
 *   of every card call.
 * @property language Language of the card form and the 3-D Secure sheet. `null` uses the device language.
 * @property enableLogging Send SDK logs to Logcat and [PayriffLogger.listener]. Defaults to `false`.
 * @property allowScreenCaptureInDebug Debug builds only: lets screenshots and screen recordings capture the card form
 *   and the 3-D Secure sheet, for example for QA. They are otherwise protected with `FLAG_SECURE`. Release builds ignore
 *   it and always protect them. Defaults to `false`.
 * @property allowCompromisedDevices Lets card payments run on a rooted device, when a hooking framework is detected,
 *   or when the check cannot complete ([Payriff.isDeviceCompromised], CONTRACT §16.16). The default (`false`) refuses
 *   them with [PayriffErrorCode.DEVICE_COMPROMISED]; `true` only logs a warning. Turn it on **only for development** on
 *   rooted or hooked test devices, never in a shipped build.
 */
public class PayriffConfig(
    publishableKey: String? = null,
    public val environment: PayriffEnvironment = PayriffEnvironment.PRODUCTION,
    public val language: PayriffLanguage? = null,
    public val enableLogging: Boolean = false,
    public val allowScreenCaptureInDebug: Boolean = false,
    public val allowCompromisedDevices: Boolean = false,
) {
    /**
     * Publishable key (`pr_pk_live_…`) of the card flow ([PayriffCardForm], [PayriffCardPaymentLauncher],
     * [Payriff.retrieveOrder]), trimmed; blank means `null`. It is safe to ship in the app. A key that does not start
     * with `pr_pk_live_` (a secret key, for example) makes card calls fail with [PayriffErrorCode.INVALID_CONFIG].
     */
    public val publishableKey: String? = publishableKey?.trim()?.takeIf { it.isNotEmpty() }

    /** Returns a [Builder] pre-filled with this configuration. */
    public fun newBuilder(): Builder = Builder()
        .publishableKey(publishableKey)
        .environment(environment)
        .language(language)
        .enableLogging(enableLogging)
        .allowScreenCaptureInDebug(allowScreenCaptureInDebug)
        .allowCompromisedDevices(allowCompromisedDevices)

    /** Describes the configuration with the publishable key redacted as `pr_pk_live_***(<length>)`, so it is safe to log. */
    override fun toString(): String =
        "PayriffConfig(publishableKey=${publishableKey?.let(::redactPublishableKey)}, environment=$environment, " +
            "language=$language, enableLogging=$enableLogging, allowScreenCaptureInDebug=$allowScreenCaptureInDebug, " +
            "allowCompromisedDevices=$allowCompromisedDevices)"

    /** Java-friendly builder for [PayriffConfig]. Every setter mirrors the constructor parameter of the same name. */
    public class Builder {
        private var publishableKey: String? = null
        private var environment: PayriffEnvironment = PayriffEnvironment.PRODUCTION
        private var language: PayriffLanguage? = null
        private var enableLogging: Boolean = false
        private var allowScreenCaptureInDebug: Boolean = false
        private var allowCompromisedDevices: Boolean = false

        /** Sets [PayriffConfig.publishableKey]. */
        public fun publishableKey(value: String?): Builder = apply { publishableKey = value }

        /** Sets [PayriffConfig.environment]. */
        public fun environment(value: PayriffEnvironment): Builder = apply { environment = value }

        /** Sets [PayriffConfig.language]. */
        public fun language(value: PayriffLanguage?): Builder = apply { language = value }

        /** Sets [PayriffConfig.enableLogging]. */
        public fun enableLogging(value: Boolean): Builder = apply { enableLogging = value }

        /** Sets [PayriffConfig.allowScreenCaptureInDebug]. Debug builds only. */
        public fun allowScreenCaptureInDebug(value: Boolean): Builder = apply { allowScreenCaptureInDebug = value }

        /** Sets [PayriffConfig.allowCompromisedDevices]. For development only. */
        public fun allowCompromisedDevices(value: Boolean): Builder = apply { allowCompromisedDevices = value }

        /** Creates the configuration. Never throws; see [PayriffConfig] for validation. */
        public fun build(): PayriffConfig = PayriffConfig(
            publishableKey = publishableKey,
            environment = environment,
            language = language,
            enableLogging = enableLogging,
            allowScreenCaptureInDebug = allowScreenCaptureInDebug,
            allowCompromisedDevices = allowCompromisedDevices,
        )
    }

    private companion object {
        private const val PUBLISHABLE_PREFIX = "pr_pk_live_"

        /** `pr_pk_live_***(<length>)` for a well-formed key, otherwise `***(<length>)`: never the key itself. */
        private fun redactPublishableKey(key: String): String =
            if (key.startsWith(PUBLISHABLE_PREFIX)) "$PUBLISHABLE_PREFIX***(${key.length})" else "***(${key.length})"
    }
}

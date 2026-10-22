package com.payriff.example.android

import android.content.Context
import androidx.core.content.edit
import com.payriff.android.PayriffCardFormStyle
import com.payriff.android.PayriffConfig
import com.payriff.android.PayriffEnvironment
import com.payriff.android.PayriffLanguage
import com.payriff.android.PayriffPaymentViewStyle

/**
 * Example settings: the publishable key, the environment and the language, the look of the payment screen, plus two
 * debugging switches. Defaults come from the debug BuildConfig (filled from the git-ignored local.properties); values
 * saved in the Settings screen override them. Stored in app-private SharedPreferences with backups off. There is no
 * secret key: the order comes from your backend.
 */
class ExampleSettings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The card flow's publishable key (`pr_pk_live_…`). */
    var publishableKey: String
        get() = prefs.getString(KEY_PUBLISHABLE_KEY, null) ?: BuildConfig.PAYRIFF_PUBLISHABLE_KEY
        set(value) = prefs.edit { putString(KEY_PUBLISHABLE_KEY, value.trim()) }

    var environment: PayriffEnvironment
        get() = parseEnvironment(prefs.getString(KEY_ENVIRONMENT, null) ?: BuildConfig.PAYRIFF_ENVIRONMENT)
        set(value) = prefs.edit { putString(KEY_ENVIRONMENT, value.name) }

    /** The language of the card form and the 3-D Secure sheet; `null` follows the device. */
    var language: PayriffLanguage?
        get() = prefs.getString(KEY_LANGUAGE, null)?.let { name -> PayriffLanguage.entries.firstOrNull { it.name == name } }
        set(value) = prefs.edit { putString(KEY_LANGUAGE, value?.name) }

    var enableLogging: Boolean
        get() = prefs.getBoolean(KEY_LOGGING, true)
        set(value) = prefs.edit { putBoolean(KEY_LOGGING, value) }

    /**
     * Lets screenshots capture the card form and the 3-D Secure sheet, for QA. The SDK honours it in debug builds only;
     * release builds always protect them with FLAG_SECURE (CONTRACT §16.0 item 4). Off by default.
     */
    var allowScreenCapture: Boolean
        get() = prefs.getBoolean(KEY_SCREEN_CAPTURE, false)
        set(value) = prefs.edit { putBoolean(KEY_SCREEN_CAPTURE, value) }

    /**
     * "Custom style": the payment screen restyled through PayriffPaymentViewStyle ([customPaymentViewStyle]), to show
     * that an app can change the SDK's default look. Off by default: the SDK default, like pay.payriff.com.
     */
    var customStyle: Boolean
        get() = prefs.getBoolean(KEY_CUSTOM_STYLE, false)
        set(value) = prefs.edit { putBoolean(KEY_CUSTOM_STYLE, value) }

    /** The parts of the default payment view an app can hide; all shown by default. "Custom style" ignores them. */
    var showLabels: Boolean
        get() = prefs.getBoolean(KEY_SHOW_LABELS, true)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_LABELS, value) }

    var showBadges: Boolean
        get() = prefs.getBoolean(KEY_SHOW_BADGES, true)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_BADGES, value) }

    var showSecureNote: Boolean
        get() = prefs.getBoolean(KEY_SHOW_SECURE_NOTE, true)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_SECURE_NOTE, value) }

    var showPoweredBy: Boolean
        get() = prefs.getBoolean(KEY_SHOW_POWERED_BY, true)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_POWERED_BY, value) }

    /**
     * The look of the payment screen: [customPaymentViewStyle] with "Custom style", otherwise the SDK default without
     * the parts switched off in Settings.
     */
    fun paymentViewStyle(context: Context): PayriffPaymentViewStyle =
        if (customStyle) {
            customPaymentViewStyle(context)
        } else {
            PayriffPaymentViewStyle(
                showBadges = showBadges,
                showSecureNote = showSecureNote,
                showPoweredBy = showPoweredBy,
                formStyle = PayriffCardFormStyle(showLabels = showLabels),
            )
        }

    /** Forgets everything saved in the Settings screen. */
    fun reset() = prefs.edit { clear() }

    fun toConfig(): PayriffConfig = PayriffConfig(
        publishableKey = publishableKey.ifBlank { null },
        environment = environment,
        language = language,
        enableLogging = enableLogging,
        allowScreenCaptureInDebug = allowScreenCapture,
    )

    private fun parseEnvironment(value: String): PayriffEnvironment =
        PayriffEnvironment.entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) } ?: PayriffEnvironment.SANDBOX

    companion object {
        private const val FILE = "payriff_example_card_settings"

        /** The settings file of the example before the SDK became card-only. */
        private const val LEGACY_FILE = "payriff_example_settings"

        private const val KEY_PUBLISHABLE_KEY = "publishableKey"
        private const val KEY_ENVIRONMENT = "environment"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_LOGGING = "enableLogging"
        private const val KEY_SCREEN_CAPTURE = "allowScreenCapture"
        private const val KEY_CUSTOM_STYLE = "customStyle"
        private const val KEY_SHOW_LABELS = "showLabels"
        private const val KEY_SHOW_BADGES = "showBadges"
        private const val KEY_SHOW_SECURE_NOTE = "showSecureNote"
        private const val KEY_SHOW_POWERED_BY = "showPoweredBy"

        /**
         * Deletes the settings file of earlier versions of the example, which kept settings of the removed hosted
         * checkout, so none of them stays on the device. It is never read.
         */
        fun forgetLegacySettings(context: Context) {
            context.applicationContext.deleteSharedPreferences(LEGACY_FILE)
        }
    }
}

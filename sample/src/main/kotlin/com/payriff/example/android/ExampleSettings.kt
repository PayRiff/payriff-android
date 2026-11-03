package com.payriff.example.android

import android.content.Context
import com.payriff.android.PayriffConfig
import com.payriff.android.PayriffEnvironment
import com.payriff.android.PayriffGooglePayConfig
import com.payriff.android.PayriffRegion

/**
 * The example's settings, compiled into the debug build from the git-ignored local.properties: the publishable key
 * (`payriff.publishableKey`), the environment (`payriff.environment`), the region (`payriff.region`) and Google Pay
 * (`payriff.googlePay`, `payriff.googlePayMerchantId`). Release builds have no publishable key and no Google Pay. SDK
 * logs go to Logcat (tag `Payriff`). There is no secret key: the order comes from your backend.
 */
object ExampleSettings {

    /** Whether this build has a publishable key; without one the main screen says how to add it. */
    val hasPublishableKey: Boolean
        get() = BuildConfig.PAYRIFF_PUBLISHABLE_KEY.isNotBlank()

    /** The configuration of this build. */
    fun config(): PayriffConfig = config(
        BuildConfig.PAYRIFF_PUBLISHABLE_KEY,
        BuildConfig.PAYRIFF_ENVIRONMENT,
        BuildConfig.PAYRIFF_REGION,
        BuildConfig.PAYRIFF_GOOGLE_PAY,
        BuildConfig.PAYRIFF_GOOGLE_PAY_MERCHANT_ID,
    )

    /**
     * [publishableKey] (blank: none) in [environment], `SANDBOX` or `PRODUCTION` in any case (anything else is
     * `SANDBOX`), and [region], `AZ` or `SA` in any case (anything else is `AZ`), with logging on. Region `SA` has no
     * sandbox: with it, `SANDBOX` payments go to production and charge real cards, and the SDK logs a warning. The SDK's
     * security defaults stay: to pay from a rooted or hooked test device, add `allowCompromisedDevices = true` here for
     * that run of the debug build. Release builds ignore it, with a warning.
     *
     * [googlePay] turns on Google Pay in the payment view (`PayriffConfig.googlePay`), with [googlePayMerchantId] (blank:
     * none) from the Google Pay & Wallet Console. The Payriff sandbox has no wallets: Google Pay shows only for production
     * orders that list `GOOGLE_PAY`, and the app needs production access in the Google Pay & Wallet Console.
     */
    fun config(
        publishableKey: String,
        environment: String,
        region: String,
        googlePay: Boolean = false,
        googlePayMerchantId: String = "",
    ): PayriffConfig = PayriffConfig(
        publishableKey = publishableKey,
        environment = PayriffEnvironment.entries.firstOrNull { it.name.equals(environment.trim(), ignoreCase = true) }
            ?: PayriffEnvironment.SANDBOX,
        region = PayriffRegion.entries.firstOrNull { it.name.equals(region.trim(), ignoreCase = true) } ?: PayriffRegion.AZ,
        enableLogging = true,
        googlePay = if (googlePay) PayriffGooglePayConfig(merchantId = googlePayMerchantId) else null,
    )

    /**
     * Deletes the settings files of earlier versions of the example, which had a Settings screen, so none of them stays
     * on the device. They are never read.
     */
    fun forgetSavedSettings(context: Context) {
        for (name in LEGACY_FILES) context.applicationContext.deleteSharedPreferences(name)
    }

    /** The hosted checkout's settings, then the card flow's Settings screen. */
    private val LEGACY_FILES = listOf("payriff_example_settings", "payriff_example_card_settings")
}

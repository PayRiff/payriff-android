package com.payriff.example.android

import com.payriff.android.PayriffEnvironment
import com.payriff.android.PayriffRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The example's settings: from local.properties into the debug build, never a secret key. */
class ExampleSettingsTest {

    @Test
    fun theConfigurationHasThePublishableKeyTheEnvironmentAndTheRegion() {
        val config = ExampleSettings.config(" pr_pk_live_TEST ", "production", "sa")
        assertEquals("pr_pk_live_TEST", config.publishableKey)
        assertEquals(PayriffEnvironment.PRODUCTION, config.environment)
        assertEquals(PayriffRegion.SA, config.region)
        assertEquals("https://api.gccpay.sa", config.apiBaseUrl)
        assertTrue("the SDK log goes to Logcat", config.enableLogging)
        assertNull("the device language", config.language)
        // The SDK's security defaults stay on.
        assertFalse(config.allowCompromisedDevices)
        assertFalse(config.allowScreenCaptureInDebug)
    }

    @Test
    fun anEmptyKeyIsNoKeyAndAnUnknownEnvironmentIsTheSandbox() {
        assertNull(ExampleSettings.config("", "SANDBOX", "AZ").publishableKey)
        assertNull(ExampleSettings.config("  ", "SANDBOX", "AZ").publishableKey)
        assertEquals(PayriffEnvironment.SANDBOX, ExampleSettings.config("", "SANDBOX", "AZ").environment)
        assertEquals(PayriffEnvironment.PRODUCTION, ExampleSettings.config("", " Production ", "AZ").environment)
        assertEquals(PayriffEnvironment.SANDBOX, ExampleSettings.config("", "staging", "AZ").environment)
        assertEquals(PayriffEnvironment.SANDBOX, ExampleSettings.config("", "", "AZ").environment)
    }

    @Test
    fun anUnknownRegionIsAz() {
        assertEquals(PayriffRegion.AZ, ExampleSettings.config("", "SANDBOX", "AZ").region)
        assertEquals(PayriffRegion.SA, ExampleSettings.config("", "SANDBOX", " Sa ").region)
        assertEquals(PayriffRegion.AZ, ExampleSettings.config("", "SANDBOX", "ae").region)
        assertEquals(PayriffRegion.AZ, ExampleSettings.config("", "SANDBOX", "").region)
        assertEquals("https://sb-api.payriff.com", ExampleSettings.config("", "SANDBOX", "").apiBaseUrl)
        // Region SA has no sandbox: its "sandbox" is production.
        assertEquals("https://api.gccpay.sa", ExampleSettings.config("", "SANDBOX", "SA").apiBaseUrl)
    }

    @Test
    fun googlePayIsOnWhenTheSettingSaysSo() {
        // CONTRACT §17.13: payriff.googlePay and payriff.googlePayMerchantId.
        assertNull("off by default", ExampleSettings.config("pr_pk_live_TEST", "PRODUCTION", "AZ").googlePay)
        assertNull(ExampleSettings.config("pr_pk_live_TEST", "PRODUCTION", "AZ", googlePay = false, googlePayMerchantId = "BCR2DN4T").googlePay)
        val on = ExampleSettings.config("pr_pk_live_TEST", "PRODUCTION", "AZ", googlePay = true, googlePayMerchantId = " BCR2DN4TABCDEF ")
        assertEquals("BCR2DN4TABCDEF", on.googlePay?.merchantId)
        assertNull("the order's or the app's name", on.googlePay?.merchantName)
        assertTrue(on.toString().endsWith("googlePay=on)"))
        assertNull("blank: no merchant ID", ExampleSettings.config("", "PRODUCTION", "AZ", googlePay = true).googlePay?.merchantId)
    }

    @Test
    fun theGatewayMerchantIdGoesToTheGooglePayConfig() {
        // CONTRACT revision 10: payriff.googlePayGatewayMerchantId, for orders without a googlePay object.
        val on = ExampleSettings.config("pr_pk_live_TEST", "PRODUCTION", "AZ", googlePay = true, googlePayGatewayMerchantId = " PRF0001 ")
        assertEquals("PRF0001", on.googlePay?.gatewayMerchantId)
        assertEquals("payriff", on.googlePay?.gateway)
        assertNull("blank: none", ExampleSettings.config("", "PRODUCTION", "AZ", googlePay = true).googlePay?.gatewayMerchantId)
    }

    @Test
    fun theBuildHoldsNoSecretKey() {
        // Only the publishable key, the environment, the region and the Google Pay settings come from local.properties;
        // the order comes from your backend.
        val fields = BuildConfig::class.java.declaredFields.map { it.name }.filter { it.startsWith("PAYRIFF_") }
        assertEquals(
            listOf(
                "PAYRIFF_ENVIRONMENT",
                "PAYRIFF_GOOGLE_PAY",
                "PAYRIFF_GOOGLE_PAY_GATEWAY_MERCHANT_ID",
                "PAYRIFF_GOOGLE_PAY_MERCHANT_ID",
                "PAYRIFF_PUBLISHABLE_KEY",
                "PAYRIFF_REGION",
            ),
            fields.sorted(),
        )
        assertTrue(BuildConfig::class.java.declaredFields.none { "SECRET" in it.name.uppercase() })
    }
}

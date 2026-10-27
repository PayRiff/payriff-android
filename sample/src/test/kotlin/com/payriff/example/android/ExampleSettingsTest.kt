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
    fun theBuildHoldsNoSecretKey() {
        // Only the publishable key, the environment and the region come from local.properties; the order comes from
        // your backend.
        val fields = BuildConfig::class.java.declaredFields.map { it.name }.filter { it.startsWith("PAYRIFF_") }
        assertEquals(listOf("PAYRIFF_ENVIRONMENT", "PAYRIFF_PUBLISHABLE_KEY", "PAYRIFF_REGION"), fields.sorted())
        assertTrue(BuildConfig::class.java.declaredFields.none { "SECRET" in it.name.uppercase() })
    }
}

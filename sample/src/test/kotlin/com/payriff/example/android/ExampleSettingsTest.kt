package com.payriff.example.android

import com.payriff.android.PayriffEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The example's settings: from local.properties into the debug build, never a secret key. */
class ExampleSettingsTest {

    @Test
    fun theConfigurationHasThePublishableKeyAndTheEnvironment() {
        val config = ExampleSettings.config(" pr_pk_live_TEST ", "production")
        assertEquals("pr_pk_live_TEST", config.publishableKey)
        assertEquals(PayriffEnvironment.PRODUCTION, config.environment)
        assertTrue("the SDK log goes to Logcat", config.enableLogging)
        assertNull("the device language", config.language)
        // The SDK's security defaults stay on.
        assertFalse(config.allowCompromisedDevices)
        assertFalse(config.allowScreenCaptureInDebug)
    }

    @Test
    fun anEmptyKeyIsNoKeyAndAnUnknownEnvironmentIsTheSandbox() {
        assertNull(ExampleSettings.config("", "SANDBOX").publishableKey)
        assertNull(ExampleSettings.config("  ", "SANDBOX").publishableKey)
        assertEquals(PayriffEnvironment.SANDBOX, ExampleSettings.config("", "SANDBOX").environment)
        assertEquals(PayriffEnvironment.PRODUCTION, ExampleSettings.config("", " Production ").environment)
        assertEquals(PayriffEnvironment.SANDBOX, ExampleSettings.config("", "staging").environment)
        assertEquals(PayriffEnvironment.SANDBOX, ExampleSettings.config("", "").environment)
    }

    @Test
    fun theBuildHoldsNoSecretKey() {
        // Only the publishable key and the environment come from local.properties; the order comes from your backend.
        val fields = BuildConfig::class.java.declaredFields.map { it.name }.filter { it.startsWith("PAYRIFF_") }
        assertEquals(listOf("PAYRIFF_ENVIRONMENT", "PAYRIFF_PUBLISHABLE_KEY"), fields.sorted())
        assertTrue(BuildConfig::class.java.declaredFields.none { "SECRET" in it.name.uppercase() })
    }
}

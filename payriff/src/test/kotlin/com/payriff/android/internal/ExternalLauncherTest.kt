package com.payriff.android.internal

import com.payriff.android.Payriff
import com.payriff.android.PayriffConfig
import com.payriff.android.testing.LogCapture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.net.URISyntaxException

/**
 * External launches from the 3-D Secure sheet (CONTRACT §16.0 item 5, §16.8; audit AND-01 and AND-02): whatever the
 * platform throws, the launcher returns an outcome and the host app keeps running. A fake platform stands in for
 * Android's Intent API.
 */
class ExternalLauncherTest {

    @get:Rule
    val logs = LogCapture()

    /** A stand-in for an Android intent. */
    class FakeIntent(val url: String, var dataScheme: String?, val fallback: String? = null, val pkg: String? = null) {
        var hardened = false
    }

    private class FakePlatform : ExternalLauncher.Platform<FakeIntent> {
        var parseFailure: Exception? = null
        val startFailures = mutableMapOf<String, RuntimeException>()
        var fallbackFailure: RuntimeException? = null
        val started = mutableListOf<FakeIntent>()

        override fun parseIntentUrl(url: String): FakeIntent {
            parseFailure?.let { throw it }
            val parts = url.substringAfter("#Intent;").substringBefore(";end").split(';').filter { '=' in it }
                .associate { it.substringBefore('=') to it.substringAfter('=') }
            return FakeIntent(url, parts["scheme"], parts["S.browser_fallback_url"], parts["package"])
        }

        override fun viewIntent(url: String) = FakeIntent(url, Urls.parse(url)?.scheme)

        override fun harden(intent: FakeIntent) {
            intent.hardened = true
        }

        override fun dataScheme(intent: FakeIntent): String? = intent.dataScheme

        override fun browserFallbackUrl(intent: FakeIntent): String? {
            fallbackFailure?.let { throw it }
            return intent.fallback
        }

        override fun targetPackage(intent: FakeIntent): String? = intent.pkg

        override fun storeIntent(packageName: String) = FakeIntent("market://details?id=$packageName", "market")

        override fun start(intent: FakeIntent) {
            startFailures[intent.dataScheme ?: ""]?.let { throw it }
            started += intent
        }
    }

    private val platform = FakePlatform()
    private val launcher = ExternalLauncher(platform)
    private val loaded = mutableListOf<String>()

    @Before
    fun setUp() = Payriff.configure(PayriffConfig(enableLogging = true))

    @After
    fun tearDown() = Payriff.reset()

    private fun open(url: String) = launcher.open(url) { loaded += it }

    @Test
    fun appLinksOpenHardened() {
        assertEquals(ExternalOutcome.OPENED_APP, open("tel:+994121234567"))
        assertEquals(ExternalOutcome.OPENED_APP, open("intent://pay?id=1#Intent;scheme=abbpay;package=az.abb.mobile;end"))
        assertEquals(listOf("tel", "abbpay"), platform.started.map { it.dataScheme })
        assertTrue("BROWSABLE, no component, no selector, no URI grants", platform.started.all { it.hardened })
    }

    /** AND-01: `Intent.parseUri` throws NumberFormatException for `launchFlags=zz`; that used to kill the app. */
    @Test
    fun aMalformedIntentUrlNeverCrashes() {
        for (failure in listOf(NumberFormatException("For input string: \"zz\""), IllegalArgumentException("bad"), URISyntaxException("x", "bad"))) {
            platform.parseFailure = failure
            assertEquals("$failure", ExternalOutcome.FAILED, open("intent:#Intent;launchFlags=zz;end"))
        }
        assertTrue(platform.started.isEmpty())
        assertTrue(logs.lines.any { "NumberFormatException" in it })
    }

    /** AND-02: `startActivity` can throw FileUriExposedException or "Failure from system"; never let it escape. */
    @Test
    fun aFailingStartNeverCrashes() {
        platform.startFailures["tel"] = RuntimeException("Failure from system")
        assertEquals(ExternalOutcome.FAILED, open("tel:+994121234567"))
        platform.startFailures["abbpay"] = SecurityException("Permission Denial")
        assertEquals(ExternalOutcome.FAILED, open("intent://pay#Intent;scheme=abbpay;end"))
        platform.startFailures["bank"] = IllegalStateException("odd")
        assertEquals(ExternalOutcome.FAILED, open("bank://pay"))
    }

    @Test
    fun fileAndContentDataIsRefused() {
        for (url in listOf(
            "file:///sdcard/Download/x.txt",
            "content://com.example.provider/hidden-record",
            "intent:///sdcard/Download/x.txt#Intent;scheme=file;end",
            "intent://com.example.provider/hidden-record#Intent;scheme=content;package=com.example;end",
            "intent:///x#Intent;scheme=FILE;S.browser_fallback_url=https://example.com/;end",
        )) {
            assertEquals(url, ExternalOutcome.FAILED, open(url))
        }
        assertTrue("nothing started, no store page, no fallback", platform.started.isEmpty() && loaded.isEmpty())
        assertFalse("the URL content is never logged", logs.lines.any { "sdcard" in it || "hidden-record" in it })
    }

    @Test
    fun oversizedUrlsAreRefused() {
        val huge = "tel:" + "1".repeat(ExternalLauncher.MAX_URL_LENGTH)
        assertEquals(ExternalOutcome.FAILED, open(huge))
        assertEquals(ExternalOutcome.OPENED_APP, open("tel:" + "1".repeat(ExternalLauncher.MAX_URL_LENGTH - 4)))
    }

    @Test
    fun withoutAnAppTheFallbackUrlOrTheStoreIsUsed() {
        platform.startFailures["abbpay"] = RuntimeException("No Activity found to handle Intent")
        val withFallback = "intent://pay#Intent;scheme=abbpay;package=az.abb.mobile;S.browser_fallback_url=https://abb.example/pay;end"
        assertEquals(ExternalOutcome.LOADED_FALLBACK, open(withFallback))
        assertEquals(listOf("https://abb.example/pay"), loaded)

        assertEquals("only https fallbacks", ExternalOutcome.OPENED_APP, open("intent://pay#Intent;scheme=abbpay;package=az.abb.mobile;S.browser_fallback_url=javascript:alert(1);end"))
        assertEquals("market://details?id=az.abb.mobile", platform.started.single().url)
        assertFalse("the store intent is not a web link", platform.started.single().hardened)

        platform.fallbackFailure = IllegalStateException("bad extras")
        assertEquals(ExternalOutcome.OPENED_APP, open("intent://pay#Intent;scheme=abbpay;package=az.abb.mobile;end"))
        platform.startFailures["market"] = RuntimeException("No Activity found to handle Intent")
        assertEquals("no app, no fallback, no store", ExternalOutcome.FAILED, open("intent://pay#Intent;scheme=abbpay;package=az.abb.mobile;end"))
        assertEquals(ExternalOutcome.FAILED, open("intent://pay#Intent;scheme=abbpay;end"))
    }

    /** CONTRACT §16.8: the 3-D Secure sheet loads only an https fallback; a plain-http one is never requested. */
    @Test
    fun onlyHttpsFallbacksAreLoaded() {
        platform.startFailures["abbpay"] = RuntimeException("No Activity found to handle Intent")

        val https = "intent://pay#Intent;scheme=abbpay;S.browser_fallback_url=https://abb.example/pay;end"
        assertEquals(ExternalOutcome.LOADED_FALLBACK, open(https))
        assertEquals(listOf("https://abb.example/pay"), loaded)

        val http = "intent://pay#Intent;scheme=abbpay;package=az.abb.mobile;S.browser_fallback_url=http://abb.example/pay;end"
        assertEquals("the store instead", ExternalOutcome.OPENED_APP, open(http))
        assertEquals(listOf("https://abb.example/pay"), loaded)
        assertEquals("market://details?id=az.abb.mobile", platform.started.single().url)
    }

    @Test
    fun unparseableUrlsFail() {
        assertEquals(ExternalOutcome.FAILED, open("no scheme"))
        assertEquals(ExternalOutcome.FAILED, open(""))
        assertTrue(platform.started.isEmpty())
    }
}

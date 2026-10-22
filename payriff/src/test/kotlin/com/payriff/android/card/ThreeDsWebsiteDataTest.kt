package com.payriff.android.card

import com.payriff.android.Payriff
import com.payriff.android.PayriffConfig
import com.payriff.android.internal.card.ThreeDsWebsiteData
import com.payriff.android.testing.LogCapture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * CONTRACT §9.1a on Android: the origins the 3-D Secure WebViews request, and how their web storage and cookies are
 * removed when the payment ends. A fake platform stands in for `WebStorage` and `CookieManager`.
 */
class ThreeDsWebsiteDataTest {

    @get:Rule
    val logs = LogCapture()

    /** Records every call in order. [cookies] answers `getCookie` by URL; the calls in [failing] throw. */
    private class FakePlatform(
        private val cookies: Map<String, String?> = emptyMap(),
        private val failing: Set<String> = emptySet(),
    ) : ThreeDsWebsiteData.Platform {
        val calls = mutableListOf<String>()

        override fun deleteOrigin(origin: String) = call("deleteOrigin $origin")

        override fun cookies(url: String): String? {
            call("getCookie $url")
            return cookies[url]
        }

        override fun setCookie(url: String, cookie: String) = call("setCookie $url $cookie")

        override fun flush() = call("flush")

        private fun call(description: String) {
            calls += description
            if (description in failing) throw IllegalStateException("WebView provider failed")
        }
    }

    @Before
    fun setUp() = Payriff.configure(PayriffConfig(enableLogging = true))

    @After
    fun tearDown() = Payriff.reset()

    @Test
    fun theOriginIsTheSchemeTheHostAndAPortThatIsNotTheDefault() {
        assertEquals("https://acs.bank.az", ThreeDsWebsiteData.originOf("https://ACS.Bank.az/acs/challenge?creq=x#top"))
        assertEquals("https://acs.bank.az", ThreeDsWebsiteData.originOf("https://acs.bank.az:443/"))
        assertEquals("https://acs.bank.az:8443", ThreeDsWebsiteData.originOf("https://acs.bank.az:8443/acs"))
        assertEquals("http://bank.az", ThreeDsWebsiteData.originOf("http://bank.az:80"))
        assertEquals("https://bank.az", ThreeDsWebsiteData.originOf("https://user:secret@bank.az/"))
        assertEquals("https://[::1]:8443", ThreeDsWebsiteData.originOf("https://[::1]:8443/x"))
        val notWebPages = listOf(
            null, "", "about:blank", "data:text/html,x", "blob:https://bank.az/1", "javascript:void(0)",
            "intent://pay#Intent;end", "bankapp://confirm", "file:///sdcard/x", "https:///path", "https://bank.az:99999/",
        )
        for (url in notWebPages) assertNull(url, ThreeDsWebsiteData.originOf(url))
    }

    @Test
    fun theCookiePathIsEveryPrefixOfTheUrlsPathWithAndWithoutItsSlash() {
        assertEquals("/", ThreeDsWebsiteData.normalizePath(""))
        assertEquals("/acs", ThreeDsWebsiteData.normalizePath("/acs"))
        assertEquals(listOf("/"), ThreeDsWebsiteData.pathPrefixes("/"))
        assertEquals(listOf("/"), ThreeDsWebsiteData.pathPrefixes(""))
        // A prefix the path goes on from with a "/" is expired with and without that "/" (CONTRACT §9.1a).
        assertEquals(listOf("/", "/acs", "/acs/", "/acs/challenge"), ThreeDsWebsiteData.pathPrefixes("/acs/challenge"))
        assertEquals(
            listOf("/", "/acs", "/acs/", "/acs/challenge", "/acs/challenge/"),
            ThreeDsWebsiteData.pathPrefixes("/acs/challenge/"),
        )
        assertEquals("a trailing slash is kept", listOf("/", "/acs", "/acs/"), ThreeDsWebsiteData.pathPrefixes("/acs/"))
        assertEquals(listOf("/", "/a", "/a/", "/a/b", "/a/b/", "/a/b/c"), ThreeDsWebsiteData.pathPrefixes("/a/b/c"))
        // Only real prefixes of the raw path: an empty segment stays, and the path is not decoded.
        assertEquals(listOf("/", "/a", "/a/", "/a//", "/a//b"), ThreeDsWebsiteData.pathPrefixes("/a//b"))
        assertEquals(
            listOf("/", "/3ds%20v2", "/3ds%20v2/", "/3ds%20v2/step"),
            ThreeDsWebsiteData.pathPrefixes("/3ds%20v2/step"),
        )
        assertEquals("not a path", listOf("/"), ThreeDsWebsiteData.pathPrefixes("relative/path"))
    }

    @Test
    fun theTrailingSlashFormsAreExpiredAsInTheFlutterSdk() {
        // The same setCookie values, in the same order, as the Flutter SDK expires for this URL (its WebsiteDataTest).
        val data = ThreeDsWebsiteData()
        data.record("https://acs.bank.example/acs/challenge?creq=x")
        val platform = FakePlatform(cookies = mapOf("https://acs.bank.example/acs/challenge" to "sid=1"))
        data.clear(platform)
        val expected = listOf("/", "/acs", "/acs/", "/acs/challenge").flatMap { path ->
            listOf(
                "sid=; Max-Age=0; Path=$path; Secure",
                "sid=; Max-Age=0; Path=$path; Domain=acs.bank.example; Secure",
                "sid=; Max-Age=0; Path=$path; Domain=bank.example; Secure",
            )
        }
        assertEquals(
            expected.map { "setCookie https://acs.bank.example/acs/challenge $it" },
            platform.calls.filter { it.startsWith("setCookie") },
        )
        assertFalse("not a prefix of the path", platform.calls.any { "Path=/acs/challenge/" in it })
    }

    @Test
    fun eachOriginIsRecordedOnceAndAnythingElseIsIgnored() {
        val data = ThreeDsWebsiteData()
        assertTrue(data.isEmpty)
        data.record("about:blank")
        data.record("bankapp://confirm")
        data.record(null)
        assertTrue("only http(s) pages count", data.isEmpty)
        data.record("https://acs.bank.az/acs/challenge")
        data.record("https://ACS.bank.az/acs/otp?step=2")
        data.record("https://api.payriff.com/api/v3/gateway/kapital/callback/ORD-1")
        assertFalse(data.isEmpty)
        val platform = FakePlatform()
        data.clear(platform)
        assertEquals(
            listOf("deleteOrigin https://acs.bank.az", "deleteOrigin https://api.payriff.com"),
            platform.calls.filter { it.startsWith("deleteOrigin") },
        )
    }

    @Test
    fun recordingIsCappedAndSafeFromWebViewThreads() {
        val data = ThreeDsWebsiteData()
        val threads = (0 until 4).map { thread -> Thread { repeat(500) { data.record("https://h${it % 100}.bank.az/page$thread") } } }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)
        val platform = FakePlatform()
        data.clear(platform)
        assertEquals(ThreeDsWebsiteData.MAX_ORIGINS, platform.calls.count { it.startsWith("deleteOrigin") })
    }

    @Test
    fun cookieDomainsAreTheHostAndItsParentsOfAtLeastTwoLabels() {
        assertEquals(listOf("acs.bank.az", "bank.az"), ThreeDsWebsiteData.cookieDomains("acs.bank.az"))
        assertEquals(
            listOf("a.secure.bank.co.uk", "secure.bank.co.uk", "bank.co.uk", "co.uk"),
            ThreeDsWebsiteData.cookieDomains("a.secure.bank.co.uk"),
        )
        assertEquals(listOf("bank.az"), ThreeDsWebsiteData.cookieDomains("bank.az"))
        assertEquals(listOf("localhost"), ThreeDsWebsiteData.cookieDomains("localhost"))
        // IP addresses have no parent domains, and a host with an empty label is taken as it is.
        assertEquals(listOf("192.168.0.10"), ThreeDsWebsiteData.cookieDomains("192.168.0.10"))
        assertEquals(listOf("[2001:db8::1]"), ThreeDsWebsiteData.cookieDomains("[2001:db8::1]"))
        assertEquals(listOf("bank.az."), ThreeDsWebsiteData.cookieDomains("bank.az."))
    }

    @Test
    fun aCookieIsExpiredHostOnlyAndForTheHostAndEachParentDomain() {
        // Path=/ on an http page: host-only, then each domain, with no Secure.
        assertEquals(
            listOf(
                "JSESSIONID=; Max-Age=0; Path=/",
                "JSESSIONID=; Max-Age=0; Path=/; Domain=acs.bank.az",
                "JSESSIONID=; Max-Age=0; Path=/; Domain=bank.az",
            ),
            ThreeDsWebsiteData.expiryCookies("JSESSIONID", "acs.bank.az", listOf("/"), secure = false),
        )
        assertEquals(listOf("a", "b", "c"), ThreeDsWebsiteData.cookieNames("a=1; b=x=y;c=; a=2"))
        assertEquals(listOf("sid"), ThreeDsWebsiteData.cookieNames(" sid = v ; =orphan; nameless"))
        assertEquals(emptyList<String>(), ThreeDsWebsiteData.cookieNames(null))
        assertEquals(emptyList<String>(), ThreeDsWebsiteData.cookieNames(""))
    }

    @Test
    fun anHttpsCookieIsExpiredForEveryPathPrefixWithSecure() {
        // Every path prefix, host-only then each domain, with "; Secure" for an https URL (CONTRACT §9.1a).
        assertEquals(
            listOf(
                "sid=; Max-Age=0; Path=/; Secure",
                "sid=; Max-Age=0; Path=/; Domain=acs.bank.az; Secure",
                "sid=; Max-Age=0; Path=/; Domain=bank.az; Secure",
                "sid=; Max-Age=0; Path=/acs; Secure",
                "sid=; Max-Age=0; Path=/acs; Domain=acs.bank.az; Secure",
                "sid=; Max-Age=0; Path=/acs; Domain=bank.az; Secure",
            ),
            ThreeDsWebsiteData.expiryCookies("sid", "acs.bank.az", listOf("/", "/acs"), secure = true),
        )
        // A __Secure- cookie goes with the Secure flag like any other; a __Host- cookie is only host-only, Path=/, Secure.
        assertEquals(
            listOf("__Secure-sid=; Max-Age=0; Path=/; Secure", "__Secure-sid=; Max-Age=0; Path=/; Domain=bank.az; Secure"),
            ThreeDsWebsiteData.expiryCookies("__Secure-sid", "bank.az", listOf("/"), secure = true),
        )
        assertEquals(
            listOf("__Host-sid=; Max-Age=0; Path=/; Secure"),
            ThreeDsWebsiteData.expiryCookies("__Host-sid", "acs.bank.az", listOf("/", "/acs"), secure = true),
        )
    }

    @Test
    fun clearingRemovesWebStorageThenCookiesThenFlushes() {
        val data = ThreeDsWebsiteData()
        // Recorded with its path: cookies are expired at the page URL for every path prefix, with Secure (https).
        data.record("https://acs.bank.az/acs?creq=x")
        data.record("https://api.payriff.com:8443/callback/ORD-1")
        val platform = FakePlatform(
            cookies = mapOf("https://acs.bank.az/acs" to "sid=1", "https://api.payriff.com:8443/callback/ORD-1" to null),
        )
        data.clear(platform)
        assertEquals(
            listOf(
                "deleteOrigin https://acs.bank.az",
                "deleteOrigin https://api.payriff.com:8443",
                "getCookie https://acs.bank.az/acs",
                "setCookie https://acs.bank.az/acs sid=; Max-Age=0; Path=/; Secure",
                "setCookie https://acs.bank.az/acs sid=; Max-Age=0; Path=/; Domain=acs.bank.az; Secure",
                "setCookie https://acs.bank.az/acs sid=; Max-Age=0; Path=/; Domain=bank.az; Secure",
                "setCookie https://acs.bank.az/acs sid=; Max-Age=0; Path=/acs; Secure",
                "setCookie https://acs.bank.az/acs sid=; Max-Age=0; Path=/acs; Domain=acs.bank.az; Secure",
                "setCookie https://acs.bank.az/acs sid=; Max-Age=0; Path=/acs; Domain=bank.az; Secure",
                "getCookie https://api.payriff.com:8443/callback/ORD-1",
                "flush",
            ),
            platform.calls,
        )
    }

    @Test
    fun aPaymentThatLoadedNoPageTouchesNothing() {
        val platform = FakePlatform()
        ThreeDsWebsiteData().clear(platform)
        assertTrue(platform.calls.isEmpty())
    }

    @Test
    fun aFailingCallNeverThrowsAndTheRestStillRuns() {
        val data = ThreeDsWebsiteData()
        data.record("https://acs.bank.az/")
        data.record("https://bank.az/")
        val platform = FakePlatform(
            cookies = mapOf("https://acs.bank.az/" to "sid=1", "https://bank.az/" to "id=2"),
            failing = setOf(
                "deleteOrigin https://acs.bank.az",
                "getCookie https://acs.bank.az/",
                "setCookie https://bank.az/ id=; Max-Age=0; Path=/; Secure",
                "flush",
            ),
        )
        data.clear(platform)
        assertEquals(
            listOf(
                "deleteOrigin https://acs.bank.az",
                "deleteOrigin https://bank.az",
                "getCookie https://acs.bank.az/",
                "getCookie https://bank.az/",
                "setCookie https://bank.az/ id=; Max-Age=0; Path=/; Secure",
                "setCookie https://bank.az/ id=; Max-Age=0; Path=/; Domain=bank.az; Secure",
                "flush",
            ),
            platform.calls,
        )
        assertTrue(logs.lines.toString(), logs.lines.any { "4 call(s) failed for 2 origin(s)" in it })
    }
}

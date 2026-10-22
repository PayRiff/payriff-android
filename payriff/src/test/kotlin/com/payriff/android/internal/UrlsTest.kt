package com.payriff.android.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlsTest {

    @Test
    fun parsesTheNeededParts() {
        val url = checkNotNull(Urls.parse("HTTPS://User@Merchant.Example:8443/Pay/Callback?x=1#frag"))
        assertEquals("https", url.scheme)
        assertEquals("merchant.example", url.host)
        assertEquals(8443, url.port)
        assertEquals("/Pay/Callback", url.path)
        assertEquals(443, checkNotNull(Urls.parse("https://a.example")).effectivePort)
        assertEquals(80, checkNotNull(Urls.parse("http://a.example/")).effectivePort)
        assertEquals("[::1]", Urls.parse("http://[::1]:8080/x")?.host)
    }

    @Test
    fun keepsCharactersThatJavaNetUriRejects() {
        val url = checkNotNull(Urls.parse("https://acs.bank.example/challenge?data={a|b}^"))
        assertEquals("acs.bank.example", url.host)
        assertEquals("/challenge", url.path)
    }

    @Test
    fun rejectsUnparseableUrls() {
        assertNull(Urls.parse(""))
        assertNull(Urls.parse("no-scheme"))
        assertNull(Urls.parse(":missing"))
        assertNull(Urls.parse("1http://x"))
        assertNull(Urls.parse("https://host:99999/"))
        assertNull(Urls.parse("https://host:abc/"))
    }

    @Test
    fun opaqueUrlsHaveNoHost() {
        assertNull(Urls.parse("mailto:support@payriff.com")?.host)
        assertEquals("tel", Urls.parse("tel:+994121234567")?.scheme)
        assertEquals("blob", Urls.parse("blob:https://pay.payriff.com/1")?.scheme)
    }

    @Test
    fun absoluteUrlChecks() {
        assertTrue(Urls.isAbsoluteHttpsUrl("https://merchant.example/cb"))
        assertFalse(Urls.isAbsoluteHttpsUrl("http://merchant.example/cb"))
        assertFalse(Urls.isAbsoluteHttpsUrl("/cb"))
        assertFalse(Urls.isAbsoluteHttpsUrl("https:///cb"))
        assertFalse(Urls.isAbsoluteHttpsUrl("myapp://payriff"))
    }

    /** CONTRACT §11: http(s) without query and fragment; any other scheme only as `<scheme>:…`. */
    @Test
    fun redactionDropsQueryAndContent() {
        assertEquals("https://merchant.example/cb", Urls.redact("https://merchant.example/cb?orderId=1&token=secret#x"))
        assertEquals("http://10.0.2.2:8080/pay", Urls.redact("http://10.0.2.2:8080/pay#frag"))
        assertEquals("data:…", Urls.redact("data:text/html;base64,PGh0bWw+"))
        assertEquals("javascript:…", Urls.redact("javascript:alert(1)"))
        assertEquals("intent:…", Urls.redact("intent://pay?id=1#Intent;scheme=abbpay;end"))
        assertEquals("tel:…", Urls.redact("tel:+994121234567"))
        assertEquals("mailto:…", Urls.redact("mailto:support@payriff.com"))
        assertEquals("birbank:…", Urls.redact("BIRBANK://payment?id=1"))
        assertEquals("about:…", Urls.redact("about:blank"))
        assertEquals("<unparseable url>", Urls.redact("no scheme"))
        assertEquals("null", Urls.redact(null))
    }

    @Test
    fun pathSegmentsKeepOnlyUnreservedCharactersExceptTheDot() {
        assertEquals("A_b-c~d", Urls.encodePathSegment("A_b-c~d"))
        assertEquals("%2E%2E%2Fx", Urls.encodePathSegment("../x"))
        assertEquals("%25%2B%3A%40", Urls.encodePathSegment("%+:@"))
    }
}

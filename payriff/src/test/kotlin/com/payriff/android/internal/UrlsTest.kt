package com.payriff.android.internal

import com.payriff.android.PayriffConfig
import com.payriff.android.PayriffEnvironment
import com.payriff.android.PayriffRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.CharBuffer

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

    /** CONTRACT §11: the path only on Payriff's API hosts; any other host as `scheme://host[:port]`. */
    @Test
    fun redactionKeepsThePathOnlyOnPayriffsApiHosts() {
        assertEquals(
            "https://api.payriff.com/api/v3/client/orders/7c9e…0ae7/pay",
            Urls.redact("https://api.payriff.com/api/v3/client/orders/7c9e6679-7425-40de-944b-e07fc1f90ae7/pay?x=1#y"),
        )
        assertEquals("https://sb-api.payriff.com/api/v3/client/orders/o1/status", Urls.redact("https://sb-api.payriff.com/api/v3/client/orders/o1/status"))
        assertEquals("https://api.payriff.com", Urls.redact("https://api.payriff.com"))
        assertEquals("https://api.payriff.com/", Urls.redact("https://api.payriff.com/?x=1"))
        // Region sa's host (CONTRACT §16.2) keeps its path like api.payriff.com.
        assertEquals(
            "https://api.gccpay.sa/api/v3/client/orders/7c9e…0ae7/pay",
            Urls.redact("https://user:pw@api.gccpay.sa/api/v3/client/orders/7c9e6679-7425-40de-944b-e07fc1f90ae7/pay?x=1#y"),
        )
        assertEquals("https://api.gccpay.sa/api/v3/client/orders/o1/status", Urls.redact("HTTPS://API.GCCPAY.SA/api/v3/client/orders/o1/status"))
        // The host of every region and environment (PayriffConfig.apiBaseUrl) keeps its path.
        for (region in PayriffRegion.entries) {
            for (environment in PayriffEnvironment.entries) {
                val base = PayriffConfig(environment = environment, region = region).apiBaseUrl
                assertEquals("$region $environment", "$base/api/v3/client/orders/o1", Urls.redact("$base/api/v3/client/orders/o1?x=1"))
            }
        }
        // Bank, 3-D Secure and merchant pages: the path, the query and the fragment are all dropped.
        assertEquals("https://merchant.example", Urls.redact("https://merchant.example/cb?orderId=1&token=secret#x"))
        assertEquals("https://acs.bank.example", Urls.redact("https://acs.bank.example/challenge/SESSION-1/step"))
        assertEquals("https://acs.bank.example", Urls.redact("https://acs.bank.example/s3cr3t#frag"))
        // Other Payriff hosts, and hosts that only look like Payriff's, are other hosts.
        assertEquals("https://pay.payriff.com", Urls.redact("https://pay.payriff.com/order/o1"))
        assertEquals("https://payriff.com", Urls.redact("https://payriff.com/api/v3"))
        assertEquals("https://api.payriff.com.evil.example", Urls.redact("https://api.payriff.com.evil.example/api/v3/client/orders/o1"))
        assertEquals("https://evil-api.payriff.com.example", Urls.redact("https://evil-api.payriff.com.example/x"))
        assertEquals("https://pay.gccpay.sa", Urls.redact("https://pay.gccpay.sa/order/o1"))
        assertEquals("https://gccpay.sa", Urls.redact("https://gccpay.sa/api/v3"))
        assertEquals("https://api.gccpay.sa.evil.example", Urls.redact("https://api.gccpay.sa.evil.example/api/v3/client/orders/o1"))
        assertEquals("https://evil-api.gccpay.sa", Urls.redact("https://evil-api.gccpay.sa/x"))
        assertEquals("https://evil.example", Urls.redact("https://api.gccpay.sa@evil.example/api/v3/client/orders/o1"))
    }

    @Test
    fun redactionKeepsAnExplicitPortAndNothingElseOfOtherHosts() {
        assertEquals("http://10.0.2.2:8080", Urls.redact("http://10.0.2.2:8080/pay#frag"))
        assertEquals("https://acs.bank.example:8443", Urls.redact("https://acs.bank.example:8443/c?x=1"))
        assertEquals("http://[::1]:8080", Urls.redact("http://[::1]:8080/x"))
        assertEquals("https://api.payriff.com:8443/api/v3", Urls.redact("https://api.payriff.com:8443/api/v3?x"))
        assertEquals("the port as a number", "https://api.payriff.com:443/x", Urls.redact("https://api.payriff.com:0443/x"))
    }

    @Test
    fun redactionNeverShowsTheUserInfo() {
        assertEquals("https://acs.bank.example", Urls.redact("https://user:p%40ss@acs.bank.example/c"))
        assertEquals(
            "https://api.payriff.com/api/v3/client/orders/o1",
            Urls.redact("https://user:password@api.payriff.com/api/v3/client/orders/o1"),
        )
        assertEquals("an @ inside the user info", "https://acs.bank.example:8443", Urls.redact("https://a@b:c@acs.bank.example:8443/x"))
        assertEquals("http://[::1]", Urls.redact("http://token@[::1]/x"))
        // User info that names Payriff's host does not make the real host one of Payriff's.
        assertEquals("https://evil.example", Urls.redact("https://api.payriff.com@evil.example/api/v3/client/orders/o1"))
        assertEquals("https://evil.example", Urls.redact("https://api.payriff.com:443@evil.example/x"))
        for (url in listOf("https://user:SECRET@api.payriff.com/x", "https://SECRET@acs.bank.example/x", "http://SECRET:@10.0.2.2:8080")) {
            assertFalse(url, "SECRET" in Urls.redact(url))
        }
    }

    @Test
    fun longPathSegmentsAreShortenedToTheirFirstAndLastFourCharacters() {
        assertEquals("15 characters stay", "https://api.payriff.com/0123456789abcde", Urls.redact("https://api.payriff.com/0123456789abcde"))
        assertEquals("16 are shortened", "https://api.payriff.com/0123…cdef", Urls.redact("https://api.payriff.com/0123456789abcdef"))
        assertEquals(
            "every long segment; empty segments and the trailing slash stay",
            "https://api.payriff.com/api//v3/aaaa…zzzz/b/cccc…dddd/",
            Urls.redact("https://api.payriff.com/api//v3/${"a".repeat(20)}${"z".repeat(20)}/b/${"c".repeat(8)}${"d".repeat(8)}/"),
        )
        assertEquals(
            "percent-encoded text counts as written",
            "https://api.payriff.com/api/v3/client/orders/a%20…%20b",
            Urls.redact("https://api.payriff.com/api/v3/client/orders/a%20b%20c%20d%20b"),
        )
        // Characters are code points: a surrogate pair is never split.
        val emoji = "😀"
        assertEquals(
            "https://api.payriff.com/$emoji${emoji}ab…yz$emoji$emoji",
            Urls.redact("https://api.payriff.com/$emoji${emoji}abcdefghijklmnopqrstuvwxyz$emoji$emoji"),
        )
        assertEquals("https://api.payriff.com/${emoji.repeat(15)}", Urls.redact("https://api.payriff.com/${emoji.repeat(15)}"))
        assertEquals("https://api.payriff.com/${emoji.repeat(4)}…${emoji.repeat(4)}", Urls.redact("https://api.payriff.com/${emoji.repeat(16)}"))
        assertEquals("the path's case stays", "https://api.payriff.com/API/V3/Client", Urls.redact("https://api.payriff.com/API/V3/Client"))
    }

    @Test
    fun upperCaseSchemesAndHostsAreRecognisedAndLowerCased() {
        assertEquals(
            "https://api.payriff.com/api/v3/client/orders/7c9e…0ae7",
            Urls.redact("HTTPS://API.PAYRIFF.COM/api/v3/client/orders/7c9e6679-7425-40de-944b-e07fc1f90ae7?X=1"),
        )
        assertEquals("https://sb-api.payriff.com:8443/api", Urls.redact("Https://Sb-Api.Payriff.Com:8443/api"))
        assertEquals("http://acs.bank.example:8080", Urls.redact("HTTP://User:Pw@ACS.Bank.Example:8080/Path?Q#F"))
        assertEquals("birbank:…", Urls.redact("BIRBANK://payment?id=1"))
        assertEquals("tel:…", Urls.redact("TEL:+994121234567"))
    }

    @Test
    fun redactionCapsTheLengthAndReadsAnyCharSequence() {
        val long = Urls.redact("https://api.payriff.com" + "/abc".repeat(100))
        assertEquals(201, long.length)
        assertTrue(long, long.startsWith("https://api.payriff.com/abc/") && long.endsWith("…"))
        // The HTTP log passes views of char arrays that it wipes afterwards.
        val view = CharBuffer.wrap("https://user:pw@acs.bank.example/c?token=T".toCharArray())
        assertEquals("https://acs.bank.example", Urls.redact(view))
    }

    /** CONTRACT §11: any scheme but http(s) only as `<scheme>:…`. */
    @Test
    fun otherSchemesShowOnlyTheirScheme() {
        assertEquals("data:…", Urls.redact("data:text/html;base64,PGh0bWw+"))
        assertEquals("javascript:…", Urls.redact("javascript:alert(1)"))
        assertEquals("intent:…", Urls.redact("intent://pay?id=1#Intent;scheme=abbpay;end"))
        assertEquals("tel:…", Urls.redact("tel:+994121234567"))
        assertEquals("mailto:…", Urls.redact("mailto:support@payriff.com"))
        assertEquals("birbank:…", Urls.redact("BIRBANK://payment?id=1"))
        assertEquals("about:…", Urls.redact("about:blank"))
        assertEquals("blob:…", Urls.redact("blob:https://api.payriff.com/1"))
        assertEquals("<unparseable url>", Urls.redact("no scheme"))
        assertEquals("<unparseable url>", Urls.redact("/api/v3/client/orders/o1"))
        assertEquals("<unparseable url>", Urls.redact(""))
        assertEquals("<unparseable url>", Urls.redact("https://api.payriff.com:99999/x"))
        assertEquals("null", Urls.redact(null))
        // An http(s) URL without a host shows its scheme only.
        assertEquals("https:…", Urls.redact("https:///api/v3/client/orders/o1"))
        assertEquals("https:…", Urls.redact("https:api.payriff.com/x"))
        assertEquals("http:…", Urls.redact("http://"))
    }

    @Test
    fun pathSegmentsKeepOnlyUnreservedCharactersExceptTheDot() {
        assertEquals("A_b-c~d", Urls.encodePathSegment("A_b-c~d"))
        assertEquals("%2E%2E%2Fx", Urls.encodePathSegment("../x"))
        assertEquals("%25%2B%3A%40", Urls.encodePathSegment("%+:@"))
    }
}

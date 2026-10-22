package com.payriff.android.internal

import com.payriff.android.Payriff
import com.payriff.android.PayriffConfig
import com.payriff.android.PayriffEnvironment
import com.payriff.android.PayriffLogEntry
import com.payriff.android.PayriffLogLevel
import com.payriff.android.PayriffLogListener
import com.payriff.android.PayriffLogger
import com.payriff.android.card.APP_ID
import com.payriff.android.card.CLIENT_SECRET
import com.payriff.android.card.FakeTransport
import com.payriff.android.card.PUBLISHABLE_KEY
import com.payriff.android.card.credentials
import com.payriff.android.card.http
import com.payriff.android.card.ok
import com.payriff.android.internal.card.CardApiClient
import com.payriff.android.internal.card.EncryptedCard
import com.payriff.android.testing.FakeHttpServer
import com.payriff.android.testing.HostAppRule
import com.payriff.android.testing.LogCapture
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * CONTRACT §11, HTTP details: every API call logs its request and response as pretty-printed JSON in debuggable apps,
 * and that log never shows a secret. The first tests mirror the Flutter SDK's `test/http_log_test.dart` case by case.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HttpLogTest {

    @get:Rule
    val logs = LogCapture()

    @get:Rule
    val hostApp = HostAppRule(debuggable = true)

    @After
    fun tearDown() {
        Payriff.reset()
        PayriffLogger.listener = null
    }

    // http_log_test.dart: redactHeaders

    @Test
    fun redactHeadersRedactsSecretsAndKeepsTheRest() {
        val shown = HttpLog.redactHeaders(
            mapOf(
                "Authorization" to "0123456789ABCDEF0123456789ABCDEF",
                "X-Client-Secret" to "pr_cs_v1_secret",
                "x-secret-key" to "WRAPPED+/=",
                "X-Public-Key" to "pr_pk_live_TestPublishableKey00000000000000000000",
                "X-Client-App-Id" to "com.payriff.example.flutter",
                "Content-Type" to "application/json",
            ),
        )
        assertEquals(
            mapOf(
                "Authorization" to "***(32)",
                "Content-Type" to "application/json",
                "X-Client-App-Id" to "com.payriff.example.flutter",
                "X-Client-Secret" to "***(15)",
                "X-Public-Key" to "pr_pk_live_***(49)",
                "x-secret-key" to "***(10)",
            ),
            shown,
        )
        assertEquals("sorted by name, ignoring case", listOf("Authorization", "Content-Type", "X-Client-App-Id", "X-Client-Secret", "X-Public-Key", "x-secret-key"), shown.keys.toList())
        assertEquals(
            "a key without the publishable prefix shows nothing of itself",
            mapOf("authorization" to "***(3)", "x-public-key" to "***(32)"),
            HttpLog.redactHeaders(mapOf("x-public-key" to "0123456789ABCDEF0123456789ABCDEF", "authorization" to "abc")),
        )
    }

    // http_log_test.dart: redactBody

    @Test
    fun redactBodyRedactsSecretFieldsAtAnyDepthAndMasksCardNumbers() {
        assertEquals(
            """{"code":"00000","payload":{"orderId":"o1","clientSecret":"***(7)","items":[{"encryptedMessage":"***(3)"}],"pan":"****1111","maskedPan":"411111******1111","creq":"***(4)","amount":1.5}}""",
            HttpLog.redactBody(
                """{"code":"00000","payload":{"orderId":"o1","clientSecret":"pr_cs_x","items":[{"encryptedMessage":"ENC"}],"pan":"4111111111111111","maskedPan":"411111******1111","creq":"CREQ","amount":1.5}}""",
            ),
        )
    }

    @Test
    fun theValuesOfTheBankFormFieldsAreHidden() {
        val shown = checkNotNull(
            HttpLog.redactBody(
                """{"clientRequest":[{"key":"creq","value":"CREQ-VALUE-42"},{"key":"threeDSSessionData","value":null},{"key":"other","value":"X-VALUE"}]}""",
            ),
        )
        assertFalse(shown.contains("CREQ-VALUE-42"))
        assertFalse(shown.contains("X-VALUE"))
        assertTrue(shown.contains("""{"key":"creq","value":"***(13)"}"""))
        assertTrue(shown.contains("""{"key":"threeDSSessionData","value":null}"""))
        assertEquals(
            """{"clientRequest":[{"key":"creq","value":"***(13)"},{"key":"threeDSSessionData","value":null},{"key":"other","value":"***(7)"}]}""",
            shown,
        )
        assertEquals(
            "URL values of form fields, and values that are not strings, are hidden too; a non-string key is no form field",
            """[{"key":"threeDSMethodUrl","value":"***(30)"},{"value":"***(21)","key":"k"},{"key":1,"value":"x"}]""",
            HttpLog.redactBody(
                """[{"key":"threeDSMethodUrl","value":"https://acs.bank.example/m?x=1"},{"value":{"a":[1,"bc"],"d":null},"key":"k"},{"key":1,"value":"x"}]""",
            ),
        )
    }

    @Test
    fun urlsLoseTheirQueryAndFragment() {
        assertEquals(
            """{"redirectUrl":"https://3ds.bank.example/r/o1"}""",
            HttpLog.redactBody("""{"redirectUrl":"https://3ds.bank.example/r/o1?token=T#x"}"""),
        )
        assertEquals(
            """["HTTPS://acs.bank.example/c","http://bank.example/3ds","/api/v3/client/orders?page=1","not a url?x"]""",
            HttpLog.redactBody("""["HTTPS://acs.bank.example/c?token=T#f","http://bank.example/3ds#frag","/api/v3/client/orders?page=1","not a url?x"]"""),
        )
        assertEquals("https://api.payriff.com/api/v3/client/orders", HttpLog.redactUrl("https://api.payriff.com/api/v3/client/orders?x=1#y"))
        assertEquals("tel:…", HttpLog.redactUrl("tel:+994500000000"))
    }

    @Test
    fun emptyNonJsonAndVeryLongBodiesAreDescribed() {
        assertNull(HttpLog.redactBody(null))
        assertNull(HttpLog.redactBody(""))
        assertEquals("\"(19 bytes, not JSON)\"", HttpLog.redactBody("<html>secret</html>"))
        assertEquals(
            "\"(${HttpLog.MAX_LOGGED_BODY_CHARS + 2} characters of JSON, too long to log)\"",
            HttpLog.redactBody("\"" + "a".repeat(HttpLog.MAX_LOGGED_BODY_CHARS) + "\""),
        )
        val longest = "\"" + "a".repeat(HttpLog.MAX_LOGGED_BODY_CHARS - 2) + "\""
        assertEquals("exactly at the limit it is logged", longest, HttpLog.redactBody(longest))
        assertEquals("the limit counts the redacted JSON", """{"clientSecret":"***(20000)"}""", HttpLog.redactBody("""{"clientSecret":"${"s".repeat(20_000)}"}"""))
        assertEquals("bytes, not characters", "\"(9 bytes, not JSON)\"", HttpLog.redactBody("<p>\u0259</p>"))
        assertNull("JSON null is no body", HttpLog.redactBody("null"))
    }

    // http_log_test.dart: describeRequest / describeResponse

    @Test
    fun requestsAndResponsesArePrettyPrintedJsonObjects() {
        val url = "https://api.payriff.com/api/v3/client/orders?x=1"
        val request = HttpLog.describeRequest("POST", url, mapOf("Authorization" to "0123456789ABCDEF0123456789ABCDEF"), """{"amount":0.1}""".toByteArray())
        assertEquals(
            """
            {
              "method": "POST",
              "url": "https://api.payriff.com/api/v3/client/orders",
              "headers": {
                "Authorization": "***(32)"
              },
              "body": {
                "amount": 0.1
              }
            }
            """.trimIndent(),
            request,
        )
        val response = HttpLog.describeResponse(200, "POST", url, mapOf("content-type" to "application/json"), """{"payload":{"clientSecret":"pr_cs_abc"}}""")
        assertEquals(
            """
            {
              "status": 200,
              "method": "POST",
              "url": "https://api.payriff.com/api/v3/client/orders",
              "headers": {
                "content-type": "application/json"
              },
              "body": {
                "payload": {
                  "clientSecret": "***(9)"
                }
              }
            }
            """.trimIndent(),
            response,
        )
        assertEquals(
            """
            {
              "method": "GET",
              "url": "https://api.payriff.com/api/v3/client/orders/o1",
              "headers": {},
              "body": null
            }
            """.trimIndent(),
            HttpLog.describeRequest("GET", "https://api.payriff.com/api/v3/client/orders/o1", emptyMap(), null),
        )
        assertEquals(
            """
            {
              "status": 502,
              "method": "GET",
              "url": "https://api.payriff.com/api/v3/client/orders/o1",
              "headers": {},
              "body": "(15 bytes, not JSON)"
            }
            """.trimIndent(),
            HttpLog.describeResponse(502, "GET", "https://api.payriff.com/api/v3/client/orders/o1", emptyMap(), "Bad Gateway\r\n\r\n"),
        )
        assertEquals(
            "malformed UTF-8 is replaced, as the response decoder does",
            "(4 bytes, not JSON)",
            JSONObject(HttpLog.describeRequest("POST", url, emptyMap(), byteArrayOf(0x7B, 0xFF.toByte()))).getString("body"),
        )
    }

    // Beyond the Flutter cases

    @Test
    fun everySecretFieldIsRedactedWhateverItsCaseOrType() {
        val names = listOf(
            "clientSecret", "client_secret", "encryptedMessage", "creq", "threeDSMethodData", "threeDSSessionData", "cvv", "cvc",
            "cardHolder", "card_holder", "expiryMonth", "expiryYear", "secretKey", "secret_key", "appKey", "app_key", "password",
        )
        for (name in names) {
            for (spelling in listOf(name, name.uppercase(Locale.ROOT), name.lowercase(Locale.ROOT))) {
                assertEquals(spelling, """{"a":[{"$spelling":"***(6)"}]}""", HttpLog.redactBody("""{"a":[{"$spelling":"SECRET"}]}"""))
            }
        }
        assertEquals(
            "numbers and booleans by their text, array items by their array's name, null stays null",
            """{"cvv":"***(3)","expiryMonth":"***(2)","password":"***(4)","secretKey":null,"cvc":["***(3)","***(3)",null],"clientSecret":"***(27)"}""",
            HttpLog.redactBody("""{"cvv":123,"expiryMonth":12,"password":true,"secretKey":null,"cvc":["123",456,null],"clientSecret":"https://x.example/?secret=1"}"""),
        )
    }

    @Test
    fun cardNumbersShowOnlyTheirLastFourDigits() {
        assertEquals(
            """{"pan":"****4242","cardNumber":"****0004","card_number":"****1111","PAN":"****9999","short":{"pan":"***(4)"},"masked":{"cardNumber":"4169 73** **** 2911"}}""",
            HttpLog.redactBody(
                """{"pan":"4242 4242 4242 4242","cardNumber":4000000000000004,"card_number":"4111-1111-1111-1111","PAN":"5555555555559999","short":{"pan":"1234"},"masked":{"cardNumber":"4169 73** **** 2911"}}""",
            ),
        )
    }

    @Test
    fun bodiesKeepTheirOrderNumbersAndCharacters() {
        assertEquals(
            "members in the order received, numbers as written, a repeated name at its first place with its last value",
            """{"z":1,"a":2.50,"m":-0.0e+10,"text":"q\"b\\s/\n\t\u0001 əş ✓ 😀 \ud800","list":[true,false,null],"empty":{},"none":[],"dup":2}""",
            HttpLog.redactBody(
                """ { "z" : 1 , "a":2.50, "m":-0.0e+10, "text":"q\"b\\s\/\n\t\u0001 \u0259ş ✓ 😀 \uD800", "list":[true, false, null],"empty":{ },"none":[ ],"dup":1,"dup":2 } """,
            ),
        )
    }

    /** `org.json` accepts all of these; the log describes them as not JSON instead of guessing (like `jsonDecode`). */
    @Test
    fun onlyStrictJsonIsParsed() {
        val bodies = listOf(
            "{a:1}", "{'a':1}", "[1,]", "{\"a\":1,}", "01", "{\"a\":1} x", "NaN", " ", "{\"a\":\"line\nbreak\"}", "\"\\x\"", "[1 2]", "tru",
            "-", "1.", "1e", "\"\\u12\"", "{\"a\" 1}", "/* c */ {}", "{\"a\":1}}", "[", "\"open",
        )
        for (body in bodies) {
            assertEquals(body, "\"(${body.toByteArray().size} bytes, not JSON)\"", HttpLog.redactBody(body))
        }
        for (valid in listOf("0", "-1.5E-3", "true", "\"\"", "[]", "{}", "[[],{}]", " \r\n\t[ 1 ]\n")) {
            assertEquals(valid, valid.filterNot { it.isWhitespace() }, HttpLog.redactBody(valid))
        }
    }

    @Test
    fun deeplyNestedJsonIsDescribedWithoutOverflowingTheStack() {
        val deepest = "[".repeat(HttpLog.MAX_DEPTH) + "]".repeat(HttpLog.MAX_DEPTH)
        assertEquals(deepest, HttpLog.redactBody(deepest))
        val tooDeep = "[".repeat(HttpLog.MAX_DEPTH + 1) + "]".repeat(HttpLog.MAX_DEPTH + 1)
        assertEquals("\"(${tooDeep.length} bytes, not JSON)\"", HttpLog.redactBody(tooDeep))
        val hostile = "{\"a\":".repeat(200_000) + "1" + "}".repeat(200_000)
        assertEquals("\"(${hostile.length} bytes, not JSON)\"", HttpLog.redactBody(hostile))
    }

    @Test
    fun onlyDebuggableAppsLogHttpDetails() {
        assertTrue(HttpLog.isOn(debuggable = true))
        assertFalse("a release build", HttpLog.isOn(debuggable = false))
        assertFalse("not known yet: counts as a release build", HttpLog.isOn(debuggable = null))
    }

    /** retrieveOrder end to end: the one-line entries stay, and the JSON entries follow them at debug level. */
    @Test
    fun theClientApiLogsEveryRequestAndResponse() = runBlocking {
        Payriff.transportForTests = HttpTransport {
            HttpResponse(200, ORDER_RESPONSE, mapOf("X-Request-Id" to "r-1", "Content-Type" to "application/json"))
        }
        Payriff.configure(PayriffConfig(publishableKey = PUBLISHABLE_KEY, environment = PayriffEnvironment.SANDBOX, enableLogging = true))
        val entries = mutableListOf<PayriffLogEntry>()
        PayriffLogger.listener = PayriffLogListener { entries += it }
        logs.lines.clear()

        val order = Payriff.retrieveOrder("o1", CLIENT_SECRET)

        assertEquals("o1", order.orderId)
        val messages = entries.map { it.message }
        assertEquals(messages.toString(), 4, messages.size)
        assertEquals("GET /api/v3/client/orders/o1", messages[0])
        assertEquals(
            """
            HTTP request
            {
              "method": "GET",
              "url": "https://sb-api.payriff.com/api/v3/client/orders/o1",
              "headers": {
                "Accept": "application/json",
                "X-Client-App-Id": "${HostAppRule.PACKAGE_NAME}",
                "X-Client-Secret": "***(${CLIENT_SECRET.length})",
                "X-Public-Key": "pr_pk_live_***(${PUBLISHABLE_KEY.length})"
              },
              "body": null
            }
            """.trimIndent(),
            messages[1],
        )
        assertEquals("HTTP 200 for GET /api/v3/client/orders/o1", messages[2])
        assertEquals(
            """
            HTTP response
            {
              "status": 200,
              "method": "GET",
              "url": "https://sb-api.payriff.com/api/v3/client/orders/o1",
              "headers": {
                "Content-Type": "application/json",
                "X-Request-Id": "r-1"
              },
              "body": {
                "code": "00000",
                "message": "OK",
                "payload": {
                  "orderId": "o1",
                  "amount": 10.5,
                  "currency": "AZN",
                  "status": "CREATED",
                  "clientSecret": "***(18)"
                }
              }
            }
            """.trimIndent(),
            messages[3],
        )
        assertEquals(List(4) { PayriffLogLevel.DEBUG }, entries.map { it.level })
        assertEquals("the same lines reach Logcat", entries.map { it.formatted }, logs.lines)
        for (line in logs.lines) {
            assertFalse(line, PUBLISHABLE_KEY in line || CLIENT_SECRET in line || "pr_cs_SECRET_VALUE" in line)
        }
        // The test report shows the two entries as Logcat gets them.
        println(logs.lines[1] + "\n" + logs.lines[3])
    }

    @Test
    fun releaseBuildsAndAppsOfUnknownDebuggabilityNeverLogHttpDetails() = runBlocking {
        Payriff.transportForTests = HttpTransport { HttpResponse(200, ORDER_RESPONSE, mapOf("X-Request-Id" to "r-2")) }
        Payriff.configure(PayriffConfig(publishableKey = PUBLISHABLE_KEY, environment = PayriffEnvironment.SANDBOX, enableLogging = true))
        for (debuggable in listOf(false, null)) {
            HostApp.setForTests(debuggable = debuggable, packageName = HostAppRule.PACKAGE_NAME)
            logs.lines.clear()
            Payriff.retrieveOrder("o1", CLIENT_SECRET)
            assertEquals("$debuggable", 2, logs.lines.size)
            assertEquals("[Payriff] [DEBUG] GET /api/v3/client/orders/o1", logs.lines[0])
            assertEquals("[Payriff] [DEBUG] HTTP 200 for GET /api/v3/client/orders/o1", logs.lines[1])
            assertFalse(logs.lines.any { "HTTP request" in it || "HTTP response" in it || "r-2" in it })
        }
        HostApp.setForTests(debuggable = true, packageName = HostAppRule.PACKAGE_NAME)
        Payriff.configure(PayriffConfig(publishableKey = PUBLISHABLE_KEY, environment = PayriffEnvironment.SANDBOX))
        logs.lines.clear()
        Payriff.retrieveOrder("o1", CLIENT_SECRET)
        assertTrue("logging off logs nothing", logs.lines.isEmpty())
    }

    /** The card flow: every attempt is logged, the secrets never are, and logging leaves the request bytes alone. */
    @Test
    fun theClientApiLogsEveryAttemptWithoutSecrets() = runTest {
        Payriff.configure(PayriffConfig(enableLogging = true))
        val transport = FakeTransport { testScheduler.currentTime }
        transport.on(
            "POST",
            "/api/v3/client/orders/o1/pay",
            http(429, """{"code":"14290","message":"Too many requests"}"""),
            http(200, ok("""{"nextAction":"THREE_DS","accessUrl":"https://acs.bank.example/c?token=QUERY_SECRET","clientRequest":[{"key":"creq","value":"CREQ_SECRET"}]}""")),
        )
        val ciphertext = "Q0lQSEVSVEVYVF9PRl9USEVfQ0FSRA=="
        val card = EncryptedCard(ciphertext.toByteArray(Charsets.US_ASCII), "WRAPPED+KEY/==")
        val expectedBody = CardApiClient.payBody(card)

        CardApiClient(credentials(), transport).pay("o1", card, "5b1f2c3d-0000-4000-8000-000000000001")

        assertEquals(2, transport.requests.size)
        for (sent in transport.requests) assertArrayEquals("logging never changes the bytes sent", expectedBody, sent.body)
        val requests = logs.lines.filter { it.startsWith("[Payriff] [DEBUG] HTTP request\n") }
        val responses = logs.lines.filter { it.startsWith("[Payriff] [DEBUG] HTTP response\n") }
        assertEquals("one per attempt", 2, requests.size)
        assertEquals(2, responses.size)
        assertEquals(
            """
            [Payriff] [DEBUG] HTTP request
            {
              "method": "POST",
              "url": "https://api.payriff.com/api/v3/client/orders/o1/pay",
              "headers": {
                "Accept": "application/json",
                "Content-Type": "application/json",
                "X-Client-App-Id": "$APP_ID",
                "X-Client-Secret": "***(${CLIENT_SECRET.length})",
                "X-Idempotency-Key": "5b1f2c3d-0000-4000-8000-000000000001",
                "X-Public-Key": "pr_pk_live_***(${PUBLISHABLE_KEY.length})",
                "x-secret-key": "***(14)"
              },
              "body": {
                "method": "CARD",
                "encryptedMessage": "***(${ciphertext.length})"
              }
            }
            """.trimIndent(),
            requests[0],
        )
        assertEquals(requests[0], requests[1])
        assertTrue(responses[0], responses[0].contains("\"status\": 429,"))
        assertEquals(
            """
            [Payriff] [DEBUG] HTTP response
            {
              "status": 200,
              "method": "POST",
              "url": "https://api.payriff.com/api/v3/client/orders/o1/pay",
              "headers": {},
              "body": {
                "code": "00000",
                "message": "OK",
                "route": "/api/v3/client/orders",
                "responseId": "r-1",
                "payload": {
                  "nextAction": "THREE_DS",
                  "accessUrl": "https://acs.bank.example/c",
                  "clientRequest": [
                    {
                      "key": "creq",
                      "value": "***(11)"
                    }
                  ]
                }
              }
            }
            """.trimIndent(),
            responses[1],
        )
        val secrets = listOf(ciphertext, "WRAPPED+KEY/==", CLIENT_SECRET, PUBLISHABLE_KEY, "CREQ_SECRET", "QUERY_SECRET")
        for (line in logs.lines) for (secret in secrets) assertFalse("log leaks $secret: $line", secret in line)
    }

    /** The real transport hands the response headers to the log. */
    @Test
    fun responseHeadersComeFromTheConnection() = runBlocking {
        FakeHttpServer().use { server ->
            server.reply(200, ORDER_RESPONSE)
            val direct = UrlConnectionTransport().execute(HttpRequest("GET", server.baseUrl + "/x", emptyMap(), null, 2_000))
            assertEquals(mapOf("Connection" to "close", "Content-Length" to "${ORDER_RESPONSE.length}", "Content-Type" to "application/json; charset=utf-8"), direct.headers)

            Payriff.configure(PayriffConfig(enableLogging = true))
            logs.lines.clear()
            CardApiClient(credentials(host = server.baseUrl), UrlConnectionTransport()).order("o1")
            val response = logs.lines.single { it.startsWith("[Payriff] [DEBUG] HTTP response\n") }
            val headers = "\n  \"headers\": {\n" +
                "    \"Connection\": \"close\",\n" +
                "    \"Content-Length\": \"${ORDER_RESPONSE.length}\",\n" +
                "    \"Content-Type\": \"application/json; charset=utf-8\"\n" +
                "  },\n"
            assertTrue(response, response.contains(headers))
            assertFalse(logs.lines.any { CLIENT_SECRET in it || PUBLISHABLE_KEY in it })
            assertTrue(logs.lines.any { "\"X-Client-Secret\": \"***(${CLIENT_SECRET.length})\"" in it })
        }
    }

    @Test
    fun logcatGetsLongEntriesInPiecesThatEndAtLineBreaks() {
        assertEquals(listOf("short"), PayriffLog.logcatPieces("short"))
        val lines = listOf("a".repeat(3_000), "b".repeat(3_000), "c".repeat(3_000))
        assertEquals(lines, PayriffLog.logcatPieces(lines.joinToString("\n")))
        val oneLine = "x".repeat(10_000)
        val pieces = PayriffLog.logcatPieces(oneLine)
        assertEquals(listOf(4_000, 4_000, 2_000), pieces.map { it.length })
        assertEquals(oneLine, pieces.joinToString(""))
        val wide = "\u0259".repeat(3_000)
        assertEquals("2-byte characters count twice", listOf(2_000, 1_000), PayriffLog.logcatPieces(wide).map { it.length })
        val emoji = "\uD83D\uDE00".repeat(1_500)
        val emojiPieces = PayriffLog.logcatPieces(emoji)
        assertEquals(emoji, emojiPieces.joinToString(""))
        assertTrue("no surrogate pair is split", emojiPieces.none { Character.isHighSurrogate(it.last()) || Character.isLowSurrogate(it.first()) })
        val shifted = "x$emoji"
        val shiftedPieces = PayriffLog.logcatPieces(shifted)
        assertEquals(listOf(1_999, 1_002), shiftedPieces.map { it.length })
        assertEquals(shifted, shiftedPieces.joinToString(""))
        assertTrue(shiftedPieces.none { Character.isHighSurrogate(it.last()) || Character.isLowSurrogate(it.first()) })
        assertEquals(listOf("a", "b", "c"), PayriffLog.logcatPieces("abc", maxBytes = 0))
    }

    private companion object {
        const val ORDER_RESPONSE =
            """{"code":"00000","message":"OK","payload":{"orderId":"o1","amount":10.5,"currency":"AZN","status":"CREATED","clientSecret":"pr_cs_SECRET_VALUE"}}"""
    }
}

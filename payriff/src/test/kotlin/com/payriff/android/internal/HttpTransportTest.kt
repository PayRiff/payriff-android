package com.payriff.android.internal

import com.payriff.android.Payriff
import com.payriff.android.PayriffConfig
import com.payriff.android.PayriffError
import com.payriff.android.PayriffErrorCode
import com.payriff.android.card.APP_ID
import com.payriff.android.card.CLIENT_SECRET
import com.payriff.android.card.PUBLISHABLE_KEY
import com.payriff.android.card.credentials
import com.payriff.android.card.ok
import com.payriff.android.card.orderPayload
import com.payriff.android.internal.card.CardApiClient
import com.payriff.android.internal.card.CardCredentials
import com.payriff.android.testing.FakeHttpServer
import com.payriff.android.testing.HostAppRule
import com.payriff.android.testing.LogCapture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket

/** The HttpURLConnection transport and the Client API client against a local fake server. No real network. */
class HttpTransportTest {

    @get:Rule
    val logs = LogCapture()

    @get:Rule
    val hostApp = HostAppRule()

    private val server = FakeHttpServer()

    @After
    fun tearDown() {
        server.close()
        Payriff.reset()
    }

    private fun client(host: String = server.baseUrl) = CardApiClient(credentials(host = host), UrlConnectionTransport())

    @Test
    fun orderIdIsPercentEncodedAsOnePathSegment() = runBlocking {
        server.reply(200, ok(orderPayload()))
        client().order("a/b c?d#é")
        assertEquals("/api/v3/client/orders/a%2Fb%20c%3Fd%23%C3%A9", server.requests.single().path)
    }

    @Test
    fun aSlowResponseTimesOut() = runBlocking {
        server.reply(200, ok(orderPayload()), delayMillis = 3_000)
        val started = System.nanoTime()
        try {
            UrlConnectionTransport().execute(HttpRequest("GET", server.baseUrl + "/x", emptyMap(), null, 300))
            fail("expected a timeout")
        } catch (expected: IOException) {
            // SocketTimeoutException: the card client reports it as NETWORK_ERROR.
        }
        val tookMillis = (System.nanoTime() - started) / 1_000_000
        assertTrue("timed out promptly ($tookMillis ms)", tookMillis < 2_000)
    }

    @Test
    fun cancellingTheCallerAbortsTheRequest() = runBlocking {
        server.reply(200, ok(orderPayload()), delayMillis = 5_000)
        val started = System.nanoTime()
        val result = withTimeoutOrNull(300) { client().order("o1") }
        assertNull(result)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000)
    }

    @Test
    fun anUnreachableHostIsANetworkError() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        try {
            client(host = "http://127.0.0.1:$port").order("o1")
            fail("expected a network error")
        } catch (error: PayriffError) {
            assertEquals(PayriffErrorCode.NETWORK_ERROR, error.code)
            assertTrue(error.isRetryable)
            assertNull(error.httpStatus)
        }
    }

    @Test
    fun aServerErrorIsNotRetried() = runBlocking {
        server.reply(500, """{"code":"15000","message":"Internal error"}""")
        val error = runCatching { client().order("o1") }.exceptionOrNull() as PayriffError
        assertEquals(PayriffErrorCode.PAYMENT_FAILED, error.code)
        assertEquals(500, error.httpStatus)
        assertEquals("Internal error", error.message)
        delay(100)
        assertEquals(1, server.requests.size)
    }

    @Test
    fun theKeysAreNeverLogged() = runBlocking {
        Payriff.configure(PayriffConfig(publishableKey = PUBLISHABLE_KEY, enableLogging = true))
        server.reply(401, """{"code":"14010","message":"Unauthorized\n Support ticket: 1"}""")
        val error = runCatching { client().order("o1") }.exceptionOrNull() as PayriffError
        assertEquals(PayriffErrorCode.UNAUTHORIZED, error.code)
        assertTrue(error.isFatal)
        assertTrue(logs.lines.isNotEmpty())
        assertFalse(logs.lines.any { PUBLISHABLE_KEY in it || CLIENT_SECRET in it })
        assertTrue(logs.lines.any { "\"X-Client-Secret\": \"***(${CLIENT_SECRET.length})\"" in it })
        assertTrue(logs.lines.all { it.startsWith("[Payriff] [") })
    }

    /** The HTTP stack's own exception quotes the rejected header value; it must never escape (CONTRACT §11). */
    @Test
    fun aKeyTheHttpStackRejectsNeverLeaksIntoTheError() = runBlocking {
        val broken = CardCredentials(server.baseUrl, "pr_pk_live_SECRET\nVALUE", CLIENT_SECRET, APP_ID)
        val error = runCatching { CardApiClient(broken, UrlConnectionTransport()).order("o1") }.exceptionOrNull() as PayriffError
        assertEquals(PayriffErrorCode.UNKNOWN, error.code)
        assertFalse(error.message, error.message.contains("SECRET"))
        assertNull("the cause quotes the key", error.cause)
        assertTrue("nothing was sent", server.requests.isEmpty())
    }

    @Test
    fun concurrentRequestsDoNotInterfere() = runBlocking {
        server.handler = { request ->
            val id = request.path.substringAfterLast('/')
            FakeHttpServer.Reply(200, ok("""{"orderId":"$id","status":"CREATED","paymentMethods":["CARD"]}"""))
        }
        val client = client()
        val ids = (1..8).map { "id$it" }
        val orders = ids.map { id -> async(Dispatchers.IO) { client.order(id) } }.map { it.await() }
        assertEquals(ids, orders.map { it.orderId })
    }
}

package com.payriff.android.card

import com.payriff.android.PayriffConfig
import com.payriff.android.PayriffEnvironment
import com.payriff.android.PayriffError
import com.payriff.android.PayriffErrorCode
import com.payriff.android.PayriffRegion
import com.payriff.android.PayriffResult
import com.payriff.android.internal.UrlConnectionTransport
import com.payriff.android.internal.card.CardApiClient
import com.payriff.android.internal.card.CardApiErrors
import com.payriff.android.internal.card.CardKeys
import com.payriff.android.internal.card.CardResults
import com.payriff.android.internal.card.CardSetup
import com.payriff.android.internal.card.ClientApiHosts
import com.payriff.android.internal.card.EncryptedCard
import com.payriff.android.testing.FakeHttpServer
import com.payriff.android.testing.Vectors
import com.payriff.android.testing.Vectors.objects
import com.payriff.android.testing.Vectors.stringOrNull
import com.payriff.android.testing.Vectors.toStringMap
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigDecimal
import java.util.Locale

/** `card_api.json` (CONTRACT §16.2–§16.3, §16.9): every case, through the real client over a fake transport. */
@OptIn(ExperimentalCoroutinesApi::class)
class CardApiVectorsTest {

    private val vectors = Vectors.load("card_api.json")

    private fun TestScope.client(transport: FakeTransport) = CardApiClient(credentials(), transport)

    private fun TestScope.transport() = FakeTransport { testScheduler.currentTime }

    private suspend fun expectError(block: suspend () -> Unit): PayriffError {
        try {
            block()
        } catch (e: PayriffError) {
            return e
        }
        fail("expected a PayriffError")
        throw AssertionError()
    }

    @Test
    fun order() = runTest {
        val cases = vectors.getJSONArray("order").objects()
        assertEquals(3, cases.size)
        for (case in cases) {
            val transport = transport()
            transport.on("GET", "/api/v3/client/orders/o1", http(case.getInt("httpStatus"), case.getString("body")))
            val order = client(transport).order("o1")
            val expect = case.getJSONObject("expect")
            assertTrue(expect.getBoolean("ok"))
            assertEquals(expect.getString("orderId"), order.orderId)
            assertEquals(expect.getString("status"), order.rawStatus)
            assertEquals(case.getString("name"), expect.getBoolean("payable"), order.isPayableByCard)
            if (expect.has("amount")) assertEquals(0, BigDecimal(expect.getString("amount")).compareTo(order.amount))
            if (expect.has("currency")) assertEquals(expect.getString("currency"), order.currency)
            if (expect.has("paymentMethods")) {
                val methods = expect.getJSONArray("paymentMethods")
                assertEquals((0 until methods.length()).map(methods::getString), order.paymentMethods)
            }
        }
    }

    @Test
    fun errors() = runTest {
        val cases = vectors.getJSONArray("errors").objects()
        assertEquals(10, cases.size)
        for (case in cases) {
            val name = case.getString("name")
            val status = case.getInt("httpStatus")
            val expect = case.getJSONObject("expect")
            val transport = transport()
            transport.on("GET", "/api/v3/client/orders/o1", http(status, case.getString("body")))
            val started = testScheduler.currentTime
            val error = expectError { client(transport).order("o1") }
            assertEquals(name, expect.getString("code"), error.code.name)
            assertEquals(name, expect.getBoolean("fatal"), error.isFatal)
            assertEquals(name, expect.getBoolean("retry"), CardApiErrors.isRetried(status))
            assertEquals(name, status, error.httpStatus)
            if (expect.has("payriffCode")) assertEquals(name, expect.stringOrNull("payriffCode"), error.payriffCode)
            if (expect.has("message")) assertEquals(name, expect.getString("message"), error.message)
            assertEquals(name, error.code == PayriffErrorCode.PAYMENT_FAILED, error.isRetryable)
            if (expect.getBoolean("retry")) {
                // 3 retries with 1 s, 2 s and 4 s backoff.
                assertEquals(name, listOf(0L, 1_000L, 3_000L, 7_000L), transport.requests.map { it.time - started })
            } else {
                assertEquals(name, 1, transport.requests.size)
            }
        }
    }

    @Test
    fun status() = runTest {
        val cases = vectors.getJSONArray("status").objects()
        assertEquals(8, cases.size)
        for (case in cases) {
            val transport = transport()
            transport.on("GET", "/api/v3/client/orders/o1/status", http(case.getInt("httpStatus"), case.getString("body")))
            val status = client(transport).status("o1")
            val expect = case.getJSONObject("expect")
            assertEquals(expect.getString("status"), status.rawStatus)
            assertEquals(case.getString("name"), expect.getBoolean("final"), status.isFinal)
            val type = when (CardResults.finalResult("o1", status)) {
                null -> null
                is PayriffResult.Success -> "success"
                is PayriffResult.Declined -> "declined"
                is PayriffResult.Cancelled -> "cancelled"
                is PayriffResult.Pending -> "pending"
                is PayriffResult.Failed -> "failed"
            }
            assertEquals(case.getString("name"), expect.stringOrNull("result"), type)
        }
    }

    @Test
    fun requests() = runTest {
        val cases = vectors.getJSONArray("requests").objects()
        assertEquals(5, cases.size)
        val transport = transport()
        transport.on("GET", "/api/v3/client/orders/o1", http(200, ok(orderPayload())))
        transport.on("POST", "/api/v3/client/orders/o1/pay", http(200, ok("""{"nextAction":"CHECK_STATUS"}""")))
        transport.on("GET", "/api/v3/client/orders/o1/three-ds", http(200, ok("""{"nextAction":"WAIT"}""")))
        transport.on("GET", "/api/v3/client/orders/o1/status", http(200, ok(statusPayload("PENDING"))))
        transport.on("GET", "/api/v3/client/orders/a%2Fb", http(200, ok(orderPayload())))
        val api = client(transport)
        api.order("o1")
        api.pay("o1", EncryptedCard("QUJD".toByteArray(), "WRAPPED"), "idem-1")
        api.threeDs("o1")
        api.status("o1")
        api.order("a/b")
        for ((case, request) in cases.zip(transport.requests)) {
            val name = case.getString("name")
            assertEquals(name, case.getString("path"), request.path)
            case.optString("method").takeIf { it.isNotEmpty() }?.let { assertEquals(name, it, request.method) }
            case.optJSONObject("headers")?.toStringMap()?.forEach { (header, value) -> assertEquals("$name $header", value, request.headers[header]) }
            val absent = case.optJSONArray("absentHeaders")
            if (absent != null) {
                val sent = request.headers.keys.map { it.lowercase(Locale.ROOT) }
                for (i in 0 until absent.length()) assertFalse("$name sends ${absent.getString(i)}", absent.getString(i).lowercase(Locale.ROOT) in sent)
            }
            val required = case.optJSONArray("requiredHeaders")
            if (required != null) for (i in 0 until required.length()) assertNotNull("$name ${required.getString(i)}", request.headers[required.getString(i)])
            case.optJSONObject("body")?.let { shape ->
                val body = JSONObject(checkNotNull(request.bodyText))
                assertEquals(shape.getString("method"), body.getString("method"))
                assertEquals("QUJD", body.getString("encryptedMessage"))
                assertEquals(setOf("method", "encryptedMessage"), body.keys().asSequence().toSet())
            }
        }
        val pay = transport.requests[1]
        assertEquals("WRAPPED", pay.headers["x-secret-key"])
        assertEquals("idem-1", pay.headers["X-Idempotency-Key"])
        assertEquals("""{"method":"CARD","encryptedMessage":"QUJD"}""", pay.bodyText)
    }

    @Test
    fun theAppIdHeaderIsOmittedWhenUnknown() = runTest {
        val transport = transport()
        transport.on("GET", "/api/v3/client/orders/o1", http(200, ok(orderPayload())))
        CardApiClient(credentials(appId = null), transport).order("o1")
        assertFalse(transport.requests.single().headers.containsKey("X-Client-App-Id"))
        assertNull(CardKeys.appId(null))
        assertNull(CardKeys.appId("not a package"))
        assertEquals("com.payriff.example.android", CardKeys.appId("com.payriff.example.android"))
    }

    @Test
    fun headersOverARealConnection() {
        FakeHttpServer().use { server ->
            server.reply(200, ok(statusPayload("APPROVED")))
            val api = CardApiClient(credentials(host = server.baseUrl), UrlConnectionTransport())
            val status = kotlinx.coroutines.runBlocking { api.status(" o1 ") }
            assertEquals("APPROVED", status.rawStatus)
            val request = server.requests.single()
            assertEquals("/api/v3/client/orders/o1/status", request.path)
            val headers = request.headers.mapKeys { it.key.lowercase(Locale.ROOT) }
            assertEquals(PUBLISHABLE_KEY, headers["x-public-key"])
            assertEquals(CLIENT_SECRET, headers["x-client-secret"])
            assertEquals(APP_ID, headers["x-client-app-id"])
            assertEquals("application/json", headers["accept"])
            for (absent in listOf("origin", "x-parent-origin", "authorization")) assertFalse(absent, absent in headers)
        }
    }

    @Test
    fun keys() {
        val cases = vectors.getJSONArray("keys").objects()
        assertEquals(4, cases.size)
        for (case in cases) {
            val value = case.getString("value")
            val valid = case.getBoolean("valid")
            assertEquals(case.getString("name"), valid, CardKeys.isValidPublishableKey(value))
            val config = PayriffConfig(publishableKey = value)
            try {
                val credentials = CardSetup.credentials(config, CLIENT_SECRET, packageName = APP_ID)
                assertTrue(case.getString("name"), valid)
                assertEquals(value, credentials.publishableKey)
            } catch (e: PayriffError) {
                assertFalse(case.getString("name"), valid)
                assertEquals(PayriffErrorCode.INVALID_CONFIG, e.code)
                if (value.isNotEmpty()) assertFalse(e.message.contains(value))
            }
        }
        val error = try {
            CardSetup.credentials(null, CLIENT_SECRET)
            null
        } catch (e: PayriffError) {
            e
        }
        assertEquals(PayriffErrorCode.NOT_CONFIGURED, error?.code)
    }

    @Test
    fun clientSecrets() {
        val cases = vectors.getJSONArray("clientSecrets").objects()
        assertEquals(4, cases.size)
        for (case in cases) {
            val value = case.getString("value")
            val sent = CardKeys.clientSecretToSend(value)
            assertEquals(case.getString("name"), case.getBoolean("valid"), sent != null)
            if (case.getBoolean("valid")) assertEquals(case.optString("sent", value), sent)
        }
        assertNull(CardKeys.clientSecretToSend("pr_cs_a\r\nX-Evil: 1"))
        val error = try {
            CardSetup.credentials(PayriffConfig(publishableKey = PUBLISHABLE_KEY), "cs_abc", APP_ID)
            null
        } catch (e: PayriffError) {
            e
        }
        assertEquals(PayriffErrorCode.INVALID_ORDER, error?.code)
    }

    /** `hosts`: the API base URL and the configure warning of every region and environment (CONTRACT §16.2). */
    @Test
    fun hosts() {
        val hosts = vectors.getJSONObject("hosts")
        assertEquals(hosts.getString("defaultRegion"), PayriffConfig().region.name.lowercase(Locale.ROOT))
        val cases = hosts.getJSONArray("cases").objects()
        val combinations = cases.map { case ->
            val region = PayriffRegion.valueOf(case.getString("region").uppercase(Locale.ROOT))
            val environment = PayriffEnvironment.valueOf(case.getString("environment").uppercase(Locale.ROOT))
            val name = "$region $environment"
            val config = PayriffConfig(publishableKey = PUBLISHABLE_KEY, environment = environment, region = region)
            assertEquals(name, case.getString("apiBaseUrl"), config.apiBaseUrl)
            assertEquals(name, case.getString("apiBaseUrl"), ClientApiHosts.of(region, environment))
            assertEquals(name, case.getString("apiBaseUrl"), CardSetup.credentials(config, CLIENT_SECRET, APP_ID).host)
            assertEquals(name, case.stringOrNull("warning"), ClientApiHosts.warning(region, environment))
            region to environment
        }
        assertEquals(
            "every region and environment has a case",
            PayriffRegion.entries.flatMap { region -> PayriffEnvironment.entries.map { region to it } }.toSet(),
            combinations.toSet(),
        )
        assertEquals("/api/v3/client/orders/a%2Fb", CardApiClient.orderPath(" a/b "))
        assertEquals("/api/v3/client/orders/%2E%2E", CardApiClient.orderPath(".."))
    }
}

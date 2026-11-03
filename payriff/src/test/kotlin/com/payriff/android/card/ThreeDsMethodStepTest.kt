package com.payriff.android.card

import com.payriff.android.PayriffResult
import com.payriff.android.internal.card.CardApiClient
import com.payriff.android.internal.card.CardTimings
import com.payriff.android.internal.card.NextAction
import com.payriff.android.internal.card.ThreeDsFlow
import com.payriff.android.testing.LogCapture
import com.payriff.android.testing.Vectors
import com.payriff.android.testing.Vectors.keyNames
import com.payriff.android.testing.Vectors.objects
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The 3-D Secure Method step of CONTRACT §19.1 (Payriff.js 1.3.1), `card_next_action.json` → `methodSteps`,
 * `threeDsTiming` and `afterMethod`, with a fake transport and virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreeDsMethodStepTest {

    @get:Rule
    val logs = LogCapture()

    private val vector = Vectors.load("card_next_action.json")
    private val scopes = mutableListOf<CoroutineScope>()
    private val statusPath = "/api/v3/client/orders/o1/status"
    private val threeDsPath = "/api/v3/client/orders/o1/three-ds"

    @After
    fun cancelScopes() = scopes.forEach { it.cancel() }

    private class Harness(val flow: ThreeDsFlow, val ui: FakeThreeDsUi, val transport: FakeTransport)

    private fun TestScope.harness(): Harness {
        val transport = FakeTransport { testScheduler.currentTime }
        val ui = FakeThreeDsUi()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()).also { scopes += it }
        val flow = ThreeDsFlow(scope, ui, CardApiClient(credentials(), transport), "o1", HOST, { testScheduler.currentTime })
        return Harness(flow, ui, transport)
    }

    @Test
    fun methodStepsStartTheVectorsWebViews() = runTest {
        val cases = vector.getJSONArray("methodSteps").objects()
        assertEquals(4, cases.size)
        for (case in cases) {
            val name = case.getString("name")
            val request = case.getJSONObject("clientRequest")
            val expect = case.getJSONObject("expect")
            val action = NextAction.parse(threeDsPayload(request)) as NextAction.Method
            assertEquals(name, expect.getBoolean("issuer"), action.runsIssuer)
            assertEquals(name, expect.getBoolean("server"), action.runsServer)

            val h = harness()
            h.transport.on("GET", threeDsPath, http(200, ok("""{"nextAction":"CHECK_STATUS"}""")))
            h.transport.on("GET", statusPath, http(200, ok(statusPayload("APPROVED"))))
            h.flow.start(action)
            val issuer = request.optString("threeDSMethodUrl").takeIf { expect.getBoolean("issuer") }
            val server = request.optString("threeDSMethodUrlServer").takeIf { expect.getBoolean("server") }
            if (issuer == null && server == null) {
                // Nothing started: straight to /three-ds.
                assertTrue(name, h.ui.methods.isEmpty())
                runCurrent()
                assertEquals(name, listOf(0L), h.transport.requestsTo(threeDsPath).map { it.time })
            } else {
                // Back to back, in one step: the issuer's POST carries threeDSMethodData, the server's is empty.
                val body = issuer?.let { "threeDSMethodData=" + request.getString("threeDSMethodData") }
                assertEquals(name, listOf(Triple(issuer, body, server)), h.ui.methods)
                repeat(listOfNotNull(issuer, server).size) { h.flow.onMethodPageLoaded() }
                runCurrent()
                assertEquals(name, listOf(0L), h.transport.requestsTo(threeDsPath).map { it.time })
            }
            advanceUntilIdle()
            assertTrue(name, h.ui.result is PayriffResult.Success)
        }
    }

    @Test
    fun timingsAreTheVectors() = runTest {
        val timing = vector.getJSONObject("threeDsTiming")
        assertEquals(
            setOf("methodLoadTimeoutSeconds", "threeDsRequestTimeoutSeconds", "threeDsRetries", "waitIntervalSeconds", "waitLimitSeconds"),
            timing.keyNames(),
        )
        val defaults = CardTimings()
        assertEquals(timing.getLong("methodLoadTimeoutSeconds") * 1_000, defaults.methodLoadTimeoutMillis)
        assertEquals(timing.getLong("threeDsRequestTimeoutSeconds") * 1_000, CardApiClient.THREE_DS_TIMEOUT_MILLIS)
        assertEquals(timing.getLong("waitIntervalSeconds") * 1_000, defaults.waitIntervalMillis)
        assertEquals(timing.getLong("waitLimitSeconds") * 1_000, defaults.waitLimitMillis)
        assertEquals(1, timing.getInt("threeDsRetries"))

        // The pages get at most 10 s: a page that never loads does not hold the step longer.
        val slow = harness()
        slow.transport.on("GET", threeDsPath, http(200, ok("""{"nextAction":"CHECK_STATUS"}""")))
        slow.transport.on("GET", statusPath, http(200, ok(statusPayload("APPROVED"))))
        slow.flow.start(NextAction.Method(null, null, "https://3ds.bank.example/gather"))
        advanceTimeBy(9_999)
        assertTrue(slow.transport.requestsTo(threeDsPath).isEmpty())
        // The hidden WebViews are alive while the step waits.
        assertFalse("destroyHidden" in slow.ui.events)
        advanceUntilIdle()
        assertEquals(listOf(10_000L), slow.transport.requestsTo(threeDsPath).map { it.time })
        assertTrue(slow.ui.result is PayriffResult.Success)

        // /three-ds waits up to 60 s for the bank, and a request without an answer is retried once, 1 s later.
        val hanging = harness()
        hanging.transport.on("GET", threeDsPath, FakeTransport.Reply.Hang)
        hanging.transport.on("GET", statusPath, http(200, ok(statusPayload("APPROVED"))))
        val start = testScheduler.currentTime
        hanging.flow.start(NextAction.Method(null, null, null))
        advanceUntilIdle()
        assertEquals(listOf(0L, 61_000L), hanging.transport.requestsTo(threeDsPath).map { it.time - start })
        assertEquals("then status polling", listOf(121_000L), hanging.transport.requestsTo(statusPath).map { it.time - start })

        // A slow bank (35 s) is waited for, without a retry.
        val bank = harness()
        bank.transport.on(
            "GET",
            threeDsPath,
            http(200, ok("""{"nextAction":"THREE_DS","accessUrl":"https://acs.bank.example/c","clientRequest":[]}"""), delayMillis = 35_000),
        )
        bank.flow.start(NextAction.Method(null, null, null))
        advanceUntilIdle()
        assertEquals(1, bank.transport.requestsTo(threeDsPath).size)
        assertEquals(listOf("https://acs.bank.example/c" to ""), bank.ui.challenges)
    }

    @Test
    fun afterTheMethodEveryResultGoesWhereTheVectorSays() = runTest {
        val rows = vector.getJSONArray("afterMethod").objects()
        assertEquals(8, rows.size)
        for (row in rows) {
            val result = row.getString("result")
            val h = harness()
            when (result) {
                "challenge" -> h.answer(http(200, ok("""{"nextAction":"THREE_DS","accessUrl":"https://acs.bank.example/c","clientRequest":[{"key":"creq","value":"x"}]}""")))
                "redirect" -> h.answer(http(200, ok("""{"nextAction":"REDIRECT","redirectUrl":"https://bank.example/frictionless"}""")))
                "challengeWithoutAccessUrl" -> h.answer(http(200, ok("""{"nextAction":"THREE_DS","clientRequest":[{"key":"creq","value":"x"}]}""")))
                "method" -> h.answer(
                    http(
                        200,
                        ok("""{"nextAction":"THREE_DS","clientRequest":[{"key":"threeDSMethodUrl","value":"https://acs.bank.example/m"},{"key":"threeDSMethodData","value":"d"}]}"""),
                    ),
                )
                "checkStatus" -> h.answer(http(200, ok("""{"nextAction":"CHECK_STATUS"}""")))
                "waitAfterLimit" -> h.answer(http(200, ok("""{"nextAction":"WAIT"}""")))
                "invalid" -> h.answer(http(200, ok("""{"nextAction":"SOMETHING_NEW"}""")))
                "errorAfterRetry" -> h.answer(http(503, """{"code":"15000","message":"Unavailable"}"""))
                else -> error("unknown result $result")
            }
            // A status that is not final yet, then a final one: polling goes on to it, a single check would not.
            h.transport.on("GET", statusPath, http(200, ok(statusPayload("PENDING"))), http(200, ok(statusPayload("APPROVED"))))
            h.flow.start(NextAction.Method(null, null, null))
            advanceUntilIdle()
            when (row.getString("then")) {
                "challenge" -> {
                    assertEquals(result, listOf("https://acs.bank.example/c" to "creq=x"), h.ui.challenges)
                    assertNull(result, h.ui.result)
                }
                "redirect" -> {
                    assertEquals(result, listOf("https://bank.example/frictionless"), h.ui.redirects)
                    assertNull(result, h.ui.result)
                }
                "statusPolling" -> {
                    assertTrue(result, h.ui.challenges.isEmpty() && h.ui.redirects.isEmpty() && h.ui.methods.isEmpty())
                    assertTrue(result, "verifying" in h.ui.events)
                    assertEquals(result, 2, h.transport.requestsTo(statusPath).size)
                    assertTrue("$result -> ${h.ui.result}", h.ui.result is PayriffResult.Success)
                }
                else -> error("unknown step in $row")
            }
            // Step 6: the hidden WebViews are destroyed when the step hands over.
            assertTrue(result, "destroyHidden" in h.ui.events)
        }
    }

    @Test
    fun aPageThatLoadsLateOrTwiceChangesNothing() = runTest {
        val h = harness()
        h.transport.on("GET", threeDsPath, http(200, ok("""{"nextAction":"CHECK_STATUS"}""")))
        h.transport.on("GET", statusPath, http(200, ok(statusPayload("APPROVED"))))
        h.flow.start(NextAction.Method("https://acs.bank.example/m", "d", null))
        h.flow.onMethodPageLoaded()
        runCurrent()
        assertEquals(1, h.transport.requestsTo(threeDsPath).size)
        h.flow.onMethodPageLoaded()
        advanceUntilIdle()
        assertEquals(1, h.transport.requestsTo(threeDsPath).size)
        assertTrue(h.ui.result is PayriffResult.Success)
    }

    private fun Harness.answer(reply: FakeTransport.Reply) = transport.on("GET", threeDsPath, reply)

    /** A `THREE_DS` payload whose `clientRequest` holds the vector's object as key/value entries. */
    private fun threeDsPayload(request: JSONObject): JSONObject {
        val entries = JSONArray()
        for (key in request.keyNames()) entries.put(JSONObject().put("key", key).put("value", request.opt(key)))
        return JSONObject().put("nextAction", "THREE_DS").put("clientRequest", entries)
    }
}

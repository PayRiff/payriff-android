package com.payriff.android.card

import com.payriff.android.PayriffCancelReason
import com.payriff.android.PayriffErrorCode
import com.payriff.android.PayriffPaymentStatus
import com.payriff.android.PayriffPendingReason
import com.payriff.android.PayriffResult
import com.payriff.android.internal.card.CardApiClient
import com.payriff.android.internal.card.NextAction
import com.payriff.android.internal.card.ThreeDsFlow
import com.payriff.android.internal.card.ThreeDsNav
import com.payriff.android.testing.LogCapture
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.math.BigDecimal

/** The 3DS sheet's state machine (CONTRACT §16.7 step 4–5, §16.8) with a fake transport and virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreeDsFlowTest {

    @get:Rule
    val logs = LogCapture()

    private val scopes = mutableListOf<CoroutineScope>()
    private val statusPath = "/api/v3/client/orders/o1/status"
    private val threeDsPath = "/api/v3/client/orders/o1/three-ds"
    private val callback = "https://api.payriff.com/api/v3/gateway/kapital/callback/ORD-1"

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

    private fun challenge() = NextAction.Challenge("https://acs.bank.example/challenge", listOf("creq" to "a b+c", "threeDSSessionData" to ""))

    @Test
    fun challengeCallbackThenNextPageEndsWithTheStatus() = runTest {
        val h = harness()
        h.transport.on("GET", statusPath, http(200, ok(statusPayload("PENDING"))), http(200, ok(statusPayload("APPROVED"))))
        h.flow.start(challenge())
        assertEquals(listOf("https://acs.bank.example/challenge" to "creq=a+b%2Bc&threeDSSessionData="), h.ui.challenges)
        assertEquals("loader", h.ui.events.first())
        h.flow.onPageVisible()
        assertEquals(ThreeDsNav.ALLOW, h.flow.onNavigation("https://acs.bank.example/otp", isMainFrame = true))
        assertTrue(h.transport.requests.isEmpty())
        assertEquals(ThreeDsNav.ALLOW_CALLBACK, h.flow.onNavigation(callback, isMainFrame = true))
        runCurrent()
        // Status polling starts in parallel once the callback is seen; the page stays visible.
        assertEquals(1, h.transport.requestsTo(statusPath).size)
        assertTrue(h.ui.closeButtonEnabled)
        assertEquals(ThreeDsNav.FINISH, h.flow.onNavigation("https://merchant.example/return?status=success", isMainFrame = true))
        assertTrue("stop" in h.ui.events && "verifying" in h.ui.events)
        assertFalse(h.ui.closeButtonEnabled)
        advanceUntilIdle()
        val result = h.ui.result as PayriffResult.Success
        assertEquals(1, h.ui.finishCount)
        assertEquals(PayriffPaymentStatus.APPROVED, result.payment.status)
        assertEquals(0, BigDecimal("10.5").compareTo(result.payment.amount))
        assertEquals("AZN", result.payment.currency)
        assertNull(result.payment.transactionId)
        assertEquals(listOf(0L, 2_500L), h.transport.requestsTo(statusPath).map { it.time })
        // Anything after the end is blocked.
        assertEquals(ThreeDsNav.BLOCK, h.flow.onNavigation("https://merchant.example/again", isMainFrame = true))
    }

    @Test
    fun aFinalStatusClosesTheSheetWithoutAnotherNavigation() = runTest {
        val h = harness()
        h.transport.on("GET", statusPath, http(200, ok(statusPayload("DECLINED"))))
        h.flow.start(NextAction.Redirect("https://bank.example/frictionless"))
        assertEquals(listOf("https://bank.example/frictionless"), h.ui.redirects)
        h.flow.onCallbackObserved()
        advanceUntilIdle()
        assertTrue(h.ui.result is PayriffResult.Declined)
    }

    @Test
    fun statusPollingTimesOutAsPendingNotFinal() = runTest {
        val h = harness()
        h.transport.on("GET", statusPath, http(200, ok(statusPayload("PENDING"))))
        h.flow.start(NextAction.CheckStatus)
        advanceUntilIdle()
        val result = h.ui.result as PayriffResult.Pending
        assertEquals(PayriffPendingReason.NOT_FINAL, result.reason)
        assertEquals(PayriffPaymentStatus.PENDING, result.payment.status)
        val times = h.transport.requestsTo(statusPath).map { it.time }
        assertEquals((0..48).map { it * 2_500L }, times)
    }

    @Test
    fun pollingContinuesOnNetworkErrorsAndStopsOnOtherErrors() = runTest {
        val h = harness()
        h.transport.on("GET", statusPath, FakeTransport.Reply.NoResponse(), http(404, """{"code":"01000","message":"Order not found"}"""))
        h.flow.start(NextAction.CheckStatus)
        advanceUntilIdle()
        val error = (h.ui.result as PayriffResult.Failed).error
        assertEquals(PayriffErrorCode.SESSION_EXPIRED, error.code)
        assertTrue(error.isFatal)
        assertEquals(2, h.transport.requestsTo(statusPath).size)
    }

    @Test
    fun methodThenThreeDsLoopUntilAChallenge() = runTest {
        val h = harness()
        h.transport.on(
            "GET",
            threeDsPath,
            http(200, ok("""{"nextAction":"WAIT"}""")),
            FakeTransport.Reply.NoResponse(),
            http(200, ok("""{"nextAction":"THREE_DS","clientRequest":[]}""")),
            http(200, ok("""{"nextAction":"THREE_DS","accessUrl":"https://acs.bank.example/c","clientRequest":[{"key":"creq","value":"x"}]}""")),
        )
        h.flow.start(NextAction.Method("https://acs.bank.example/method", "eyJ0aHJlZURTU2VydmVyVHJhbnNJRCI6IjEifQ", "https://api.payriff.com/api/v3/gateway/atb/method"))
        assertEquals(
            listOf(Triple("https://acs.bank.example/method", "threeDSMethodData=eyJ0aHJlZURTU2VydmVyVHJhbnNJRCI6IjEifQ", "https://api.payriff.com/api/v3/gateway/atb/method")),
            h.ui.methods,
        )
        assertTrue(h.ui.closeButtonEnabled)
        advanceUntilIdle()
        assertEquals(listOf(1_000L, 2_000L, 3_000L, 4_000L), h.transport.requestsTo(threeDsPath).map { it.time })
        assertEquals(listOf("https://acs.bank.example/c" to "creq=x"), h.ui.challenges)
        val destroyed = h.ui.events.indexOf("destroyHidden")
        assertTrue(destroyed in 0 until h.ui.events.indexOf("challenge"))
        assertNull(h.ui.result)
    }

    @Test
    fun thirtyWaitsGoToStatusPolling() = runTest {
        val h = harness()
        h.transport.on("GET", threeDsPath, http(200, ok("""{"nextAction":"WAIT"}""")))
        h.transport.on("GET", statusPath, http(200, ok(statusPayload("APPROVED"))))
        h.flow.start(NextAction.Wait)
        assertTrue(h.ui.methods.isEmpty())
        advanceUntilIdle()
        assertEquals(30, h.transport.requestsTo(threeDsPath).size)
        assertEquals(listOf(30_000L), h.transport.requestsTo(statusPath).map { it.time })
        assertTrue(h.ui.result is PayriffResult.Success)
    }

    @Test
    fun aNewMethodStepRunsAndTheLoopGoesOn() = runTest {
        val h = harness()
        h.transport.on(
            "GET",
            threeDsPath,
            http(200, ok("""{"nextAction":"THREE_DS","clientRequest":[{"key":"threeDSMethodUrlServer","value":"https://api.payriff.com/api/v3/gateway/atb/method2"}]}""")),
            http(200, ok("""{"nextAction":"CHECK_STATUS"}""")),
        )
        h.transport.on("GET", statusPath, http(200, ok(statusPayload("APPROVED"))))
        h.flow.start(NextAction.Method(null, null, null))
        assertTrue(h.ui.methods.isEmpty())
        advanceUntilIdle()
        assertEquals(listOf(Triple<String?, String?, String?>(null, null, "https://api.payriff.com/api/v3/gateway/atb/method2")), h.ui.methods)
        assertTrue(h.ui.result is PayriffResult.Success)
    }

    @Test
    fun anInvalidStepEndsWithOneStatusCheck() = runTest {
        for ((status, expected) in listOf("PENDING" to "failed", "APPROVED" to "success")) {
            val h = harness()
            h.transport.on("GET", statusPath, http(200, ok(statusPayload(status))))
            h.flow.start(NextAction.Invalid)
            advanceUntilIdle()
            assertEquals(1, h.transport.requests.size)
            when (expected) {
                "failed" -> assertEquals(PayriffErrorCode.UNEXPECTED_RESPONSE, (h.ui.result as PayriffResult.Failed).error.code)
                else -> assertTrue(h.ui.result is PayriffResult.Success)
            }
        }
    }

    @Test
    fun closingChecksOnceAndIsNeverUserClosed() = runTest {
        for ((status, check) in listOf<Pair<String, (PayriffResult?) -> Boolean>>(
            "CREATED" to { it is PayriffResult.Pending && it.reason == PayriffPendingReason.NOT_FINAL },
            "PENDING" to { it is PayriffResult.Pending && it.reason == PayriffPendingReason.NOT_FINAL },
            "CANCELED" to { it is PayriffResult.Cancelled && it.reason == PayriffCancelReason.CANCELED },
            "APPROVED" to { it is PayriffResult.Success },
        )) {
            val h = harness()
            h.transport.on("GET", statusPath, http(200, ok(statusPayload(status))))
            h.flow.start(challenge())
            h.flow.onPageVisible()
            h.flow.onUserClose()
            assertEquals("confirmClose", h.ui.events.last())
            assertTrue(h.transport.requests.isEmpty())
            h.ui.confirm()
            assertFalse(h.ui.closeButtonEnabled)
            advanceUntilIdle()
            assertTrue("$status -> ${h.ui.result}", check(h.ui.result))
            assertEquals(1, h.transport.requestsTo(statusPath).size)
        }
    }

    @Test
    fun aFailedCheckOnCloseIsPendingNotFinal() = runTest {
        val h = harness()
        h.transport.on("GET", statusPath, FakeTransport.Reply.NoResponse())
        h.flow.start(NextAction.Wait)
        h.transport.on("GET", threeDsPath, http(200, ok("""{"nextAction":"WAIT"}""")))
        h.flow.onUserClose()
        h.ui.confirm()
        advanceUntilIdle()
        val result = h.ui.result as PayriffResult.Pending
        assertEquals(PayriffPendingReason.NOT_FINAL, result.reason)
        assertEquals(PayriffPaymentStatus.UNKNOWN, result.payment.status)
        assertTrue(h.transport.requestsTo(threeDsPath).isEmpty())
    }

    @Test
    fun closeIsIgnoredWhileVerifying() = runTest {
        val h = harness()
        h.transport.on("GET", statusPath, http(200, ok(statusPayload("PENDING"))), http(200, ok(statusPayload("APPROVED"))))
        h.flow.start(NextAction.CheckStatus)
        h.flow.onUserClose()
        assertFalse("confirmClose" in h.ui.events)
        advanceUntilIdle()
        assertTrue(h.ui.result is PayriffResult.Success)
    }

    @Test
    fun restoringChecksTheStatusOnce() = runTest {
        val h = harness()
        h.transport.on("GET", statusPath, http(200, ok(statusPayload("PENDING"))))
        h.flow.start(null)
        advanceUntilIdle()
        assertEquals(PayriffPendingReason.NOT_FINAL, (h.ui.result as PayriffResult.Pending).reason)
        assertEquals(1, h.transport.requests.size)
    }

    @Test
    fun aPageLoadErrorOffersTryAgainUntilTheCallback() = runTest {
        val h = harness()
        h.transport.on("GET", statusPath, http(200, ok(statusPayload("APPROVED"))))
        h.flow.start(challenge())
        h.flow.onMainFrameError()
        assertEquals("pageLoadError", h.ui.events.last())
        // The error page itself finishing must not hide the error.
        h.flow.onPageVisible()
        assertFalse("page" in h.ui.events)
        h.flow.onRetry()
        assertEquals(2, h.ui.challenges.size)
        assertEquals(h.ui.challenges[0], h.ui.challenges[1])
        h.flow.onNavigation(callback, isMainFrame = true)
        h.flow.onMainFrameError()
        advanceUntilIdle()
        assertTrue(h.ui.result is PayriffResult.Success)
        assertEquals(1, h.ui.finishCount)
    }

    @Test
    fun aThreeDsErrorChecksTheStatusBeforeFailing() = runTest {
        val h = harness()
        h.transport.on("GET", threeDsPath, http(500, """{"code":"15000","message":"Internal error"}"""))
        h.transport.on("GET", statusPath, http(200, ok(statusPayload("PENDING"))))
        h.flow.start(NextAction.Wait)
        advanceUntilIdle()
        assertEquals(PayriffErrorCode.PAYMENT_FAILED, (h.ui.result as PayriffResult.Failed).error.code)
        assertEquals(1, h.transport.requestsTo(statusPath).size)
    }

    @Test
    fun aMissingWebViewChecksTheStatus() = runTest {
        val h = harness()
        h.transport.on("GET", statusPath, http(200, ok(statusPayload("PENDING"))))
        h.flow.start(challenge())
        h.flow.onWebViewUnavailable()
        advanceTimeBy(1)
        advanceUntilIdle()
        assertEquals(PayriffErrorCode.PRESENTATION_FAILED, (h.ui.result as PayriffResult.Failed).error.code)
    }

    @Test
    fun aCallbackInASubframeStartsPollingWithoutArmingTheFlow() = runTest {
        val h = harness()
        h.transport.on("GET", statusPath, http(200, ok(statusPayload("PENDING"))), http(200, ok(statusPayload("APPROVED"))))
        h.flow.start(challenge())
        h.flow.onSubframeCallbackObserved()
        assertEquals(ThreeDsNav.ALLOW, h.flow.onNavigation("https://acs.bank.example/next", isMainFrame = true))
        advanceUntilIdle()
        assertTrue(h.ui.result is PayriffResult.Success)
    }
}

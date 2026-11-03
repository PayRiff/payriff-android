package com.payriff.android.wallet

import com.payriff.android.Payriff
import com.payriff.android.PayriffConfig
import com.payriff.android.PayriffError
import com.payriff.android.PayriffErrorCode
import com.payriff.android.PayriffGooglePayConfig
import com.payriff.android.PayriffLanguage
import com.payriff.android.PayriffPaymentStatus
import com.payriff.android.PayriffRegion
import com.payriff.android.PayriffResult
import com.payriff.android.card.CLIENT_SECRET
import com.payriff.android.card.FakeThreeDsUi
import com.payriff.android.card.FakeTransport
import com.payriff.android.card.HOST
import com.payriff.android.card.PUBLISHABLE_KEY
import com.payriff.android.card.credentials
import com.payriff.android.card.http
import com.payriff.android.card.ok
import com.payriff.android.card.statusPayload
import com.payriff.android.internal.PaymentGuard
import com.payriff.android.internal.card.CardApiClient
import com.payriff.android.internal.card.CardCredentials
import com.payriff.android.internal.card.CardHandoffs
import com.payriff.android.internal.card.CardSheetRequest
import com.payriff.android.internal.card.NextAction
import com.payriff.android.internal.card.ThreeDsFlow
import com.payriff.android.internal.wallet.GooglePayApi
import com.payriff.android.internal.wallet.GooglePayOutcome
import com.payriff.android.internal.wallet.GooglePayPayment
import com.payriff.android.internal.wallet.GooglePayRequests
import com.payriff.android.internal.wallet.GooglePayTokens
import com.payriff.android.internal.wallet.GooglePayTransaction
import com.payriff.android.testing.HostAppRule
import com.payriff.android.testing.LogCapture
import com.payriff.android.testing.Vectors
import com.payriff.android.testing.Vectors.objects
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * CONTRACT §17.5–§17.8 and §17.11–§17.12, end to end with a fake Google Pay API and a fake Client API: OK → `/pay` →
 * `checkStatus` → `success`, a cancel, an error → `WALLET_FAILED`, a `challenge` → the 3-D Secure sheet, the device
 * checks, one payment at a time, the retries with one idempotency key per token, a result after a recreation, and that
 * the token never reaches the logs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GooglePayFlowTest {

    @get:Rule
    val logs = LogCapture()

    @get:Rule
    val hostApp = HostAppRule(debuggable = true)

    private val scopes = mutableListOf<CoroutineScope>()
    private val payPath = "/api/v3/client/orders/o1/pay"
    private val statusPath = "/api/v3/client/orders/o1/status"

    /** The vector's Google Pay token, inside Google's PaymentData JSON. */
    private val token = Vectors.load("wallet.json").getJSONArray("payRequests").objects()
        .single { it.getJSONObject("body").getString("method") == "GOOGLE_PAY" }
        .getString("walletToken")

    private val transaction = GooglePayTransaction(
        gateway = "payriff",
        gatewayMerchantId = "BCR2DN4T",
        totalPrice = "10.50",
        currencyCode = "AZN",
        countryCode = "AZ",
        merchantName = "Test Merchant",
        merchantId = null,
    )

    @Before
    fun setUp() {
        Payriff.reset()
        Payriff.configure(PayriffConfig(publishableKey = PUBLISHABLE_KEY, enableLogging = true, googlePay = PayriffGooglePayConfig()))
        PaymentGuard.clock = { 0L }
    }

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        Payriff.reset()
    }

    private class FakeGooglePay : GooglePayApi {
        val requests = mutableListOf<String>()
        var ready = true
        var failToShow = false

        override suspend fun isReadyToPay(request: String): Boolean = ready

        override fun loadPaymentData(request: String) {
            if (failToShow) throw IllegalStateException("no sheet")
            requests += request
        }
    }

    private class FakeHost(var order: GooglePayPayment.Order? = null) : GooglePayPayment.Host {
        val results = mutableListOf<PayriffResult>()
        val closed = mutableListOf<String>()
        val resumed = mutableListOf<String>()

        override fun resumableOrder(orderId: String): GooglePayPayment.Order? = order?.takeIf { it.orderId == orderId }

        override fun onResumed(orderId: String) {
            resumed += orderId
        }

        override fun onClosed(orderId: String) {
            closed += orderId
        }

        override fun onFinished(result: PayriffResult) {
            results += result
        }
    }

    private class Harness(
        val payment: GooglePayPayment,
        val googlePay: FakeGooglePay,
        val host: FakeHost,
        val transport: FakeTransport,
        val sheets: MutableList<CardSheetRequest>,
        val scope: CoroutineScope,
    )

    private fun TestScope.harness(
        resumeOrderId: String? = null,
        host: FakeHost = FakeHost(),
        deviceBlock: () -> PayriffError? = { null },
        credentials: (String) -> CardCredentials = { credentials() },
    ): Harness {
        val transport = FakeTransport { testScheduler.currentTime }
        val googlePay = FakeGooglePay()
        val sheets = mutableListOf<CardSheetRequest>()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()).also { scopes += it }
        var keys = 0
        val payment = GooglePayPayment(
            scope = scope,
            api = googlePay,
            openThreeDs = { sheets += it },
            host = host,
            resumeOrderId = resumeOrderId,
            deviceBlock = deviceBlock,
            credentials = credentials,
            clientApi = { CardApiClient(it, transport) },
            newIdempotencyKey = { "idem-${++keys}" },
        )
        return Harness(payment, googlePay, host, transport, sheets, scope)
    }

    private fun order() = GooglePayPayment.Order("o1", CLIENT_SECRET, PayriffLanguage.EN)

    /** Google's PaymentData JSON around [token]: card details and all, which must never be logged. */
    private fun paymentData(token: Any? = this.token): String = JSONObject()
        .put("apiVersion", 2)
        .put("apiVersionMinor", 0)
        .put(
            "paymentMethodData",
            JSONObject()
                .put("type", "CARD")
                .put("description", "Visa •••• 4321")
                .put("info", JSONObject().put("cardNetwork", "VISA").put("cardDetails", "4321"))
                .put("tokenizationData", JSONObject().put("type", "PAYMENT_GATEWAY").put("token", token ?: JSONObject.NULL)),
        )
        .toString()

    @Test
    fun okThenPayThenCheckStatusIsSuccess() = runTest {
        val h = harness()
        h.transport.on("POST", payPath, http(200, ok("""{"orderId":"o1","nextAction":"CHECK_STATUS"}""")))
        h.transport.on("GET", statusPath, http(200, ok(statusPayload("APPROVED"))))

        h.payment.start(order(), transaction)
        // The Google Pay sheet opens with the paymentDataRequest; the payment holds the one-payment guard.
        val request = JSONObject(h.googlePay.requests.single())
        assertEquals("10.50", request.getJSONObject("transactionInfo").getString("totalPrice"))
        assertTrue(h.payment.isRunning)
        assertTrue(Payriff.isPaymentInProgress)
        assertEquals("o1", h.payment.sheetOrderId)
        assertTrue(h.transport.requests.isEmpty())

        h.payment.onSheetOutcome(GooglePayOutcome.Ok(paymentData()))
        advanceUntilIdle()
        val pay = h.transport.requests.single()
        assertEquals("POST $payPath", "${pay.method} ${pay.path}")
        assertEquals(token, JSONObject(pay.bodyText!!).getString("encryptedMessage"))
        assertEquals("GOOGLE_PAY", JSONObject(pay.bodyText!!).getString("method"))
        assertFalse("no x-secret-key for a wallet", pay.headers.keys.any { it.equals("x-secret-key", ignoreCase = true) })
        assertEquals("idem-1", pay.headers["X-Idempotency-Key"])
        assertNull("the sheet's order is no longer waited for", h.payment.sheetOrderId)

        // /pay accepted: the 3-D Secure sheet runs the next action, handed over in memory, and polls the status.
        val sheet = h.sheets.single()
        assertEquals("o1", sheet.orderId)
        assertEquals(CLIENT_SECRET, sheet.clientSecret)
        assertEquals(PayriffLanguage.EN, sheet.language)
        val action = CardHandoffs.take(sheet.flowId)
        assertTrue(action === NextAction.CheckStatus)
        assertTrue("the sheet confirms the same flow", PaymentGuard.confirm(sheet.flowId))
        val ui = FakeThreeDsUi()
        ThreeDsFlow(h.scope, ui, CardApiClient(credentials(), h.transport), "o1", HOST, { testScheduler.currentTime }).start(action)
        advanceUntilIdle()
        val result = ui.result as PayriffResult.Success
        assertEquals(PayriffPaymentStatus.APPROVED, result.payment.status)

        h.payment.onThreeDsResult(result)
        assertEquals(listOf<PayriffResult>(result), h.host.results)
        assertFalse(h.payment.isRunning)
        assertFalse(Payriff.isPaymentInProgress)
        assertTrue(h.host.closed.isEmpty())
    }

    @Test
    fun aChallengeOpensTheThreeDsSheet() = runTest {
        val h = harness()
        h.transport.on(
            "POST",
            payPath,
            http(200, ok("""{"nextAction":"THREE_DS","accessUrl":"https://acs.bank.example/c","clientRequest":[{"key":"creq","value":"x"}]}""")),
        )
        h.payment.start(order(), transaction)
        h.payment.onSheetOutcome(GooglePayOutcome.Ok(paymentData()))
        advanceUntilIdle()
        val sheet = h.sheets.single()
        val challenge = CardHandoffs.take(sheet.flowId) as NextAction.Challenge
        assertEquals("https://acs.bank.example/c", challenge.url)
        val ui = FakeThreeDsUi()
        ThreeDsFlow(h.scope, ui, CardApiClient(credentials(), h.transport), "o1", HOST, { testScheduler.currentTime }).start(challenge)
        assertEquals(listOf("https://acs.bank.example/c" to "creq=x"), ui.challenges)
        assertTrue("the payment waits for the sheet", h.payment.isRunning && h.host.results.isEmpty())

        // The sheet ended without a result: pending(notFinal), as for cards.
        h.payment.onThreeDsResult(null)
        val pending = h.host.results.single() as PayriffResult.Pending
        assertEquals("o1", pending.payment.orderId)
        PaymentGuard.release(sheet.flowId)
    }

    @Test
    fun closingTheGooglePaySheetReportsNothingButTheClose() = runTest {
        val h = harness()
        h.payment.start(order(), transaction)
        h.payment.onSheetOutcome(GooglePayOutcome.Cancelled)
        assertEquals(listOf("o1"), h.host.closed)
        assertTrue(h.host.results.isEmpty())
        assertTrue(h.transport.requests.isEmpty())
        assertFalse(Payriff.isPaymentInProgress)
        assertTrue(logs.lines.any { it.endsWith("Google Pay: cancelled") })
    }

    @Test
    fun aGooglePayErrorIsWalletFailedWithItsStatus() = runTest {
        val h = harness()
        h.payment.start(order(), transaction)
        h.payment.onSheetOutcome(GooglePayOutcome.Error(10))
        val error = (h.host.results.single() as PayriffResult.Failed).error
        assertEquals(PayriffErrorCode.WALLET_FAILED, error.code)
        assertEquals("Google Pay failed (status 10).", error.message)
        assertFalse(error.isRetryable)
        assertFalse(error.isFatal)
        assertTrue(h.transport.requests.isEmpty())
        assertFalse(Payriff.isPaymentInProgress)
        assertTrue(logs.lines.any { it.endsWith("Google Pay: error 10") })
    }

    @Test
    fun aMissingOrBlankTokenIsWalletFailed() = runTest {
        for (data in listOf(paymentData(token = null), paymentData(token = "  "), paymentData(token = 42), "{}", "not json", null)) {
            val h = harness()
            h.payment.start(order(), transaction)
            h.payment.onSheetOutcome(GooglePayOutcome.Ok(data))
            val error = (h.host.results.single() as PayriffResult.Failed).error
            assertEquals(PayriffErrorCode.WALLET_FAILED, error.code)
            assertEquals(GooglePayPayment.NO_TOKEN_MESSAGE, error.message)
            assertTrue(h.transport.requests.isEmpty())
            assertFalse(Payriff.isPaymentInProgress)
        }
        assertEquals(token, GooglePayTokens.token(paymentData()))
        assertEquals(" spaced ", GooglePayTokens.token(paymentData(" spaced ")))
    }

    @Test
    fun aSheetThatCannotOpenIsWalletFailed() = runTest {
        val h = harness()
        h.googlePay.failToShow = true
        h.payment.start(order(), transaction)
        val error = (h.host.results.single() as PayriffResult.Failed).error
        assertEquals(PayriffErrorCode.WALLET_FAILED, error.code)
        assertEquals(GooglePayPayment.NOT_SHOWN_MESSAGE, error.message)
        assertFalse(Payriff.isPaymentInProgress)

        val again = harness()
        again.payment.start(order(), transaction)
        again.payment.onSheetOutcome(GooglePayOutcome.NotShown)
        assertEquals(GooglePayPayment.NOT_SHOWN_MESSAGE, (again.host.results.single() as PayriffResult.Failed).error.message)
    }

    @Test
    fun theStatusCodesMapToOutcomes() {
        assertTrue(GooglePayOutcome.of(0) { "{}" } is GooglePayOutcome.Ok)
        assertEquals("{}", (GooglePayOutcome.of(0) { "{}" } as GooglePayOutcome.Ok).paymentData)
        assertTrue(GooglePayOutcome.of(16) { null } === GooglePayOutcome.Cancelled)
        assertTrue("ERROR_CODE_USER_CANCELLED", GooglePayOutcome.of(416) { null } === GooglePayOutcome.Cancelled)
        assertEquals(10, (GooglePayOutcome.of(10) { null } as GooglePayOutcome.Error).statusCode)
        assertEquals(8, (GooglePayOutcome.of(8) { null } as GooglePayOutcome.Error).statusCode)
        assertEquals("Ok", GooglePayOutcome.Ok(paymentData()).toString())
    }

    @Test
    fun theDeviceIsCheckedOnTapAndRightBeforePay() = runTest {
        val blocked = PayriffError(PayriffErrorCode.DEVICE_COMPROMISED, "Card payments are not available on this device.")
        val atTap = harness(deviceBlock = { blocked })
        atTap.payment.start(order(), transaction)
        assertEquals(PayriffErrorCode.DEVICE_COMPROMISED, (atTap.host.results.single() as PayriffResult.Failed).error.code)
        assertTrue("no Google Pay sheet", atTap.googlePay.requests.isEmpty())

        var checks = 0
        val beforePay = harness(deviceBlock = { if (++checks == 1) null else blocked })
        beforePay.payment.start(order(), transaction)
        beforePay.payment.onSheetOutcome(GooglePayOutcome.Ok(paymentData()))
        advanceUntilIdle()
        assertEquals(2, checks)
        assertEquals(PayriffErrorCode.DEVICE_COMPROMISED, (beforePay.host.results.single() as PayriffResult.Failed).error.code)
        assertTrue("nothing is sent", beforePay.transport.requests.isEmpty())
        assertFalse(Payriff.isPaymentInProgress)
    }

    @Test
    fun onePaymentAtATime() = runTest {
        assertTrue(PaymentGuard.reserve("card-flow") && PaymentGuard.confirm("card-flow"))
        val h = harness()
        h.payment.start(order(), transaction)
        assertEquals(PayriffErrorCode.PAYMENT_IN_PROGRESS, (h.host.results.single() as PayriffResult.Failed).error.code)
        assertTrue(h.googlePay.requests.isEmpty())
        PaymentGuard.release("card-flow")

        val running = harness()
        running.payment.start(order(), transaction)
        val second = harness()
        second.payment.start(order(), transaction)
        assertEquals(PayriffErrorCode.PAYMENT_IN_PROGRESS, (second.host.results.single() as PayriffResult.Failed).error.code)
        running.payment.onSheetOutcome(GooglePayOutcome.Cancelled)
        assertFalse(Payriff.isPaymentInProgress)
    }

    @Test
    fun aConfigurationThatCannotPayFailsBeforeTheSheet() = runTest {
        val h = harness(credentials = { throw PayriffError(PayriffErrorCode.INVALID_CONFIG, "PayriffConfig.publishableKey must be your publishable key.") })
        h.payment.start(order(), transaction)
        assertEquals(PayriffErrorCode.INVALID_CONFIG, (h.host.results.single() as PayriffResult.Failed).error.code)
        assertTrue(h.googlePay.requests.isEmpty())
        val blank = harness()
        blank.payment.start(GooglePayPayment.Order(" ", CLIENT_SECRET, null), transaction)
        assertEquals(PayriffErrorCode.INVALID_ORDER, (blank.host.results.single() as PayriffResult.Failed).error.code)
        assertFalse(Payriff.isPaymentInProgress)
    }

    @Test
    fun everyTokenGetsANewIdempotencyKeyAndRetriesKeepIt() = runTest {
        val h = harness()
        h.transport.on(
            "POST",
            payPath,
            http(429, ""),
            FakeTransport.Reply.NoResponse(),
            http(200, ok("""{"nextAction":"CHECK_STATUS"}""")),
        )
        h.payment.start(order(), transaction)
        h.payment.onSheetOutcome(GooglePayOutcome.Ok(paymentData()))
        advanceUntilIdle()
        val posts = h.transport.requestsTo(payPath)
        assertEquals(3, posts.size)
        for (post in posts.drop(1)) {
            assertArrayEquals("the same token", posts.first().body, post.body)
            assertEquals("the same idempotency key", posts.first().headers, post.headers)
        }
        assertEquals("idem-1", posts.last().headers["X-Idempotency-Key"])
        CardHandoffs.take(h.sheets.single().flowId)
        h.payment.onThreeDsResult(PayriffResult.Pending(com.payriff.android.PayriffPayment("o1", PayriffPaymentStatus.PENDING), com.payriff.android.PayriffPendingReason.NOT_FINAL))
        assertFalse(Payriff.isPaymentInProgress)

        // The next payment's token is a new request with a new key.
        h.transport.on("POST", payPath, http(200, """{"code":"16001","message":"Declined by the issuer"}"""))
        h.payment.start(order(), transaction)
        h.payment.onSheetOutcome(GooglePayOutcome.Ok(paymentData()))
        advanceUntilIdle()
        assertEquals(PayriffErrorCode.PAYMENT_FAILED, (h.host.results.last() as PayriffResult.Failed).error.code)
        val next = h.transport.requestsTo(payPath).last()
        assertNotEquals(posts.first().headers["X-Idempotency-Key"], next.headers["X-Idempotency-Key"])
        assertEquals("idem-2", next.headers["X-Idempotency-Key"])
        assertFalse(Payriff.isPaymentInProgress)
    }

    @Test
    fun aRecreationKeepsTheSheetsOrderAndPaysOnlyThatOrder() = runTest {
        val first = harness()
        first.payment.start(order(), transaction)
        // The view leaves its window while the Google Pay sheet is open: the sheet still delivers to the next payment.
        assertTrue(first.payment.release(googlePaySheetOpen = true))
        assertEquals("o1", first.payment.sheetOrderId)
        assertFalse("the guard is free meanwhile", Payriff.isPaymentInProgress)

        // The recreated view shows the same order: the token pays it.
        val restored = harness(resumeOrderId = first.payment.sheetOrderId, host = FakeHost(order()))
        restored.transport.on("POST", payPath, http(200, ok("""{"nextAction":"CHECK_STATUS"}""")))
        assertEquals("o1", restored.payment.sheetOrderId)
        restored.payment.onSheetOutcome(GooglePayOutcome.Ok(paymentData()))
        advanceUntilIdle()
        assertEquals(listOf("o1"), restored.host.resumed)
        assertEquals(1, restored.transport.requestsTo(payPath).size)
        val sheet = restored.sheets.single()
        assertTrue(Payriff.isPaymentInProgress)
        restored.payment.onThreeDsResult(PayriffResult.Pending(com.payriff.android.PayriffPayment("o1", PayriffPaymentStatus.PENDING), com.payriff.android.PayriffPendingReason.NOT_FINAL))
        assertTrue(restored.host.results.single() is PayriffResult.Pending)
        assertFalse(Payriff.isPaymentInProgress)
        CardHandoffs.take(sheet.flowId)

        // A view that shows another order drops the token: nothing is sent.
        val other = harness(resumeOrderId = "o1", host = FakeHost(GooglePayPayment.Order("o2", CLIENT_SECRET, null)))
        other.payment.onSheetOutcome(GooglePayOutcome.Ok(paymentData()))
        advanceUntilIdle()
        assertTrue(other.transport.requests.isEmpty() && other.host.results.isEmpty() && other.host.resumed.isEmpty())
        assertNull(other.payment.sheetOrderId)

        // A token that no payment waits for is dropped too.
        val stray = harness(host = FakeHost(order()))
        stray.payment.onSheetOutcome(GooglePayOutcome.Ok(paymentData()))
        assertTrue(stray.transport.requests.isEmpty() && stray.host.results.isEmpty())

        // A view that leaves its window again before the awaited result arrives still waits for it.
        val again = harness(resumeOrderId = "o1")
        assertTrue(again.payment.release(googlePaySheetOpen = false))
        assertEquals("o1", again.payment.sheetOrderId)

        // A close that arrives after the recreation is reported as a close of that order.
        val closed = harness(resumeOrderId = "o1", host = FakeHost(order()))
        closed.payment.onSheetOutcome(GooglePayOutcome.Cancelled)
        assertEquals(listOf("o1"), closed.host.closed)
        assertFalse(Payriff.isPaymentInProgress)
    }

    @Test
    fun leavingDuringPayAbandonsThePaymentAndTheThreeDsSheetStillDelivers() = runTest {
        val h = harness()
        h.transport.on("POST", payPath, FakeTransport.Reply.Hang)
        h.payment.start(order(), transaction)
        h.payment.onSheetOutcome(GooglePayOutcome.Ok(paymentData()))
        runCurrent()
        assertEquals(1, h.transport.requestsTo(payPath).size)
        assertFalse("/pay is running: no sheet will deliver", h.payment.release(googlePaySheetOpen = false))
        advanceUntilIdle()
        assertTrue(h.host.results.isEmpty() && h.sheets.isEmpty())
        assertFalse(Payriff.isPaymentInProgress)
        assertNull(h.payment.sheetOrderId)

        // A sheet that never opened (Google Pay had not answered yet) does not wait either.
        val notOpen = harness()
        notOpen.payment.start(order(), transaction)
        assertFalse(notOpen.payment.release(googlePaySheetOpen = false))
        assertNull(notOpen.payment.sheetOrderId)
        assertFalse(Payriff.isPaymentInProgress)

        // The 3-D Secure sheet keeps the guard and delivers to the next registration.
        val threeDs = harness()
        threeDs.transport.on("POST", payPath, http(200, ok("""{"nextAction":"CHECK_STATUS"}""")))
        threeDs.payment.start(order(), transaction)
        threeDs.payment.onSheetOutcome(GooglePayOutcome.Ok(paymentData()))
        advanceUntilIdle()
        val sheet = threeDs.sheets.single()
        assertTrue(threeDs.payment.release(googlePaySheetOpen = false))
        assertTrue(Payriff.isPaymentInProgress)
        val restored = harness()
        val result = PayriffResult.Declined(com.payriff.android.PayriffPayment("o1", PayriffPaymentStatus.DECLINED))
        restored.payment.onThreeDsResult(result)
        assertEquals(listOf<PayriffResult>(result), restored.host.results)
        PaymentGuard.release(sheet.flowId)
        CardHandoffs.take(sheet.flowId)
    }

    @Test
    fun theTokenAndPaymentDataNeverReachTheLogs() = runTest {
        val h = harness()
        h.transport.on("POST", payPath, http(200, ok("""{"nextAction":"CHECK_STATUS"}""")))
        h.payment.start(order(), transaction)
        h.payment.onSheetOutcome(GooglePayOutcome.Ok(paymentData()))
        advanceUntilIdle()
        assertTrue("the HTTP details were logged", logs.lines.any { "HTTP request" in it && "\"encryptedMessage\": \"***(${token.length})\"" in it })
        for (secret in listOf("MEQCIFakeSignature", "MFkwEwYH", "ZmFrZQ", "dGFn", "4321", "tokenizationData", "paymentMethodData", CLIENT_SECRET)) {
            assertFalse(secret, logs.lines.any { secret in it })
        }
        assertTrue(logs.lines.any { it.endsWith("Google Pay: ok") })
        PaymentGuard.release(h.sheets.single().flowId)
        CardHandoffs.take(h.sheets.single().flowId)
    }

    @Test
    fun theSaRegionPaysWithItsCountry() {
        // The country goes into the Google Pay request; the rest of the flow is the same in both regions.
        assertEquals("SA", GooglePayRequests.countryCode(PayriffRegion.SA))
        assertEquals("AZ", GooglePayRequests.countryCode(PayriffRegion.AZ))
    }
}

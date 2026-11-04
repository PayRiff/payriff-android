package com.payriff.android.wallet

import com.payriff.android.Payriff
import com.payriff.android.PayriffCancelReason
import com.payriff.android.PayriffClientOrder
import com.payriff.android.PayriffConfig
import com.payriff.android.PayriffError
import com.payriff.android.PayriffErrorCode
import com.payriff.android.PayriffGooglePayButtonStyle
import com.payriff.android.PayriffGooglePayButtonType
import com.payriff.android.PayriffGooglePayConfig
import com.payriff.android.PayriffLanguage
import com.payriff.android.PayriffPayment
import com.payriff.android.PayriffPaymentStatus
import com.payriff.android.PayriffPaymentViewStyle
import com.payriff.android.PayriffResult
import com.payriff.android.PayriffWalletPlacement
import com.payriff.android.card.CLIENT_SECRET
import com.payriff.android.card.FakeTransport
import com.payriff.android.card.PUBLISHABLE_KEY
import com.payriff.android.card.http
import com.payriff.android.card.ok
import com.payriff.android.internal.card.CardStrings
import com.payriff.android.internal.card.PaymentViewController
import com.payriff.android.internal.card.PaymentViewKeys
import com.payriff.android.internal.card.PaymentViewPlan
import com.payriff.android.internal.card.PaymentViewPlan.Item
import com.payriff.android.internal.card.PaymentViewPlan.Part
import com.payriff.android.internal.card.PaymentViewState
import com.payriff.android.internal.wallet.GooglePayAvailability
import com.payriff.android.internal.wallet.GooglePayButtonController
import com.payriff.android.internal.wallet.GooglePayTransaction
import com.payriff.android.internal.wallet.GooglePayViewDecision
import com.payriff.android.testing.HostAppRule
import com.payriff.android.testing.LogCapture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.math.BigDecimal

/**
 * CONTRACT §17.9–§17.10: the payment view's wallet row (the Google Pay button and the divider appear only when Google
 * Pay is offered; without it the §16.14 layout is unchanged), its states, the Google Pay decision once per order, and
 * the standalone button's availability and results. With the real `Payriff.retrieveOrder` over a fake Client API.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GooglePayViewsTest {

    @get:Rule
    val logs = LogCapture()

    @get:Rule
    val hostApp = HostAppRule()

    private val orderPath = "/api/v3/client/orders/o1"
    private val googlePayOrder =
        """{"orderId":"o1","amount":10.5,"currency":"AZN","merchantName":"Test Merchant","status":"CREATED","paymentMethods":["CARD","GOOGLE_PAY"],"googlePay":{"gateway":"payriff","gatewayMerchantId":"BCR2DN4T"}}"""

    @Before
    fun setUp() {
        Payriff.reset()
        Payriff.configure(PayriffConfig(publishableKey = PUBLISHABLE_KEY, enableLogging = true, googlePay = PayriffGooglePayConfig()))
    }

    @After
    fun tearDown() {
        Payriff.reset()
    }

    // region The payment view's layout and copy

    @Test
    fun theWalletRowShowsOnlyWhenGooglePayIsOffered() {
        val default = PayriffPaymentViewStyle()
        val card = listOf(Item(Part.FORM, 0), Item(Part.PAY_BUTTON, 24), Item(Part.BADGES, 24), Item(Part.SECURE_NOTE, 20))
        // Without a wallet: exactly CONTRACT §16.14, no divider and no gap.
        assertEquals(card, PaymentViewPlan.content(default, showsError = false))
        assertEquals(card, PaymentViewPlan.content(default, showsError = false, showsWallet = false))
        // With Google Pay: the button, the divider 16 below it, and the form 16 below the divider; the rest unchanged.
        assertEquals(
            listOf(Item(Part.WALLET_BUTTON, 0), Item(Part.WALLET_DIVIDER, 16), Item(Part.FORM, 16)) + card.drop(1),
            PaymentViewPlan.content(default, showsError = false, showsWallet = true),
        )
        // The style hides the divider: the form is 16 below the button.
        assertEquals(
            listOf(Item(Part.WALLET_BUTTON, 0), Item(Part.FORM, 16)) + card.drop(1),
            PaymentViewPlan.content(PayriffPaymentViewStyle(showWalletDivider = false), showsError = false, showsWallet = true),
        )
        // An order without CARD still shows view.notPayable under Pay while the wallet button stays.
        assertEquals(
            listOf(Item(Part.WALLET_BUTTON, 0), Item(Part.WALLET_DIVIDER, 16), Item(Part.FORM, 16), Item(Part.PAY_BUTTON, 24), Item(Part.ERROR, 8)),
            PaymentViewPlan.content(PayriffPaymentViewStyle(showBadges = false, showSecureNote = false), showsError = true, showsWallet = true),
        )
        assertEquals(listOf(12, 1), listOf(PaymentViewPlan.WALLET_DIVIDER_TEXT_GAP_DP, PaymentViewPlan.WALLET_DIVIDER_LINE_DP))
    }

    @Test
    fun theDividerSaysOrPayByCardUnlessTheStyleSaysOtherwise() {
        assertEquals("Or pay by card", PaymentViewPlan.walletDividerText(PayriffLanguage.EN, PayriffPaymentViewStyle()))
        assertEquals("Və ya kartla ödəyin", PaymentViewPlan.walletDividerText(PayriffLanguage.AZ, PayriffPaymentViewStyle()))
        assertEquals("Или оплатите картой", PaymentViewPlan.walletDividerText(PayriffLanguage.RU, PayriffPaymentViewStyle()))
        for (language in PayriffLanguage.entries) {
            assertEquals(CardStrings.view(language, CardStrings.VIEW_OR_PAY_BY_CARD), PaymentViewPlan.walletDividerText(language, PayriffPaymentViewStyle(walletDividerText = "  ")))
            // Trimmed, and the same in every language.
            assertEquals("or use a card", PaymentViewPlan.walletDividerText(language, PayriffPaymentViewStyle(walletDividerText = " or use a card \n")))
        }
    }

    @Test
    fun theStyleHasTheWalletOptions() {
        with(PayriffPaymentViewStyle()) {
            assertEquals(PayriffGooglePayButtonStyle(), googlePayButton)
            assertNull(walletDividerText)
            assertTrue(showWalletDivider)
            assertEquals(PayriffWalletPlacement.TOP, walletPlacement)
        }
        val googlePayButton = PayriffGooglePayButtonStyle(type = PayriffGooglePayButtonType.PAY, height = 52f)
        val built = PayriffPaymentViewStyle.Builder()
            .googlePayButton(googlePayButton)
            .walletDividerText("or")
            .showWalletDivider(false)
            .walletPlacement(PayriffWalletPlacement.BELOW_PAY_BUTTON)
            .build()
        assertEquals(
            PayriffPaymentViewStyle(
                googlePayButton = googlePayButton,
                walletDividerText = "or",
                showWalletDivider = false,
                walletPlacement = PayriffWalletPlacement.BELOW_PAY_BUTTON,
            ),
            built,
        )
        assertEquals(built, built.newBuilder().build())
        assertEquals(listOf("TOP", "BELOW_PAY_BUTTON"), PayriffWalletPlacement.entries.map { it.name })
    }

    // endregion

    // region The payment view's states

    private val offer = GooglePayTransaction("payriff", "BCR2DN4T", "10.50", "AZN", "AZ", "Test Merchant", null)

    @Test
    fun theGooglePayButtonIsEnabledOnlyWhileNoPaymentRuns() {
        val state = PaymentViewState()
        assertFalse(state.isGooglePayShown)
        state.orderDidLoad(order(methods = listOf("GOOGLE_PAY")))
        assertFalse(state.isGooglePayDecided)
        assertFalse("undecided: not shown", state.isGooglePayShown)
        state.googlePayDidDecide(offer)
        assertTrue(state.isGooglePayDecided)
        assertTrue(state.isGooglePayShown && state.isGooglePayEnabled)
        // An order without CARD: Pay stays disabled with view.notPayable, the wallet button stays usable.
        state.isFormComplete = true
        assertFalse(state.isPayEnabled)
        assertEquals("This order cannot be paid by card", state.errorMessage(PayriffLanguage.EN))

        // While any payment runs, card or wallet: the wallet button is disabled, Pay shows its spinner.
        state.paymentDidStart()
        assertFalse(state.isGooglePayEnabled)
        assertTrue(state.isGooglePayShown)
        assertTrue(state.isBusy)
        state.paymentAbandoned()
        assertTrue("closing Google Pay lets the customer try again", state.isGooglePayEnabled)
        state.isFormProcessing = true
        assertFalse(state.isGooglePayEnabled)
        state.isFormProcessing = false

        // A wallet failure lets the customer try again; a status ends the order for both buttons.
        state.paymentDidStart()
        state.paymentDidFinish(PayriffResult.Failed(PayriffError(PayriffErrorCode.WALLET_FAILED, "Google Pay failed (status 8).")))
        assertTrue(state.isGooglePayEnabled)
        assertNull("no error line for a wallet failure", state.errorMessage(PayriffLanguage.EN)?.takeIf { it.contains("Google Pay") })
        state.paymentDidStart()
        state.paymentDidFinish(PayriffResult.Success(payment()))
        assertFalse(state.isGooglePayEnabled)
        assertTrue(state.isGooglePayShown)

        // A device block hides the button; another order forgets the decision.
        val blocked = PaymentViewState()
        blocked.orderDidLoad(order())
        blocked.googlePayDidDecide(offer)
        blocked.deviceDidBlock(PayriffError(PayriffErrorCode.DEVICE_COMPROMISED, "Card payments are not available on this device."))
        assertFalse(blocked.isGooglePayShown)
        blocked.newOrder()
        assertNull(blocked.googlePayOffer)
        assertFalse(blocked.isGooglePayDecided)
    }

    /** A payment view whose Google Pay decision for each load comes from [startGooglePay]. */
    private class Harness(scope: CoroutineScope, startGooglePay: (CoroutineScope) -> (suspend (PayriffClientOrder) -> GooglePayTransaction?)) {
        val delivered = mutableListOf<PayriffResult>()
        val controller = PaymentViewController(
            scope = scope,
            loadOrder = { orderId, clientSecret -> Payriff.retrieveOrder(orderId, clientSecret) },
            onStateChanged = {},
            deliver = { delivered += it },
            startGooglePay = startGooglePay,
        )
        val state: PaymentViewState get() = controller.state
    }

    private fun TestScope.fakeApi(): FakeTransport = FakeTransport { testScheduler.currentTime }.also { Payriff.transportForTests = it }

    @Test
    fun googlePayIsDecidedOncePerLoadedOrder() = runTest {
        val api = fakeApi()
        api.on("GET", orderPath, http(200, ok(googlePayOrder)))
        val decided = mutableListOf<PayriffClientOrder>()
        var ready = true
        val view = Harness(this) {
            { order ->
                decided += order
                GooglePayAvailability.decide(Payriff.config, canPresent = true, order = order, appName = "Example Shop") { ready }
            }
        }
        view.controller.setOrder("o1", CLIENT_SECRET)
        view.controller.appear()
        advanceUntilIdle()
        assertEquals("o1", decided.single().orderId)
        val offer = view.state.googlePayOffer!!
        assertEquals("10.50", offer.totalPrice)
        assertEquals("Test Merchant", offer.merchantName)
        assertTrue(view.state.isGooglePayShown)
        assertTrue(logs.lines.single { "Google Pay offered" in it }.endsWith("[DEBUG] Google Pay offered: true"))

        // Appearing again neither loads nor decides again.
        view.controller.disappear()
        view.controller.appear()
        advanceUntilIdle()
        assertEquals(1, api.requests.size)
        assertEquals(1, decided.size)

        // Another order is decided again: isReadyToPay false is not offered, with the reason in the log.
        ready = false
        api.on("GET", "/api/v3/client/orders/o2", http(200, ok(googlePayOrder.replace("\"o1\"", "\"o2\""))))
        view.controller.setOrder("o2", CLIENT_SECRET)
        advanceUntilIdle()
        assertNull(view.state.googlePayOffer)
        assertTrue(view.state.isGooglePayDecided)
        assertTrue(logs.lines.last { "Google Pay offered" in it }.endsWith("Google Pay offered: false (isReadyToPay is false)"))
    }

    @Test
    fun aDecisionInterruptedByLeavingTheWindowRunsAgainWithoutAnotherRead() = runTest {
        val api = fakeApi()
        api.on("GET", orderPath, http(200, ok(googlePayOrder), delayMillis = 300))
        val answers = mutableListOf<CompletableDeferred<Boolean>>()
        val view = Harness(this) { scope ->
            GooglePayViewDecision(scope, Payriff.config, canPresent = true, appName = null) {
                CompletableDeferred<Boolean>().also { answers += it }.await()
            }::decide
        }
        view.controller.setOrder("o1", CLIENT_SECRET)
        view.controller.appear()
        runCurrent()
        assertEquals("the device is asked when the load starts", 1, answers.size)
        // The order arrived at 300 ms; the decision waits for the device, and nothing is applied meanwhile.
        advanceTimeBy(800)
        assertSame(PaymentViewState.Order.Loading, view.state.order)
        view.controller.disappear()
        advanceUntilIdle()
        assertSame(PaymentViewState.Order.Loading, view.state.order)
        assertFalse(view.state.isGooglePayDecided)

        // Appearing again asks the device again, without another read, and waits at most a second from now.
        view.controller.appear()
        runCurrent()
        assertEquals(2, answers.size)
        assertEquals(1, api.requests.size)
        advanceTimeBy(500)
        answers.last().complete(true)
        runCurrent()
        assertTrue(view.state.order is PaymentViewState.Order.Payable)
        assertTrue("the order and Google Pay together", view.state.isGooglePayShown)
        // The first check's answer is no longer awaited, and the decision is logged once.
        answers.first().complete(false)
        advanceUntilIdle()
        assertTrue(view.state.isGooglePayShown)
        assertEquals(listOf("Google Pay offered: true"), logs.lines.filter { "Google Pay offered" in it }.map { it.substringAfter("[DEBUG] ") })
    }

    @Test
    fun aCompromisedDeviceNeverDecidesGooglePay() = runTest {
        val api = fakeApi()
        val decided = mutableListOf<PayriffClientOrder>()
        val delivered = mutableListOf<PayriffResult>()
        val controller = PaymentViewController(
            scope = this,
            loadOrder = { orderId, clientSecret -> Payriff.retrieveOrder(orderId, clientSecret) },
            onStateChanged = {},
            deliver = { delivered += it },
            deviceBlock = { PayriffError(PayriffErrorCode.DEVICE_COMPROMISED, "Card payments are not available on this device.") },
            startGooglePay = { { decided += it; null } },
        )
        controller.setOrder("o1", CLIENT_SECRET)
        controller.appear()
        controller.appear()
        advanceUntilIdle()
        assertTrue(api.requests.isEmpty() && decided.isEmpty())
        assertFalse(controller.state.isGooglePayShown)
        assertEquals("Google Pay offered: false (the device failed the integrity check)", logs.lines.single { "Google Pay offered" in it }.substringAfter("[DEBUG] "))
        assertEquals(PayriffErrorCode.DEVICE_COMPROMISED, (delivered.single() as PayriffResult.Failed).error.code)
    }

    @Test
    fun aWalletResultGoesToTheListenerAndAClosedSheetReportsNothing() = runTest {
        val api = fakeApi()
        api.on("GET", orderPath, http(200, ok(googlePayOrder)))
        val view = Harness(this) { { offer } }
        view.controller.setOrder("o1", CLIENT_SECRET)
        view.controller.appear()
        advanceUntilIdle()

        val started = mutableListOf<Triple<String, String, GooglePayTransaction>>()
        view.controller.payWithGooglePay { orderId, clientSecret, transaction -> started += Triple(orderId, clientSecret, transaction) }
        assertEquals("o1" to CLIENT_SECRET, started.single().first to started.single().second)
        assertSame(offer, started.single().third)
        assertTrue(view.state.isPaying)
        assertFalse(view.state.isPayEnabled)
        view.controller.payWithGooglePay { _, _, _ -> throw AssertionError("disabled while paying") }

        // The customer closes the Google Pay sheet: nothing is reported, the view stays.
        view.controller.paymentAbandoned()
        assertTrue(view.delivered.isEmpty())
        assertTrue(view.state.isGooglePayEnabled)

        view.controller.payWithGooglePay { _, _, _ -> }
        val paid = PayriffResult.Success(payment())
        view.controller.finishPayment(paid)
        assertEquals(listOf<PayriffResult>(paid), view.delivered)
        assertFalse(view.state.isGooglePayEnabled)
        view.controller.payWithGooglePay { _, _, _ -> throw AssertionError("the order is not paid twice") }

        // A payment that continues after a recreation makes the view busy until its result.
        val resumed = Harness(this) { { offer } }
        resumed.controller.paymentResumed()
        assertTrue(resumed.state.isPaying)
        resumed.controller.paymentResumed()
        resumed.controller.finishPayment(PayriffResult.Failed(PayriffError(PayriffErrorCode.WALLET_FAILED, "Google Pay returned no payment token.")))
        assertFalse(resumed.state.isPaying)
    }

    @Test
    fun theDecisionFollowsTheContractsOrderOfConditions() = runTest {
        val order = com.payriff.android.internal.card.CardApiClient.parseOrder(org.json.JSONObject(googlePayOrder), "o1")
        var asked = 0
        suspend fun decide(config: PayriffConfig?, canPresent: Boolean = true, ready: () -> Boolean = { true }) =
            GooglePayAvailability.decide(config, canPresent, order, "Example Shop") { asked++; ready() }

        assertNull(decide(PayriffConfig(publishableKey = PUBLISHABLE_KEY)))
        assertNull(decide(PayriffConfig(publishableKey = PUBLISHABLE_KEY, googlePay = PayriffGooglePayConfig()), canPresent = false))
        assertEquals("neither asks Google Pay", 0, asked)
        assertNull(decide(Payriff.config, ready = { throw IllegalStateException("Play services") }))
        assertEquals(1, asked)
        val offer = decide(Payriff.config)!!
        assertEquals("AZ", offer.countryCode)
        assertEquals(
            listOf(
                "Google Pay offered: false (PayriffConfig.googlePay is not set)",
                "Google Pay offered: false (the view is not shown in a ComponentActivity)",
                "Google Pay offered: false (isReadyToPay is false)",
                "Google Pay offered: true",
            ),
            logs.lines.filter { "Google Pay offered" in it }.map { it.substringAfter("[DEBUG] ") },
        )
        assertEquals("the order's status is APPROVED", GooglePayAvailability.orderProblem(order(status = "APPROVED", methods = listOf("GOOGLE_PAY"))))
        // Revision 10: paymentMethods is not checked; a card-only order needs a gateway merchant id from the config.
        assertEquals("no gatewayMerchantId", GooglePayAvailability.orderProblem(order()))
        assertNull(GooglePayAvailability.orderProblem(order(), PayriffGooglePayConfig(gatewayMerchantId = "PRF0001")))
    }

    // endregion

    // region The standalone button

    private class ButtonHarness(
        scope: CoroutineScope,
        deviceBlock: () -> PayriffError? = { null },
        unavailable: () -> String? = { null },
        decide: suspend (PayriffClientOrder) -> GooglePayTransaction?,
    ) {
        val delivered = mutableListOf<PayriffResult>()
        val controller = GooglePayButtonController(
            scope = scope,
            loadOrder = { orderId, clientSecret -> Payriff.retrieveOrder(orderId, clientSecret) },
            decide = decide,
            onStateChanged = {},
            deliver = { delivered += it },
            deviceBlock = deviceBlock,
            unavailable = unavailable,
        )
    }

    @Test
    fun theStandaloneButtonLoadsNothingWhenGooglePayIsNotPossible() = runTest {
        val api = fakeApi()
        api.on("GET", orderPath, http(200, ok(googlePayOrder)))
        var reason: String? = GooglePayAvailability.NOT_CONFIGURED
        val button = ButtonHarness(this, unavailable = { reason }) { offer }
        button.controller.setOrder("o1", CLIENT_SECRET)
        button.controller.appear()
        button.controller.appear()
        advanceUntilIdle()
        assertTrue("no order request", api.requests.isEmpty())
        assertTrue(button.controller.state.isDecided && !button.controller.state.isShown)
        assertEquals(1, logs.lines.count { "Google Pay offered: false (PayriffConfig.googlePay is not set)" in it })
        reason = null
        button.controller.appear()
        advanceUntilIdle()
        assertEquals(1, api.requests.size)
        assertTrue(button.controller.state.isShown)
    }

    @Test
    fun theStandaloneButtonShowsOnceGooglePayIsOffered() = runTest {
        val api = fakeApi()
        api.on("GET", orderPath, http(200, ok(googlePayOrder)))
        val button = ButtonHarness(this) { offer }
        val state = button.controller.state
        button.controller.setOrder("o1", CLIENT_SECRET)
        advanceUntilIdle()
        assertTrue("nothing is loaded before the button is on screen", api.requests.isEmpty())
        assertFalse(state.isShown || state.isDecided)

        button.controller.appear()
        advanceUntilIdle()
        assertTrue(state.isShown && state.isEnabled && state.isDecided)
        button.controller.appear()
        advanceUntilIdle()
        assertEquals(1, api.requests.size)

        // The customer closes the Google Pay sheet: cancelled(userClosed) with the order id and status unknown.
        button.controller.pay { _, _, _ -> }
        assertTrue(state.isPaying && !state.isEnabled)
        button.controller.paymentClosed("o1")
        val closed = button.delivered.single() as PayriffResult.Cancelled
        assertEquals(PayriffCancelReason.USER_CLOSED, closed.reason)
        assertEquals("o1", closed.payment?.orderId)
        assertEquals(PayriffPaymentStatus.UNKNOWN, closed.payment?.status)
        assertTrue("the customer can try again", state.isEnabled)

        // A result with a status ends the order; a wallet failure does not.
        button.controller.pay { _, _, _ -> }
        button.controller.finishPayment(PayriffResult.Failed(PayriffError(PayriffErrorCode.WALLET_FAILED, "Google Pay failed (status 8).")))
        assertTrue(state.isEnabled)
        button.controller.pay { _, _, _ -> }
        button.controller.finishPayment(PayriffResult.Success(payment()))
        assertFalse(state.isEnabled)
        assertTrue(state.isShown)
        button.controller.pay { _, _, _ -> throw AssertionError("the order is not paid twice") }
        assertEquals(3, button.delivered.size)

        // Another order starts afresh; a payment that still runs keeps the button busy until its result.
        button.controller.setOrder("o2", CLIENT_SECRET)
        assertFalse(state.isShown || state.isFinished)
        val running = ButtonHarness(this) { offer }
        running.controller.setOrder("o1", CLIENT_SECRET)
        running.controller.appear()
        advanceUntilIdle()
        running.controller.pay { _, _, _ -> }
        running.controller.setOrder("o2", CLIENT_SECRET)
        assertTrue(running.controller.state.isPaying)
        running.controller.finishPayment(PayriffResult.Failed(PayriffError(PayriffErrorCode.WALLET_FAILED, "Google Pay failed (status 8).")))
        assertFalse(running.controller.state.isPaying)
    }

    @Test
    fun theStandaloneButtonShowsNothingWhenNotOffered() = runTest {
        val api = fakeApi()
        api.on("GET", orderPath, FakeTransport.Reply.NoResponse(), http(200, ok(googlePayOrder)))
        var offered: GooglePayTransaction? = null
        val button = ButtonHarness(this) { offered }
        button.controller.setOrder("o1", CLIENT_SECRET)
        button.controller.appear()
        advanceUntilIdle()
        // A load that failed is not reported; it is decided (not offered) and loads again when the button appears.
        assertTrue(button.delivered.isEmpty())
        assertTrue(button.controller.state.isDecided && !button.controller.state.isShown)
        offered = offer
        button.controller.disappear()
        button.controller.appear()
        advanceUntilIdle()
        assertEquals(2, api.requests.size)
        assertTrue(button.controller.state.isShown)

        // A fatal load error is final for the order.
        api.on("GET", "/api/v3/client/orders/o3", http(404, """{"code":"01000","message":"Order not found"}"""))
        button.controller.setOrder("o3", CLIENT_SECRET)
        advanceUntilIdle()
        button.controller.disappear()
        button.controller.appear()
        advanceUntilIdle()
        assertEquals(3, api.requests.size)
        assertFalse(button.controller.state.isShown)
        assertTrue(button.delivered.isEmpty())
    }

    @Test
    fun theStandaloneButtonChecksTheDeviceWhenItAppears() = runTest {
        val api = fakeApi()
        api.on("GET", orderPath, http(200, ok(googlePayOrder)))
        var compromised = true
        val button = ButtonHarness(this, deviceBlock = {
            if (compromised) PayriffError(PayriffErrorCode.DEVICE_COMPROMISED, "Card payments are not available on this device.") else null
        }) { offer }
        button.controller.setOrder("o1", CLIENT_SECRET)
        button.controller.appear()
        button.controller.appear()
        advanceUntilIdle()
        assertTrue(api.requests.isEmpty())
        assertTrue(button.controller.state.isDecided && !button.controller.state.isShown)
        assertEquals(1, logs.lines.count { "Google Pay offered: false (the device failed the integrity check)" in it })
        compromised = false
        button.controller.appear()
        advanceUntilIdle()
        assertTrue(button.controller.state.isShown)
    }

    @Test
    fun theStandaloneButtonHasItsOwnRegistryKeys() {
        assertEquals("com.payriff.android.PayriffGooglePayButton:default", PaymentViewKeys.base(-1, PaymentViewKeys.GOOGLE_PAY_BUTTON))
        assertEquals("com.payriff.android.PayriffGooglePayButton:7", PaymentViewKeys.base(7, PaymentViewKeys.GOOGLE_PAY_BUTTON))
        assertEquals("com.payriff.android.PayriffPaymentView:7", PaymentViewKeys.base(7))
    }

    // endregion

    @Test
    fun theSdkManifestEnablesGooglePayAndExportsNothing() {
        val manifest = listOf(File("src/main/AndroidManifest.xml"), File("payriff/src/main/AndroidManifest.xml")).first { it.isFile }.readText()
        assertTrue(
            Regex("""<meta-data\s+android:name="com\.google\.android\.gms\.wallet\.api\.enabled"\s+android:value="true"\s*/>""").containsMatchIn(manifest),
        )
        val components = Regex("""<(activity|activity-alias|provider|receiver|service)\b[^>]*>""").findAll(manifest).map { it.value }.toList()
        assertTrue(components.isNotEmpty())
        for (component in components) assertTrue(component, "android:exported=\"false\"" in component)
    }

    private fun order(status: String = "CREATED", methods: List<String> = listOf("CARD")) = PayriffClientOrder(
        orderId = "o1",
        amount = BigDecimal("10.5"),
        currency = "AZN",
        description = null,
        merchantName = null,
        status = PayriffPaymentStatus.fromRaw(status),
        rawStatus = status,
        language = null,
        paymentMethods = methods,
    )

    private fun payment() = PayriffPayment("o1", PayriffPaymentStatus.APPROVED, rawStatus = "APPROVED", amount = BigDecimal("10.5"), currency = "AZN")
}

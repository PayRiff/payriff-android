package com.payriff.android.card

import com.payriff.android.Payriff
import com.payriff.android.PayriffCardFormStyle
import com.payriff.android.PayriffClientOrder
import com.payriff.android.PayriffConfig
import com.payriff.android.PayriffError
import com.payriff.android.PayriffErrorCode
import com.payriff.android.PayriffLanguage
import com.payriff.android.PayriffPayment
import com.payriff.android.PayriffPaymentStatus
import com.payriff.android.PayriffPaymentViewStyle
import com.payriff.android.PayriffResult
import com.payriff.android.PayriffTextStyle
import com.payriff.android.internal.card.CardStrings
import com.payriff.android.internal.card.LockGlyph
import com.payriff.android.internal.card.PaymentViewAsset
import com.payriff.android.internal.card.PaymentViewController
import com.payriff.android.internal.card.PaymentViewKeys
import com.payriff.android.internal.card.PaymentViewPlan
import com.payriff.android.internal.card.PaymentViewPlan.Item
import com.payriff.android.internal.card.PaymentViewPlan.Part
import com.payriff.android.internal.card.PaymentViewState
import com.payriff.android.testing.HostAppRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
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
import java.io.DataInputStream
import java.io.File
import java.math.BigDecimal

/**
 * CONTRACT §16.14, `PayriffPaymentView`: its parts top to bottom, the Pay button's title and states, the error line, the
 * results it reports (with the real `Payriff.retrieveOrder` over a fake Client API), the "Powered by" split, its images
 * and the lock it draws in code.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PaymentViewTest {

    @get:Rule
    val hostApp = HostAppRule()

    private val orderPath = "/api/v3/client/orders/o1"

    @Before
    fun setUp() {
        Payriff.reset()
        Payriff.configure(PayriffConfig(publishableKey = PUBLISHABLE_KEY))
    }

    @After
    fun tearDown() {
        Payriff.reset()
    }

    // region Structure

    @Test
    fun theViewIsTheFormThePayButtonTheBadgesTheSecureLineAndTheFooter() {
        val default = PayriffPaymentViewStyle()
        assertEquals(
            listOf(Item(Part.FORM, 0), Item(Part.PAY_BUTTON, 24), Item(Part.BADGES, 24), Item(Part.SECURE_NOTE, 20)),
            PaymentViewPlan.content(default, showsError = false),
        )
        assertEquals(Item(Part.POWERED_BY, 24), PaymentViewPlan.footer(default))
        // The error line goes 8 under the button; the badges stay 24 under it.
        assertEquals(
            listOf(Item(Part.FORM, 0), Item(Part.PAY_BUTTON, 24), Item(Part.ERROR, 8), Item(Part.BADGES, 24), Item(Part.SECURE_NOTE, 20)),
            PaymentViewPlan.content(default, showsError = true),
        )
    }

    @Test
    fun theStyleHidesTheBadgesTheSecureLineAndTheFooter() {
        val noBadges = PayriffPaymentViewStyle(showBadges = false)
        assertEquals(
            listOf(Item(Part.FORM, 0), Item(Part.PAY_BUTTON, 24), Item(Part.SECURE_NOTE, 24)),
            PaymentViewPlan.content(noBadges, showsError = false),
        )
        val noSecureNote = PayriffPaymentViewStyle(showSecureNote = false)
        assertEquals(
            listOf(Item(Part.FORM, 0), Item(Part.PAY_BUTTON, 24), Item(Part.BADGES, 24)),
            PaymentViewPlan.content(noSecureNote, showsError = false),
        )
        assertNull(PaymentViewPlan.footer(PayriffPaymentViewStyle(showPoweredBy = false)))
        val bare = PayriffPaymentViewStyle(showBadges = false, showSecureNote = false, showPoweredBy = false)
        assertEquals(listOf(Item(Part.FORM, 0), Item(Part.PAY_BUTTON, 24)), PaymentViewPlan.content(bare, showsError = false))
        assertEquals(
            listOf(Item(Part.FORM, 0), Item(Part.PAY_BUTTON, 24), Item(Part.ERROR, 8)),
            PaymentViewPlan.content(bare, showsError = true),
        )
    }

    @Test
    fun theStyleDefaultsAreThePaymentPages() {
        with(PayriffPaymentViewStyle()) {
            assertNull(backgroundColor)
            assertEquals(20f, padding, 0f)
            assertNull(buttonColor)
            assertEquals(PayriffTextStyle(size = 16f, weight = PayriffTextStyle.SEMIBOLD), buttonTextStyle)
            assertEquals(listOf(44f, 16f), listOf(buttonHeight, buttonCornerRadius))
            assertNull(secondaryTextColor)
            assertEquals(listOf(true, true, true), listOf(showBadges, showSecureNote, showPoweredBy))
            assertEquals(PayriffCardFormStyle(), formStyle)
            assertTrue(formStyle.showLabels)
        }
        assertEquals(0xFFFCFCFD.toInt(), PaymentViewPlan.BACKGROUND_LIGHT)
        assertEquals(0xFF222224.toInt(), PaymentViewPlan.BACKGROUND_DARK)
        assertEquals(0xFF4DBDC6.toInt(), PaymentViewPlan.BUTTON_COLOR)
        assertEquals(0xFFFFFFFF.toInt(), PaymentViewPlan.BUTTON_TEXT_COLOR)
        assertEquals(0xFF76787F.toInt(), PaymentViewPlan.SECONDARY_TEXT_COLOR)
        assertEquals(12f, PaymentViewPlan.SECONDARY_TEXT_SIZE_SP, 0f)
        assertEquals(listOf(0.5f, 0.8f), listOf(PaymentViewPlan.DISABLED_ALPHA, PaymentViewPlan.PRESSED_ALPHA))
    }

    @Test
    fun theJavaBuilderMatchesNamedArguments() {
        val formStyle = PayriffCardFormStyle(showLabels = false)
        val built = PayriffPaymentViewStyle.Builder()
            .backgroundColor(0xFF101010.toInt())
            .padding(16f)
            .buttonColor(0xFF0B57D0.toInt())
            .buttonTextStyle(PayriffTextStyle(size = 17f, weight = PayriffTextStyle.BOLD))
            .buttonHeight(52f)
            .buttonCornerRadius(28f)
            .secondaryTextColor(0xFF555555.toInt())
            .showBadges(false)
            .showSecureNote(false)
            .showPoweredBy(false)
            .formStyle(formStyle)
            .build()
        val named = PayriffPaymentViewStyle(
            backgroundColor = 0xFF101010.toInt(),
            padding = 16f,
            buttonColor = 0xFF0B57D0.toInt(),
            buttonTextStyle = PayriffTextStyle(size = 17f, weight = 700),
            buttonHeight = 52f,
            buttonCornerRadius = 28f,
            secondaryTextColor = 0xFF555555.toInt(),
            showBadges = false,
            showSecureNote = false,
            showPoweredBy = false,
            formStyle = formStyle,
        )
        assertEquals(named, built)
        assertEquals(named, named.newBuilder().build())
        assertEquals(PayriffPaymentViewStyle(), PayriffPaymentViewStyle.Builder().build())
    }

    // endregion

    // region Copy

    @Test
    fun thePayTitleCarriesTheAmountWithTwoDecimalsAndTheCurrency() {
        val order = order(amount = "1.02", currency = "AZN")
        assertEquals("Pay 1.02 AZN", PaymentViewPlan.payTitle(PayriffLanguage.EN, order))
        assertEquals("Ödə 1.02 AZN", PaymentViewPlan.payTitle(PayriffLanguage.AZ, order))
        assertEquals("Оплатить 1.02 AZN", PaymentViewPlan.payTitle(PayriffLanguage.RU, order))
        // While the amount is unknown: view.pay alone.
        assertEquals("Pay", PaymentViewPlan.payTitle(PayriffLanguage.EN, null))
        assertEquals("Ödə", PaymentViewPlan.payTitle(PayriffLanguage.AZ, order(amount = null)))
        assertEquals("Pay 10.50 USD", PaymentViewPlan.payTitle(PayriffLanguage.EN, order(amount = "10.5", currency = " USD ")))
        assertEquals("Pay 3.00", PaymentViewPlan.payTitle(PayriffLanguage.EN, order(amount = "3", currency = " ")))
        assertEquals("Pay 3.00", PaymentViewPlan.payTitle(PayriffLanguage.EN, order(amount = "3", currency = null)))
        // Two decimals, `.` as the separator, no grouping, half up.
        assertEquals(
            listOf("1.02", "10.50", "1234.00", "1000000.00", "1.01", "0.00", "0.10"),
            listOf("1.02", "10.5", "1234", "1E+6", "1.005", "0.004", "0.1").map { PaymentViewPlan.amountText(BigDecimal(it)) },
        )
    }

    @Test
    fun poweredByIsSplitAtPayriffWhereTheLogoGoes() {
        assertEquals("Powered by " to "", PaymentViewPlan.poweredByParts(PayriffLanguage.EN))
        assertEquals("" to " tərəfindən hazırlanıb", PaymentViewPlan.poweredByParts(PayriffLanguage.AZ))
        assertEquals("При поддержке " to "", PaymentViewPlan.poweredByParts(PayriffLanguage.RU))
        for (language in PayriffLanguage.entries) {
            val (before, after) = PaymentViewPlan.poweredByParts(language)!!
            assertEquals(CardStrings.view(language, CardStrings.VIEW_POWERED_BY), before + PaymentViewPlan.WORDMARK + after)
        }
        assertEquals("Proceed securely by PCI DSS", CardStrings.view(PayriffLanguage.EN, CardStrings.VIEW_SECURE))
        assertEquals("PCI DSS ilə təhlükəsiz ödəniş", CardStrings.view(PayriffLanguage.AZ, CardStrings.VIEW_SECURE))
        assertEquals("Безопасная оплата по PCI DSS", CardStrings.view(PayriffLanguage.RU, CardStrings.VIEW_SECURE))
    }

    @Test
    fun theNotPayableLineIsInTheViewsLanguage() {
        assertEquals("This order cannot be paid by card", PaymentViewPlan.notPayableMessage(PayriffLanguage.EN))
        assertEquals("Bu sifarişi kartla ödəmək mümkün deyil", PaymentViewPlan.notPayableMessage(PayriffLanguage.AZ))
        assertEquals("Этот заказ нельзя оплатить картой", PaymentViewPlan.notPayableMessage(PayriffLanguage.RU))
        for (language in PayriffLanguage.entries) {
            assertEquals(CardStrings.view(language, CardStrings.VIEW_NOT_PAYABLE), PaymentViewPlan.notPayableMessage(language))
        }
    }

    // endregion

    // region Images

    @Test
    fun theImagesAreDrawablesInFourDensitiesAtTheirSizes() {
        val drawables = Class.forName("com.payriff.android.R\$drawable")
        val res = listOf(File("src/main/res"), File("payriff/src/main/res")).first { it.isDirectory }
        for (asset in PaymentViewAsset.entries) {
            assertEquals(asset.name, drawables.getField(asset.resourceName).getInt(null), asset.resId)
            val densities = listOf("drawable-mdpi" to 1.0, "drawable-hdpi" to 1.5, "drawable-xhdpi" to 2.0, "drawable-xxhdpi" to 3.0)
            for ((folder, scale) in densities) {
                val png = File(res, "$folder/${asset.resourceName}.png")
                assertTrue("missing $png", png.isFile)
                val (width, height) = pngSize(png)
                // hdpi sizes are rounded to whole pixels.
                assertEquals("$png width", asset.widthDp * scale, width.toDouble(), 0.5)
                assertEquals("$png height", asset.heightDp * scale, height.toDouble(), 0.5)
            }
        }
        assertEquals(listOf(43 to 28, 42 to 28), PaymentViewAsset.BADGES.map { it.widthDp to it.heightDp })
        assertEquals(listOf("Mastercard", "Visa"), PaymentViewAsset.BADGES.map { it.accessibilityLabel })
        assertEquals(82 to 16, PaymentViewAsset.LOGO.widthDp to PaymentViewAsset.LOGO.heightDp)
    }

    /** Width and height from a PNG's IHDR chunk. */
    private fun pngSize(file: File): Pair<Int, Int> = DataInputStream(file.inputStream().buffered()).use { input ->
        val signature = ByteArray(8)
        input.readFully(signature)
        assertEquals(listOf(0x89, 0x50, 0x4E, 0x47), signature.take(4).map { it.toInt() and 0xFF })
        input.readInt() // IHDR length
        assertEquals("IHDR", String(ByteArray(4).also(input::readFully), Charsets.US_ASCII))
        input.readInt() to input.readInt()
    }

    @Test
    fun theLockIsDrawnInCodeInsideItsSquare() {
        // 15×15, 6 before the secure text, in green.
        assertEquals(15, PaymentViewPlan.LOCK_SIZE_DP)
        assertEquals(6, PaymentViewPlan.LOCK_GAP_DP)
        with(LockGlyph) {
            assertEquals(0xFF56BE3D.toInt(), COLOR)
            assertEquals(0.085f, STROKE_WIDTH, 0f)
            assertEquals(
                listOf(0.32f, 0.68f, 0.45f, 0.5f, 0.31f, 0.18f),
                listOf(SHACKLE_LEFT, SHACKLE_RIGHT, SHACKLE_BOTTOM, SHACKLE_CENTRE_X, SHACKLE_CENTRE_Y, SHACKLE_RADIUS),
            )
            assertEquals(listOf(0.17f, 0.45f, 0.83f, 0.92f, 0.13f), listOf(BODY_LEFT, BODY_TOP, BODY_RIGHT, BODY_BOTTOM, BODY_CORNER_RADIUS))
            assertEquals(listOf(0.5f, 0.665f, 0.075f), listOf(KEYHOLE_X, KEYHOLE_Y, KEYHOLE_RADIUS))

            // The shackle's legs run into the ends of its half circle and stand on the body.
            assertEquals(SHACKLE_CENTRE_X - SHACKLE_RADIUS, SHACKLE_LEFT, 1e-6f)
            assertEquals(SHACKLE_CENTRE_X + SHACKLE_RADIUS, SHACKLE_RIGHT, 1e-6f)
            assertEquals(BODY_TOP, SHACKLE_BOTTOM, 0f)
            // Centred, with every line inside the square and the keyhole inside the body.
            val halfLine = STROKE_WIDTH / 2
            assertEquals(0.5f, (BODY_LEFT + BODY_RIGHT) / 2, 1e-6f)
            assertEquals(SHACKLE_CENTRE_X, KEYHOLE_X, 0f)
            assertTrue(SHACKLE_CENTRE_Y - SHACKLE_RADIUS - halfLine >= 0f)
            assertTrue(BODY_LEFT - halfLine >= 0f && BODY_RIGHT + halfLine <= 1f && BODY_BOTTOM + halfLine <= 1f)
            assertTrue(KEYHOLE_Y - KEYHOLE_RADIUS > BODY_TOP + halfLine && KEYHOLE_Y + KEYHOLE_RADIUS < BODY_BOTTOM - halfLine)
        }

        // No image of a lock ships with the SDK.
        val drawables = Class.forName("com.payriff.android.R\$drawable")
        assertTrue(drawables.fields.none { it.name.startsWith("payriff_") && "lock" in it.name })
        val res = listOf(File("src/main/res"), File("payriff/src/main/res")).first { it.isDirectory }
        assertTrue(res.walk().none { "lock" in it.name })
    }

    // endregion

    // region States

    @Test
    fun payIsDisabledWhileTheOrderLoadsAndUntilTheFormIsComplete() {
        val state = PaymentViewState()
        assertSame(PaymentViewState.Order.Loading, state.order)
        state.isFormComplete = true
        assertFalse("loading", state.isPayEnabled)
        assertEquals("Pay", state.payTitle(PayriffLanguage.EN))
        assertNull(state.errorMessage(PayriffLanguage.EN))
        assertTrue(state.needsOrder)

        state.orderDidLoad(order(amount = "1.02"))
        state.isFormComplete = false
        assertFalse("incomplete form", state.isPayEnabled)
        assertEquals("Pay 1.02 AZN", state.payTitle(PayriffLanguage.EN))
        state.isFormComplete = true
        assertTrue(state.isPayEnabled)
        assertFalse(state.isBusy)
        assertFalse(state.needsOrder)

        state.paymentDidStart()
        assertFalse("paying", state.isPayEnabled)
        assertTrue("a spinner replaces the title", state.isBusy)
        state.paymentDidFinish(PayriffResult.Failed(PayriffError(PayriffErrorCode.PAYMENT_FAILED, "Insufficient funds", isRetryable = true)))
        assertTrue("the customer may try again", state.isPayEnabled)
        assertNull(state.errorMessage(PayriffLanguage.EN))

        state.isFormProcessing = true
        assertFalse(state.isPayEnabled)
        assertTrue(state.isBusy)
    }

    @Test
    fun anOrderThatCannotBePaidByCardShowsWhyAndStaysDisabled() {
        for (notPayable in listOf(order(status = "APPROVED"), order(methods = listOf("GOOGLE_PAY")))) {
            val state = PaymentViewState()
            state.isFormComplete = true
            state.orderDidLoad(notPayable)
            assertFalse(state.isPayEnabled)
            assertEquals("This order cannot be paid by card", state.errorMessage(PayriffLanguage.EN))
            assertEquals("Bu sifarişi kartla ödəmək mümkün deyil", state.errorMessage(PayriffLanguage.AZ))
            assertEquals("Этот заказ нельзя оплатить картой", state.errorMessage(PayriffLanguage.RU))
            assertEquals("Pay 1.02 AZN", state.payTitle(PayriffLanguage.EN))
        }
        val failed = PaymentViewState()
        failed.isFormComplete = true
        assertNull(failed.orderDidFail(PayriffError(PayriffErrorCode.NETWORK_ERROR, "No response from Payriff (offline or timed out).")))
        assertFalse(failed.isPayEnabled)
        assertEquals("No response from Payriff (offline or timed out).", failed.errorMessage(PayriffLanguage.EN))
        assertEquals("Pay", failed.payTitle(PayriffLanguage.EN))
        assertTrue("a failure that is not fatal loads again when the view appears", failed.needsOrder)
        failed.orderDidFail(PayriffError(PayriffErrorCode.UNKNOWN, "  "))
        assertEquals("This order cannot be paid by card", failed.errorMessage(PayriffLanguage.EN))
        assertEquals("Этот заказ нельзя оплатить картой", failed.errorMessage(PayriffLanguage.RU))
    }

    @Test
    fun aPaymentWithAStatusOrAnErrorThatEndsTheOrderDisablesPay() {
        val paid = payableState()
        paid.paymentDidFinish(PayriffResult.Success(payment()))
        assertFalse(paid.isPayEnabled)
        assertTrue(paid.isFinished)
        assertNull("no error line after a status", paid.errorMessage(PayriffLanguage.EN))
        val pending = payableState()
        pending.paymentDidFinish(PayriffResult.Pending(payment(), com.payriff.android.PayriffPendingReason.NOT_FINAL))
        assertFalse(pending.isPayEnabled)

        val notPayable = payableState()
        notPayable.paymentDidFinish(PayriffResult.Failed(PayriffError(PayriffErrorCode.ORDER_NOT_PAYABLE, "The order cannot be paid (HTTP 400).")))
        assertFalse(notPayable.isPayEnabled)
        assertEquals("The order cannot be paid (HTTP 400).", notPayable.errorMessage(PayriffLanguage.EN))

        val expired = payableState()
        val fatal = PayriffError(PayriffErrorCode.SESSION_EXPIRED, "Order not found", isFatal = true)
        expired.paymentDidFinish(PayriffResult.Failed(fatal))
        assertFalse(expired.isPayEnabled)
        assertEquals("Order not found", expired.errorMessage(PayriffLanguage.EN))
        assertNull("a fatal error already reported through the payment is not reported again", expired.orderDidFail(fatal))

        // Another order starts afresh.
        expired.newOrder()
        assertNull(expired.errorMessage(PayriffLanguage.EN))
        assertTrue(expired.orderDidFail(fatal) is PayriffResult.Failed)
    }

    // endregion

    // region Results, with the real Payriff.retrieveOrder over a fake Client API

    private class Harness(scope: CoroutineScope) {
        val delivered = mutableListOf<PayriffResult>()
        var changes = 0
        val controller = PaymentViewController(
            scope = scope,
            loadOrder = { orderId, clientSecret -> Payriff.retrieveOrder(orderId, clientSecret) },
            onStateChanged = { changes++ },
            deliver = { delivered += it },
        )
        val state: PaymentViewState get() = controller.state
    }

    private fun TestScope.fakeApi(): FakeTransport =
        FakeTransport { testScheduler.currentTime }.also { Payriff.transportForTests = it }

    @Test
    fun theOrderLoadsWhenTheViewAppearsAndPayDeliversItsResult() = runTest {
        val api = fakeApi()
        api.on("GET", orderPath, http(200, ok(orderPayload())))
        val view = Harness(this)
        view.controller.setOrder("o1", CLIENT_SECRET)
        advanceUntilIdle()
        assertTrue("nothing is loaded before the view is on screen", api.requests.isEmpty())

        view.controller.appear()
        advanceUntilIdle()
        val get = api.requests.single()
        assertEquals("GET $orderPath", "${get.method} ${get.path}")
        assertEquals(PUBLISHABLE_KEY, get.headers["X-Public-Key"])
        assertEquals(CLIENT_SECRET, get.headers["X-Client-Secret"])
        assertTrue(view.state.order is PaymentViewState.Order.Payable)
        assertEquals("Pay 10.50 AZN", view.state.payTitle(PayriffLanguage.EN))
        assertFalse("the form is still empty", view.state.isPayEnabled)

        view.state.isFormComplete = true
        assertTrue(view.state.isPayEnabled)
        val confirmed = mutableListOf<Pair<String, String>>()
        val paid = PayriffResult.Success(payment())
        view.controller.pay { orderId, clientSecret, done ->
            confirmed += orderId to clientSecret
            assertTrue("busy while the payment runs", view.state.isBusy)
            done(paid)
            done(PayriffResult.Failed(PayriffError(PayriffErrorCode.UNKNOWN, "a second callback")))
        }
        assertEquals(listOf("o1" to CLIENT_SECRET), confirmed)
        assertEquals("exactly one result", listOf<PayriffResult>(paid), view.delivered)
        assertFalse("the order is not paid twice from the view", view.state.isPayEnabled)
        view.controller.pay { _, _, _ -> throw AssertionError("Pay is disabled") }

        // Appearing again does not load a loaded order again.
        view.controller.disappear()
        view.controller.appear()
        advanceUntilIdle()
        assertEquals(1, api.requests.size)
    }

    @Test
    fun aFatalErrorIsReportedOnceAndTheOrderIsNotLoadedAgain() = runTest {
        val api = fakeApi()
        api.on("GET", orderPath, http(404, """{"code":"01000","message":"Order not found"}"""))
        val view = Harness(this)
        view.controller.setOrder("o1", CLIENT_SECRET)
        view.controller.appear()
        advanceUntilIdle()
        val failed = view.delivered.single() as PayriffResult.Failed
        assertEquals(PayriffErrorCode.SESSION_EXPIRED, failed.error.code)
        assertTrue(failed.error.isFatal)
        assertEquals("Order not found", view.state.errorMessage(PayriffLanguage.EN))
        assertFalse(view.state.isPayEnabled)

        view.controller.disappear()
        view.controller.appear()
        advanceUntilIdle()
        assertEquals("not loaded again", 1, api.requests.size)
        assertEquals("reported once", 1, view.delivered.size)

        // The same order set again changes nothing; another order is loaded, and its fatal error reported.
        view.controller.setOrder("o1", CLIENT_SECRET)
        advanceUntilIdle()
        assertEquals(1, api.requests.size)
        api.on("GET", "/api/v3/client/orders/o2", http(401, """{"code":"01000","message":"Unauthorized"}"""))
        view.controller.setOrder("o2", CLIENT_SECRET)
        advanceUntilIdle()
        assertEquals(2, api.requests.size)
        assertEquals(PayriffErrorCode.UNAUTHORIZED, (view.delivered.last() as PayriffResult.Failed).error.code)
        assertEquals(2, view.delivered.size)
    }

    @Test
    fun aNetworkErrorShowsItsMessageAndTheOrderLoadsAgainWhenTheViewAppears() = runTest {
        val api = fakeApi()
        api.on("GET", orderPath, FakeTransport.Reply.NoResponse(), http(200, ok(orderPayload())))
        val view = Harness(this)
        view.controller.setOrder("o1", CLIENT_SECRET)
        view.controller.appear()
        advanceUntilIdle()
        assertTrue("not fatal: nothing reported", view.delivered.isEmpty())
        assertEquals("No response from Payriff (offline or timed out).", view.state.errorMessage(PayriffLanguage.EN))
        assertEquals("Pay", view.state.payTitle(PayriffLanguage.EN))

        view.controller.disappear()
        view.controller.appear()
        advanceUntilIdle()
        assertEquals(2, api.requests.size)
        assertTrue(view.state.order is PaymentViewState.Order.Payable)
        assertNull(view.state.errorMessage(PayriffLanguage.EN))
    }

    @Test
    fun anOrderThatCannotBePaidByCardIsShownWithoutAResult() = runTest {
        val api = fakeApi()
        api.on("GET", orderPath, http(200, ok(orderPayload(status = "APPROVED"))))
        val view = Harness(this)
        view.controller.setOrder("o1", CLIENT_SECRET)
        view.controller.appear()
        advanceUntilIdle()
        view.state.isFormComplete = true
        assertTrue(view.state.order is PaymentViewState.Order.NotPayable)
        assertEquals("This order cannot be paid by card", view.state.errorMessage(PayriffLanguage.EN))
        assertEquals("Pay 10.50 AZN", view.state.payTitle(PayriffLanguage.EN))
        assertFalse(view.state.isPayEnabled)
        assertTrue(view.delivered.isEmpty())
    }

    @Test
    fun aFailedPaymentLetsTheCustomerTryAgainUnlessItEndsTheOrder() = runTest {
        val api = fakeApi()
        api.on("GET", orderPath, http(200, ok(orderPayload())))
        val view = Harness(this)
        view.controller.setOrder("o1", CLIENT_SECRET)
        view.controller.appear()
        advanceUntilIdle()
        view.state.isFormComplete = true

        val declinedByPayriff = PayriffResult.Failed(PayriffError(PayriffErrorCode.PAYMENT_FAILED, "Insufficient funds", isRetryable = true))
        view.controller.pay { _, _, done -> done(declinedByPayriff) }
        assertEquals(listOf<PayriffResult>(declinedByPayriff), view.delivered)
        assertTrue(view.state.isPayEnabled)
        assertNull(view.state.errorMessage(PayriffLanguage.EN))

        val notPayable = PayriffResult.Failed(PayriffError(PayriffErrorCode.ORDER_NOT_PAYABLE, "The order cannot be paid by card: its status is APPROVED."))
        view.controller.pay { _, _, done -> done(notPayable) }
        assertEquals(notPayable, view.delivered.last())
        assertFalse(view.state.isPayEnabled)
        assertEquals("The order cannot be paid by card: its status is APPROVED.", view.state.errorMessage(PayriffLanguage.EN))
    }

    @Test
    fun aLateAnswerForTheOrderSetBeforeIsDropped() = runTest {
        val api = fakeApi()
        api.on("GET", orderPath, http(200, ok(orderPayload()), delayMillis = 5_000))
        api.on(
            "GET",
            "/api/v3/client/orders/o2",
            http(200, ok("""{"orderId":"o2","amount":3,"currency":"USD","status":"CREATED","paymentMethods":["CARD"]}""")),
        )
        val view = Harness(this)
        view.controller.setOrder("o1", CLIENT_SECRET)
        view.controller.appear()
        testScheduler.advanceTimeBy(1_000)
        view.controller.setOrder("o2", CLIENT_SECRET)
        advanceUntilIdle()
        assertEquals("o2", view.state.loadedOrder?.orderId)
        assertEquals("Pay 3.00 USD", view.state.payTitle(PayriffLanguage.EN))
    }

    @Test
    fun aBlankOrderIdOrAMissingConfigurationShowsTheErrorWithoutARequest() = runTest {
        val api = fakeApi()
        val view = Harness(this)
        view.controller.setOrder(" ", CLIENT_SECRET)
        view.controller.appear()
        advanceUntilIdle()
        assertEquals("orderId must not be blank.", view.state.errorMessage(PayriffLanguage.EN))

        Payriff.reset()
        view.controller.setOrder("o1", CLIENT_SECRET)
        advanceUntilIdle()
        assertEquals("Payriff.configure() has not been called.", view.state.errorMessage(PayriffLanguage.EN))
        assertTrue(api.requests.isEmpty())
        assertTrue("configuration errors are not fatal for the order", view.delivered.isEmpty())
    }

    @Test
    fun aResultThatArrivesAfterARecreationOrAnAbandonedPaymentIsHandled() = runTest {
        val api = fakeApi()
        api.on("GET", orderPath, http(200, ok(orderPayload())))
        val view = Harness(this)
        view.controller.setOrder("o1", CLIENT_SECRET)
        view.controller.appear()
        advanceUntilIdle()
        view.state.isFormComplete = true

        // The view left its window before the 3-D Secure sheet: no result comes, Pay works again.
        view.controller.pay { _, _, _ -> }
        assertTrue(view.state.isPaying)
        view.controller.paymentAbandoned()
        assertFalse(view.state.isPaying)
        assertTrue(view.state.isPayEnabled)
        assertTrue(view.delivered.isEmpty())

        // A 3-D Secure result for the recreated activity's view, which never started the payment.
        val pending = PayriffResult.Pending(payment(), com.payriff.android.PayriffPendingReason.NOT_FINAL)
        view.controller.finishPayment(pending)
        assertEquals(listOf<PayriffResult>(pending), view.delivered)
        assertFalse(view.state.isPayEnabled)
        assertTrue(view.changes > 0)
    }

    // endregion

    @Test
    fun twoViewsInOneActivityNeverShareALauncherKey() {
        val activity = Any()
        val other = Any()
        assertEquals("com.payriff.android.PayriffPaymentView:default", PaymentViewKeys.base(-1))
        val base = PaymentViewKeys.base(0x7f0a0042)
        assertEquals("com.payriff.android.PayriffPaymentView:2131361858", base)
        assertEquals(base, PaymentViewKeys.acquire(activity, base))
        assertEquals("$base#2", PaymentViewKeys.acquire(activity, base))
        assertEquals("another registry has its own keys", base, PaymentViewKeys.acquire(other, base))
        PaymentViewKeys.release(activity, base)
        assertEquals("the recreated activity's view gets the same key", base, PaymentViewKeys.acquire(activity, base))
        PaymentViewKeys.release(activity, base)
        PaymentViewKeys.release(activity, "$base#2")
        PaymentViewKeys.release(other, base)
    }

    private fun payableState(): PaymentViewState = PaymentViewState().apply {
        orderDidLoad(order())
        isFormComplete = true
        paymentDidStart()
    }

    private fun order(
        amount: String? = "1.02",
        currency: String? = "AZN",
        status: String = "CREATED",
        methods: List<String> = listOf("CARD"),
    ) = PayriffClientOrder(
        orderId = "o1",
        amount = amount?.let(::BigDecimal),
        currency = currency,
        description = null,
        merchantName = null,
        status = PayriffPaymentStatus.fromRaw(status),
        rawStatus = status,
        language = null,
        paymentMethods = methods,
    )

    private fun payment() = PayriffPayment(
        orderId = "o1",
        status = PayriffPaymentStatus.APPROVED,
        rawStatus = "APPROVED",
        amount = BigDecimal("10.5"),
        currency = "AZN",
        transactionId = null,
    )
}

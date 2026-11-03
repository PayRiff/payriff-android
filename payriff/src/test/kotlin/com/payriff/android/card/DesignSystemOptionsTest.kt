package com.payriff.android.card

import com.payriff.android.Payriff
import com.payriff.android.PayriffCardField
import com.payriff.android.PayriffCardFormStyle
import com.payriff.android.PayriffConfig
import com.payriff.android.PayriffError
import com.payriff.android.PayriffErrorCode
import com.payriff.android.PayriffLanguage
import com.payriff.android.PayriffPaymentMethod
import com.payriff.android.PayriffPaymentViewStyle
import com.payriff.android.PayriffResult
import com.payriff.android.PayriffStrings
import com.payriff.android.PayriffTextStyle
import com.payriff.android.internal.Languages
import com.payriff.android.internal.card.BrandMarks
import com.payriff.android.internal.card.CardFormPlan
import com.payriff.android.internal.card.CardFormStyles
import com.payriff.android.internal.card.CardSheetRequest
import com.payriff.android.internal.card.FormDefaults
import com.payriff.android.internal.card.PaymentViewAsset
import com.payriff.android.internal.card.PaymentViewController
import com.payriff.android.internal.card.PaymentViewPlan
import com.payriff.android.internal.card.ResolvedStrings
import com.payriff.android.internal.wallet.GooglePayTransaction
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.util.zip.Inflater
import kotlin.math.abs

/**
 * The design-system options of CONTRACT §18: the Pay button's title and disabled colours, corner smoothing, badge and
 * brand mark borders, the three payment view callbacks (when, and how often), right-to-left, `Payriff.isSupported`, and
 * that the defaults are unchanged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesignSystemOptionsTest {

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

    // region Styles

    @Test
    fun theNewOptionsAreOffByDefault() {
        with(PayriffPaymentViewStyle()) {
            assertNull(payButtonTitle)
            assertEquals(0f, cornerSmoothing, 0f)
            assertEquals(listOf<Int?>(null, null, null), listOf(buttonDisabledColor, buttonDisabledTextColor, badgeBorderColor))
        }
        with(PayriffCardFormStyle()) {
            assertNull(cornerSmoothing)
            assertNull(brandMarkBorderColor)
        }
        assertNull(PayriffConfig().strings)
        assertEquals(PayriffStrings(), PayriffStrings.Builder().build())
        // The defaults draw what revision 7 drew.
        val style = PayriffPaymentViewStyle()
        assertEquals(0f, PaymentViewPlan.cornerSmoothing(style), 0f)
        assertEquals(0xFFECEEF0.toInt(), PaymentViewPlan.badgeBorderColor(style))
        assertEquals(FormDefaults(), PaymentViewPlan.formDefaults(style))
        val form = CardFormStyles.resolve(PayriffCardFormStyle(), night = false, density = 1f)
        assertEquals(0f, form.cornerSmoothing, 0f)
        assertEquals(0xFFD1D1D6.toInt(), form.brandMarkBorderColor)
        assertEquals(BrandMarks.BADGE_STROKE, form.brandMarkBorderColor)
        assertEquals("Pay 1.02 AZN", PaymentViewPlan.payTitle(ResolvedStrings(PayriffLanguage.EN), order("1.02"), style))
    }

    @Test
    fun theJavaBuildersMatchNamedArguments() {
        val view = PayriffPaymentViewStyle.Builder()
            .payButtonTitle("Pay €12")
            .cornerSmoothing(0.6f)
            .buttonDisabledColor(0xFFE5E5EA.toInt())
            .buttonDisabledTextColor(0xFF8E8E93.toInt())
            .badgeBorderColor(0xFF101010.toInt())
            .build()
        assertEquals(
            PayriffPaymentViewStyle(
                payButtonTitle = "Pay €12",
                cornerSmoothing = 0.6f,
                buttonDisabledColor = 0xFFE5E5EA.toInt(),
                buttonDisabledTextColor = 0xFF8E8E93.toInt(),
                badgeBorderColor = 0xFF101010.toInt(),
            ),
            view,
        )
        assertEquals(view, view.newBuilder().build())
        val form = PayriffCardFormStyle.Builder().cornerSmoothing(1f).brandMarkBorderColor(0xFF00FF00.toInt()).build()
        assertEquals(PayriffCardFormStyle(cornerSmoothing = 1f, brandMarkBorderColor = 0xFF00FF00.toInt()), form)
        val strings = PayriffStrings.Builder()
            .pay("Öde")
            .errorMessage(PayriffErrorCode.NETWORK_ERROR, "Bağlantı yok")
            .errorMessage(PayriffErrorCode.SESSION_EXPIRED, "Süre doldu")
            .errorMessage(PayriffErrorCode.SESSION_EXPIRED, null)
            .build()
        assertEquals(PayriffStrings(pay = "Öde", errorMessages = mapOf(PayriffErrorCode.NETWORK_ERROR to "Bağlantı yok")), strings)
        assertEquals(strings, strings.newBuilder().build())
        val config = PayriffConfig.Builder().publishableKey(PUBLISHABLE_KEY).strings(strings).build()
        assertEquals(strings, config.strings)
        assertEquals(strings, config.newBuilder().build().strings)
        // CONTRACT §11: the description is unchanged; the texts are not part of it.
        assertEquals(PayriffConfig(publishableKey = PUBLISHABLE_KEY).toString(), config.toString())
    }

    @Test
    fun thePayButtonTitleIsTheWholeText() {
        val texts = ResolvedStrings(PayriffLanguage.RU, config = PayriffStrings(pay = "Öde"))
        val title = PayriffPaymentViewStyle(payButtonTitle = "  Pay €12 ", payButtonLabel = "Buy")
        assertEquals("Pay €12", PaymentViewPlan.payTitle(texts, order("23.45"), title))
        assertEquals("while the amount is unknown too", "Pay €12", PaymentViewPlan.payTitle(texts, null, title))
        assertEquals("Buy 23.45 AZN", PaymentViewPlan.payTitle(texts, order("23.45"), PayriffPaymentViewStyle(payButtonLabel = "Buy")))
        assertEquals("Öde 23.45 AZN", PaymentViewPlan.payTitle(texts, order("23.45"), PayriffPaymentViewStyle(payButtonTitle = " ")))
    }

    @Test
    fun aDisabledPayButtonIsDimmedUnlessTheStyleSetsDisabledColours() {
        val brand = 0xFF0B57D0.toInt()
        val white = 0xFFFFFFFF.toInt()
        // Without disabled colours: the enabled look at 50%, as before.
        val plain = PaymentViewPlan.buttonColors(PayriffPaymentViewStyle(buttonColor = brand))
        assertEquals(PaymentViewPlan.ButtonLook(brand, white, 1f), PaymentViewPlan.buttonLook(plain, disabled = false, pressed = false))
        assertEquals(PaymentViewPlan.ButtonLook(brand, white, 0.5f), PaymentViewPlan.buttonLook(plain, disabled = true, pressed = false))
        assertEquals(PaymentViewPlan.ButtonLook(brand, white, 0.8f), PaymentViewPlan.buttonLook(plain, disabled = false, pressed = true))
        assertEquals(PaymentViewPlan.ButtonLook(PaymentViewPlan.BUTTON_COLOR, white, 1f), PaymentViewPlan.buttonLook(PaymentViewPlan.buttonColors(PayriffPaymentViewStyle()), false, false))
        // Either colour set: the disabled button (also while paying) at full opacity, the other colour from the enabled look.
        val grey = 0xFFE5E5EA.toInt()
        val dim = 0xFF8E8E93.toInt()
        val both = PaymentViewPlan.buttonColors(PayriffPaymentViewStyle(buttonColor = brand, buttonDisabledColor = grey, buttonDisabledTextColor = dim))
        assertEquals(PaymentViewPlan.ButtonLook(grey, dim, 1f), PaymentViewPlan.buttonLook(both, disabled = true, pressed = false))
        assertEquals(PaymentViewPlan.ButtonLook(brand, white, 1f), PaymentViewPlan.buttonLook(both, disabled = false, pressed = false))
        val background = PaymentViewPlan.buttonColors(PayriffPaymentViewStyle(buttonColor = brand, buttonDisabledColor = grey))
        assertEquals(PaymentViewPlan.ButtonLook(grey, white, 1f), PaymentViewPlan.buttonLook(background, disabled = true, pressed = false))
        val text = PaymentViewPlan.buttonColors(
            PayriffPaymentViewStyle(buttonColor = brand, buttonTextStyle = PayriffTextStyle(color = 0xFF111111.toInt()), buttonDisabledTextColor = dim),
        )
        assertEquals(PaymentViewPlan.ButtonLook(brand, dim, 1f), PaymentViewPlan.buttonLook(text, disabled = true, pressed = false))
        assertEquals(PaymentViewPlan.ButtonLook(brand, 0xFF111111.toInt(), 1f), PaymentViewPlan.buttonLook(text, disabled = false, pressed = false))
    }

    @Test
    fun cornerSmoothingAndBorderColoursComeFromTheFormThenTheView() {
        val view = PayriffPaymentViewStyle(cornerSmoothing = 0.6f, badgeBorderColor = 0xFF222222.toInt())
        assertEquals(0.6f, PaymentViewPlan.cornerSmoothing(view), 0f)
        assertEquals(0xFF222222.toInt(), PaymentViewPlan.badgeBorderColor(view))
        // Inside the view, the form takes the view's values ...
        val inside = CardFormStyles.resolve(PayriffCardFormStyle(), night = false, density = 1f, defaults = PaymentViewPlan.formDefaults(view))
        assertEquals(0.6f, inside.cornerSmoothing, 0f)
        assertEquals(0xFF222222.toInt(), inside.brandMarkBorderColor)
        // ... unless its own style sets them.
        val own = CardFormStyles.resolve(
            PayriffCardFormStyle(cornerSmoothing = 0f, brandMarkBorderColor = 0xFF333333.toInt()),
            night = true,
            density = 2f,
            defaults = PaymentViewPlan.formDefaults(view),
        )
        assertEquals(0f, own.cornerSmoothing, 0f)
        assertEquals(0xFF333333.toInt(), own.brandMarkBorderColor)
        // Clamped to 0…1.
        assertEquals(1f, PaymentViewPlan.cornerSmoothing(PayriffPaymentViewStyle(cornerSmoothing = 3f)), 0f)
        assertEquals(0f, PaymentViewPlan.cornerSmoothing(PayriffPaymentViewStyle(cornerSmoothing = -1f)), 0f)
        assertEquals(1f, CardFormStyles.resolve(PayriffCardFormStyle(cornerSmoothing = 2f), false, 1f).cornerSmoothing, 0f)
        assertEquals(0f, CardFormStyles.resolve(PayriffCardFormStyle(cornerSmoothing = Float.NaN), false, 1f).cornerSmoothing, 0f)
        // The badges' frames: 1 dp, outer radius 4 dp.
        assertEquals(listOf(1f, 4f), listOf(PaymentViewPlan.BADGE_BORDER_DP, PaymentViewPlan.BADGE_CORNER_RADIUS_DP))
    }

    // endregion

    // region Badges

    @Test
    fun theBadgesAreFramelessMarksAndTheFramedImagesAreGone() {
        val drawables = Class.forName("com.payriff.android.R\$drawable")
        val names = drawables.fields.map { it.name }.toSet()
        assertFalse("payriff_badge_visa" in names)
        assertFalse("payriff_badge_mastercard" in names)
        assertEquals(listOf("payriff_badge_mastercard_mark", "payriff_badge_visa_mark"), PaymentViewAsset.BADGES.map { it.resourceName })
        // The marks are on the old canvas, but nothing is drawn at the edges, where the frame was.
        for (asset in PaymentViewAsset.BADGES) {
            for (folder in listOf("drawable-mdpi", "drawable-hdpi", "drawable-xhdpi", "drawable-xxhdpi")) {
                val png = File(res(), "$folder/${asset.resourceName}.png")
                val alpha = alphaChannel(png)
                val edge = (0 until alpha.width).flatMap { x -> listOf(alpha[x, 0], alpha[x, alpha.height - 1]) } +
                    (0 until alpha.height).flatMap { y -> listOf(alpha[0, y], alpha[alpha.width - 1, y]) }
                assertTrue("$png has no frame", edge.all { it == 0 })
                assertTrue("$png has a mark", (0 until alpha.width).any { x -> (0 until alpha.height).any { y -> alpha[x, y] > 0 } })
            }
        }
    }

    private fun res(): File = listOf(File("src/main/res"), File("payriff/src/main/res")).first { it.isDirectory }

    private class Alpha(val width: Int, val height: Int, private val values: IntArray) {
        operator fun get(x: Int, y: Int): Int = values[y * width + x]
    }

    /** The alpha channel of an 8-bit RGBA, non-interlaced PNG. */
    private fun alphaChannel(file: File): Alpha {
        val input = DataInputStream(file.inputStream().buffered())
        val compressed = ByteArrayOutputStream()
        var width = 0
        var height = 0
        input.use {
            input.skipBytes(8)
            while (true) {
                val length = input.readInt()
                val type = String(ByteArray(4).also(input::readFully), Charsets.US_ASCII)
                val data = ByteArray(length).also(input::readFully)
                input.readInt()
                when (type) {
                    "IHDR" -> DataInputStream(data.inputStream()).run {
                        width = readInt()
                        height = readInt()
                        assertEquals("$file", listOf(8, 6, 0, 0, 0), List(5) { readUnsignedByte() })
                    }
                    "IDAT" -> compressed.write(data)
                    "IEND" -> break
                }
            }
        }
        val stride = width * 4
        val raw = ByteArray(height * (stride + 1))
        Inflater().run {
            setInput(compressed.toByteArray())
            var read = 0
            while (read < raw.size && !finished()) read += inflate(raw, read, raw.size - read)
            end()
        }
        val pixels = ByteArray(height * stride)
        for (y in 0 until height) {
            val filter = raw[y * (stride + 1)].toInt()
            for (i in 0 until stride) {
                val value = raw[y * (stride + 1) + 1 + i].toInt() and 0xFF
                val left = if (i >= 4) pixels[y * stride + i - 4].toInt() and 0xFF else 0
                val up = if (y > 0) pixels[(y - 1) * stride + i].toInt() and 0xFF else 0
                val upLeft = if (y > 0 && i >= 4) pixels[(y - 1) * stride + i - 4].toInt() and 0xFF else 0
                val predicted = when (filter) {
                    0 -> 0
                    1 -> left
                    2 -> up
                    3 -> (left + up) / 2
                    4 -> {
                        val p = left + up - upLeft
                        val (pa, pb, pc) = Triple(abs(p - left), abs(p - up), abs(p - upLeft))
                        if (pa <= pb && pa <= pc) left else if (pb <= pc) up else upLeft
                    }
                    else -> throw AssertionError("$file: unknown PNG filter $filter")
                }
                pixels[y * stride + i] = (value + predicted).toByte()
            }
        }
        return Alpha(width, height, IntArray(width * height) { pixels[it * 4 + 3].toInt() and 0xFF })
    }

    // endregion

    // region Callbacks (CONTRACT §18.5), with the real Payriff.retrieveOrder over a fake Client API

    private class Harness(scope: CoroutineScope, block: () -> PayriffError? = { null }, offer: GooglePayTransaction? = null) {
        val log = mutableListOf<String>()
        var onStarted: (PayriffPaymentMethod) -> Unit = {}
        val controller = PaymentViewController(
            scope = scope,
            loadOrder = { orderId, clientSecret -> Payriff.retrieveOrder(orderId, clientSecret) },
            onStateChanged = {},
            deliver = { log += "result:" + it.javaClass.simpleName },
            deviceBlock = block,
            decideGooglePay = { offer },
            onPaymentStarted = { method ->
                log += "started:$method"
                onStarted(method)
            },
            onOrderLoaded = { log += "loaded:" + it.rawStatus },
            onLoadFailed = { log += "failed:" + it.code },
        )
    }

    private fun TestScope.fakeApi(): FakeTransport = FakeTransport { testScheduler.currentTime }.also { Payriff.transportForTests = it }

    @Test
    fun orderLoadedFollowsEverySuccessfulLoadAndLoadFailedEveryFailedOne() = runTest {
        val api = fakeApi()
        api.on("GET", orderPath, FakeTransport.Reply.NoResponse(), FakeTransport.Reply.NoResponse(), http(200, ok(orderPayload(status = "APPROVED"))))
        val view = Harness(this)
        view.controller.setOrder("o1", CLIENT_SECRET)
        view.controller.appear()
        advanceUntilIdle()
        assertEquals(listOf("failed:NETWORK_ERROR"), view.log)
        // Not fatal: the next appearance loads again, and fails again.
        view.controller.disappear()
        view.controller.appear()
        advanceUntilIdle()
        assertEquals(listOf("failed:NETWORK_ERROR", "failed:NETWORK_ERROR"), view.log)
        // A loaded order is reported even when it cannot be paid by card.
        view.controller.disappear()
        view.controller.appear()
        advanceUntilIdle()
        assertEquals(listOf("failed:NETWORK_ERROR", "failed:NETWORK_ERROR", "loaded:APPROVED"), view.log)
        // A loaded order is not loaded again, so it is not reported again.
        view.controller.disappear()
        view.controller.appear()
        advanceUntilIdle()
        assertEquals(3, view.log.size)
        assertEquals(3, api.requests.size)
    }

    @Test
    fun aFatalLoadErrorGoesToLoadFailedThenOnceToTheResult() = runTest {
        val api = fakeApi()
        api.on("GET", orderPath, http(404, """{"code":"01000","message":"Order not found"}"""))
        val view = Harness(this)
        view.controller.setOrder("o1", CLIENT_SECRET)
        view.controller.appear()
        advanceUntilIdle()
        assertEquals(listOf("failed:SESSION_EXPIRED", "result:Failed"), view.log)
        view.controller.disappear()
        view.controller.appear()
        advanceUntilIdle()
        assertEquals("a fatal order is not loaded again", 2, view.log.size)
    }

    @Test
    fun aCompromisedDeviceIsALoadFailureOncePerOrder() = runTest {
        fakeApi()
        val view = Harness(this, block = { PayriffError(PayriffErrorCode.DEVICE_COMPROMISED, "Card payments are not available on this device.") })
        view.controller.setOrder("o1", CLIENT_SECRET)
        view.controller.appear()
        view.controller.disappear()
        view.controller.appear()
        advanceUntilIdle()
        assertEquals(listOf("failed:DEVICE_COMPROMISED", "result:Failed"), view.log)
        view.controller.disappear()
        view.controller.setOrder("o2", CLIENT_SECRET)
        view.controller.appear()
        advanceUntilIdle()
        assertEquals("another order reports it again", 4, view.log.size)
    }

    @Test
    fun paymentStartedOncePerAttemptBeforeAnythingIsSent() = runTest {
        val api = fakeApi()
        api.on("GET", orderPath, http(200, ok(orderPayload())))
        val view = Harness(this)
        view.controller.setOrder("o1", CLIENT_SECRET)
        view.controller.appear()
        advanceUntilIdle()
        view.log.clear()
        // An incomplete form: Pay is off, nothing starts.
        view.controller.pay { _, _, _ -> view.log += "confirm" }
        assertTrue(view.log.isEmpty())
        view.controller.state.isFormComplete = true
        view.controller.pay { _, _, done ->
            view.log += "confirm"
            done(PayriffResult.Failed(PayriffError(PayriffErrorCode.PAYMENT_FAILED, "Insufficient funds", isRetryable = true)))
        }
        // A retryable failure keeps Pay on: the next tap is a new attempt.
        view.controller.pay { _, _, done ->
            view.log += "confirm"
            done(PayriffResult.Success(com.payriff.android.PayriffPayment("o1", com.payriff.android.PayriffPaymentStatus.APPROVED)))
        }
        view.controller.pay { _, _, _ -> view.log += "confirm" }
        assertEquals(
            listOf("started:CARD", "confirm", "result:Failed", "started:CARD", "confirm", "result:Success"),
            view.log,
        )
    }

    @Test
    fun paymentStartedForGooglePayBeforeItsSheetOpens() = runTest {
        val api = fakeApi()
        api.on("GET", orderPath, http(200, ok(orderPayload())))
        val view = Harness(this, offer = GooglePayTransaction("payriff", "BCR2DN4T", "10.50", "AZN", "AZ", "Test Merchant", null))
        view.controller.setOrder("o1", CLIENT_SECRET)
        view.controller.appear()
        advanceUntilIdle()
        view.log.clear()
        view.controller.payWithGooglePay { _, _, _ -> view.log += "sheet" }
        assertEquals(listOf("started:GOOGLE_PAY", "sheet"), view.log)
        // While it runs, neither Pay nor the Google Pay button starts another attempt.
        view.controller.state.isFormComplete = true
        view.controller.payWithGooglePay { _, _, _ -> view.log += "sheet" }
        view.controller.pay { _, _, _ -> view.log += "confirm" }
        assertEquals(2, view.log.size)
    }

    @Test
    fun aListenerThatSetsAnotherOrderStopsTheAttempt() = runTest {
        val api = fakeApi()
        api.on("GET", orderPath, http(200, ok(orderPayload())))
        api.on("GET", "/api/v3/client/orders/o2", http(200, ok(orderPayload())))
        val view = Harness(this)
        view.controller.setOrder("o1", CLIENT_SECRET)
        view.controller.appear()
        advanceUntilIdle()
        view.controller.state.isFormComplete = true
        view.onStarted = { view.controller.setOrder("o2", CLIENT_SECRET) }
        view.controller.pay { _, _, _ -> view.log += "confirm" }
        assertFalse("confirm" in view.log)
        assertFalse("the view is not left busy", view.controller.state.isPaying)
    }

    // endregion

    // region Right-to-left and isSupported

    @Test
    fun theCardInputsStayLeftToRightAndTheHolderFollowsTheLayout() {
        assertTrue(CardFormPlan.isLeftToRight(PayriffCardField.NUMBER))
        assertTrue(CardFormPlan.isLeftToRight(PayriffCardField.EXPIRY))
        assertTrue(CardFormPlan.isLeftToRight(PayriffCardField.CVV))
        assertFalse(CardFormPlan.isLeftToRight(PayriffCardField.HOLDER))
        // The 3-D Secure sheet follows the screen that opened it, and is right-to-left in Arabic (CONTRACT §19.3).
        fun request(rtl: Boolean?) = CardSheetRequest("f", "o1", CLIENT_SECRET, null, rtl = rtl)
        assertEquals(android.view.View.LAYOUT_DIRECTION_RTL, request(true).layoutDirection(PayriffLanguage.EN))
        assertEquals(android.view.View.LAYOUT_DIRECTION_LTR, request(false).layoutDirection(PayriffLanguage.EN))
        assertEquals(android.view.View.LAYOUT_DIRECTION_INHERIT, request(null).layoutDirection(PayriffLanguage.AZ))
        assertEquals(android.view.View.LAYOUT_DIRECTION_RTL, request(false).layoutDirection(PayriffLanguage.AR))
        assertEquals(android.view.View.LAYOUT_DIRECTION_RTL, request(null).layoutDirection(PayriffLanguage.AR))
        assertTrue(Languages.isRightToLeft(PayriffLanguage.AR))
        assertTrue(PayriffLanguage.entries.filter { Languages.isRightToLeft(it) } == listOf(PayriffLanguage.AR))
        // The request's description holds neither the client secret nor texts.
        val described = CardSheetRequest("f", "o1", "pr_cs_SECRET", null, PayriffStrings(sheetTitle = "TITLE"), true).toString()
        assertFalse(described.contains("SECRET") || described.contains("TITLE"))
    }

    @Test
    fun everyDeviceCanRunThePayments() {
        // CONTRACT §18.8: minSdk 24.
        assertTrue(Payriff.isSupported)
    }

    // endregion

    private fun order(amount: String) = com.payriff.android.PayriffClientOrder(
        orderId = "o1",
        amount = java.math.BigDecimal(amount),
        currency = "AZN",
        description = null,
        merchantName = null,
        status = com.payriff.android.PayriffPaymentStatus.CREATED,
        rawStatus = "CREATED",
        language = null,
        paymentMethods = listOf("CARD"),
    )
}

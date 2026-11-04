package com.payriff.android.wallet

import com.payriff.android.Payriff
import com.payriff.android.PayriffClientOrder
import com.payriff.android.PayriffConfig
import com.payriff.android.PayriffError
import com.payriff.android.PayriffErrorCode
import com.payriff.android.PayriffGooglePayConfig
import com.payriff.android.PayriffPaymentStatus
import com.payriff.android.PayriffPaymentViewStyle
import com.payriff.android.PayriffWalletPlacement
import com.payriff.android.card.CLIENT_SECRET
import com.payriff.android.card.PUBLISHABLE_KEY
import com.payriff.android.internal.card.CardFormStyles
import com.payriff.android.internal.card.PaymentViewController
import com.payriff.android.internal.card.PaymentViewPlan
import com.payriff.android.internal.card.PaymentViewPlan.Item
import com.payriff.android.internal.card.PaymentViewPlan.Part
import com.payriff.android.internal.card.PaymentViewState
import com.payriff.android.internal.wallet.GooglePayAvailability
import com.payriff.android.internal.wallet.GooglePayButtonStyles
import com.payriff.android.internal.wallet.GooglePayViewDecision
import com.payriff.android.testing.HostAppRule
import com.payriff.android.testing.LogCapture
import com.payriff.android.testing.Vectors
import com.payriff.android.testing.Vectors.booleanOrNull
import com.payriff.android.testing.Vectors.objects
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.math.BigDecimal
import kotlin.math.roundToInt

/**
 * `wallet.json` → `viewPlacement`, `viewWalletHeight` and `viewReveal` (CONTRACT §20): the payment view's parts and gaps
 * in both wallet placements and the order of its children, the wallet button's height from Pay's, and when the view
 * applies a loaded order with its Google Pay decision, with a fake order read and a fake `isReadyToPay` in virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WalletViewVectorsTest {

    @get:Rule
    val logs = LogCapture()

    @get:Rule
    val hostApp = HostAppRule()

    private val vectors = Vectors.load("wallet.json")

    @After
    fun tearDown() {
        Payriff.reset()
    }

    @Test
    fun viewPlacementPartsAndGaps() {
        val cases = vectors.getJSONObject("viewPlacement").getJSONArray("cases").objects()
        assertEquals(10, cases.size)
        for (case in cases) {
            val name = case.getString("name")
            val styleJson = case.getJSONObject("style")
            val style = PayriffPaymentViewStyle(
                walletPlacement = placement(styleJson.getString("walletPlacement")),
                showWalletDivider = styleJson.getBoolean("showWalletDivider"),
                showBadges = styleJson.getBoolean("showBadges"),
                showSecureNote = styleJson.getBoolean("showSecureNote"),
            )
            val expected = case.getJSONArray("expect").objects().map { Item(part(it.getString("part")), it.getInt("gapAbove")) }
            val content = PaymentViewPlan.content(style, showsError = case.getBoolean("errorShown"), showsWallet = case.getBoolean("walletShown"))
            assertEquals(name, expected, content)
            // The view keeps its children in this order, so the shown parts are laid out in the plan's order.
            val shown = content.map { it.part }
            assertEquals(name, shown, PaymentViewPlan.parts(style.walletPlacement).filter { it in shown })
        }
        for (placement in PayriffWalletPlacement.entries) {
            assertEquals(placement.name, Part.entries - Part.POWERED_BY, PaymentViewPlan.parts(placement).sorted())
        }
    }

    @Test
    fun viewWalletHeightFollowsPayUnlessTheStyleSetsOne() {
        val section = vectors.getJSONObject("viewWalletHeight")
        assertEquals(section.getDouble("minHeight").toFloat(), GooglePayButtonStyles.MIN_HEIGHT_DP, 0f)
        val cases = section.getJSONArray("cases").objects()
        assertEquals(6, cases.size)
        for (case in cases) {
            val name = case.getString("name")
            val styleHeight = if (case.isNull("styleHeight")) null else case.getDouble("styleHeight").toFloat()
            val payButtonHeight = case.getDouble("payButtonHeight").toFloat()
            val expected = case.getDouble("expect").toFloat()
            assertEquals(name, expected, PaymentViewPlan.walletHeight(styleHeight, payButtonHeight), 0f)
            // In pixels, as the view measures it: Pay's measured height itself when the style has none.
            for (density in listOf(1f, 2.625f, 3.5f)) {
                val payPx = (payButtonHeight * density).roundToInt()
                val expectedPx = if (styleHeight == null && payButtonHeight >= GooglePayButtonStyles.MIN_HEIGHT_DP) payPx else CardFormStyles.px(expected, density)
                assertEquals("$name at $density", expectedPx, PaymentViewPlan.walletHeightPx(styleHeight, payPx, density))
            }
        }
        // Any measured height of Pay, also one no dp value rounds to; a style height that is not a number counts as 0.
        assertEquals(197, PaymentViewPlan.walletHeightPx(null, 197, 2.625f))
        assertEquals(105, PaymentViewPlan.walletHeightPx(Float.NaN, 197, 2.625f))
        // Pay's title stays one line: a long title is truncated, so Pay grows only with the font scale (CONTRACT §20.2).
        assertEquals(1, PaymentViewPlan.BUTTON_TITLE_MAX_LINES)
    }

    @Test
    fun viewRevealAppliesTheOrderAndTheDecisionTogether() {
        val section = vectors.getJSONObject("viewReveal")
        assertEquals(section.getLong("waitMs"), GooglePayAvailability.WAIT_MS)
        val cases = section.getJSONArray("cases").objects()
        assertEquals(8, cases.size)
        for (case in cases) reveal(case)
    }

    /** A state change of the view: when, and whether the order and the Google Pay button show. */
    private data class Change(val time: Long, val orderApplied: Boolean, val walletShown: Boolean)

    /** One `viewReveal` case: the order arrives [ORDER_MS] after the view appeared, the device answers as the case says. */
    private fun reveal(case: JSONObject) = runTest {
        val name = case.getString("name")
        val expect = case.getJSONObject("expect")
        val loadFails = case.getBoolean("loadFails")
        val deviceAnswerMs = if (case.isNull("deviceAnswerMs")) null else case.getLong("deviceAnswerMs")
        val deviceAnswer = case.booleanOrNull("deviceAnswer")
        val googlePay = if (case.getBoolean("walletConfigured")) PayriffGooglePayConfig(gatewayMerchantId = "PRF0001") else null
        Payriff.reset()
        Payriff.configure(PayriffConfig(publishableKey = PUBLISHABLE_KEY, enableLogging = true, googlePay = googlePay))
        logs.lines.clear()
        // Passes CONTRACT §17.3 points 2 and 3 when the case says so; an APPROVED order does not.
        val order = order(status = if (case.getBoolean("orderOffered")) "CREATED" else "APPROVED")

        var deviceAskedAt: Long? = null
        val changes = mutableListOf<Change>()
        val events = mutableListOf<String>()
        lateinit var view: PaymentViewController
        view = PaymentViewController(
            scope = this,
            loadOrder = { _, _ ->
                delay(ORDER_MS)
                if (loadFails) throw PayriffError(PayriffErrorCode.NETWORK_ERROR, "No response from Payriff (offline or timed out).")
                order
            },
            onStateChanged = {
                val state = view.state
                changes += Change(currentTime, state.order !== PaymentViewState.Order.Loading, state.isGooglePayShown)
                events += "state"
            },
            deliver = {},
            startGooglePay = { scope ->
                GooglePayViewDecision(scope, Payriff.config, canPresent = true, appName = "Example Shop") {
                    deviceAskedAt = currentTime
                    if (deviceAnswerMs == null) awaitCancellation()
                    delay(ORDER_MS + deviceAnswerMs)
                    checkNotNull(deviceAnswer)
                }::decide
            },
            onOrderLoaded = { events += "loaded" },
            onLoadFailed = { events += "failed" },
        )
        view.setOrder("o1", CLIENT_SECRET)
        view.appear()
        advanceUntilIdle()
        // Nothing changes later: a slow answer is ignored.
        advanceTimeBy(10_000)
        advanceUntilIdle()

        if (expect.getBoolean("deviceCheckStarted")) {
            assertEquals("$name: the device is asked when the view appears, before the order arrives", 0L, deviceAskedAt)
        } else {
            assertNull("$name: no device check", deviceAskedAt)
        }
        // The new order and its load at 0, then one change that applies the order with the decision.
        val applied = changes.drop(2)
        assertEquals(name, listOf(Change(0, false, false), Change(0, false, false)), changes.take(2))
        assertEquals(
            name,
            listOf(Change(ORDER_MS + expect.getLong("appliedAfterMs"), orderApplied = true, walletShown = expect.getBoolean("walletShown"))),
            applied,
        )
        assertEquals("$name: the callback comes right after the order is applied", listOf("state", "state", "state", if (loadFails) "failed" else "loaded"), events)
        assertEquals(name, expect.getBoolean("walletShown"), view.state.isGooglePayShown)
        assertTrue(name, view.state.isGooglePayDecided || loadFails)

        // The availability line, once per order.
        val decisions = logs.lines.filter { "Google Pay offered" in it }.map { it.substringAfter("[DEBUG] ") }
        val tooSlow = googlePay != null && case.getBoolean("orderOffered") && (deviceAnswerMs == null || deviceAnswerMs > GooglePayAvailability.WAIT_MS)
        when {
            loadFails -> assertEquals(name, emptyList<String>(), decisions)
            tooSlow -> assertEquals(name, listOf("Google Pay offered: false (the device check did not answer in time)"), decisions)
            else -> assertEquals(name, 1, decisions.size)
        }
    }

    private fun order(status: String) = PayriffClientOrder(
        orderId = "o1",
        amount = BigDecimal("10.5"),
        currency = "AZN",
        description = null,
        merchantName = null,
        status = PayriffPaymentStatus.fromRaw(status),
        rawStatus = status,
        language = null,
        paymentMethods = listOf("CARD"),
    )

    private fun placement(name: String): PayriffWalletPlacement = when (name) {
        "top" -> PayriffWalletPlacement.TOP
        "belowPayButton" -> PayriffWalletPlacement.BELOW_PAY_BUTTON
        else -> throw AssertionError("unknown walletPlacement $name")
    }

    private fun part(name: String): Part = when (name) {
        "walletButton" -> Part.WALLET_BUTTON
        "walletDivider" -> Part.WALLET_DIVIDER
        "form" -> Part.FORM
        "payButton" -> Part.PAY_BUTTON
        "error" -> Part.ERROR
        "badges" -> Part.BADGES
        "secureNote" -> Part.SECURE_NOTE
        else -> throw AssertionError("unknown part $name")
    }

    private companion object {
        /** When the fake order read answers, after the view appeared. */
        const val ORDER_MS = 500L
    }
}

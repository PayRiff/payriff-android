package com.payriff.android.card

import com.payriff.android.PayriffCardBrand
import com.payriff.android.internal.card.BrandMarks
import com.payriff.android.internal.card.CardErrorCode
import com.payriff.android.internal.card.CardValidation
import com.payriff.android.internal.card.PaymentViewAsset
import com.payriff.android.testing.Vectors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** mada (CONTRACT §19.2): the BINs, the detection order, mada-only BINs and the brand mark. */
class MadaTest {

    @Test
    fun theBinsArePayriffJsList() {
        val bins = Vectors.load("card_validation.json").getJSONArray("madaBins")
        val expected = (0 until bins.length()).map { bins.getString(it) }
        assertEquals(113, expected.size)
        assertEquals(expected, CardValidation.MADA_BINS)
        // Exact ranges of 6 or 8 digits, for 16-digit cards, checked before Visa and Mastercard.
        assertTrue(CardValidation.MADA_BINS.all { it.length == 6 || it.length == 8 })
        val mada = CardValidation.BRANDS.first()
        assertEquals(PayriffCardBrand.MADA, mada.brand)
        assertEquals(CardValidation.MADA_BINS.map { it to it }, mada.ranges)
        assertEquals(listOf(16), mada.lengths)
        assertEquals(16, CardValidation.MAX_PAN_DIGITS)
    }

    @Test
    fun aCoBadgedCardShowsItsInternationalBrandUntilTheWholeBinIsTyped() {
        // 403024 is a Visa BIN in the mada list: Visa from the first digit, mada from the sixth.
        assertEquals(PayriffCardBrand.VISA, CardValidation.detectBrand("40302"))
        assertEquals(PayriffCardBrand.MADA, CardValidation.detectBrand("403024"))
        // An 8-digit BIN switches at the eighth digit.
        assertEquals(PayriffCardBrand.MASTERCARD, CardValidation.detectBrand("2233790"))
        assertEquals(PayriffCardBrand.MADA, CardValidation.detectBrand("22337902"))
        // Not a mada BIN: the international brand stays.
        assertEquals(PayriffCardBrand.VISA, CardValidation.detectBrand("4242424242424242"))
        val valid = CardValidation.validateNumber("4030 2400 0000 0008")
        assertTrue(valid.valid)
        assertEquals(PayriffCardBrand.MADA, valid.brand)
    }

    @Test
    fun aMadaOnlyBinIsIncompleteWhileItIsTyped() {
        for (prefix in listOf("5043", "5888", "6049", "6051", "6361", "9682")) {
            val check = CardValidation.validateNumber(prefix)
            assertEquals(prefix, CardErrorCode.INCOMPLETE, check.errorCode)
            assertNull(prefix, check.brand)
            assertEquals(prefix, 16, CardValidation.maxPanLength(prefix))
        }
        assertEquals(PayriffCardBrand.MADA, CardValidation.validateNumber("968201").brand)
        // Digits that no accepted brand can start with are still unsupported.
        assertEquals(CardErrorCode.UNSUPPORTED, CardValidation.validateNumber("9999").errorCode)
        assertEquals(CardErrorCode.UNSUPPORTED, CardValidation.validateNumber("6011").errorCode)
    }

    @Test
    fun theMarkIsPayriffJsDrawingInTheUsualBadge() {
        assertEquals("mada", BrandMarks.label(PayriffCardBrand.MADA))
        with(BrandMarks) {
            // Centred at 1:1 in the 36×24 badge: a 32×20 box.
            assertEquals(listOf(32f, 20f), listOf(MADA_BOX_WIDTH, MADA_BOX_HEIGHT))
            assertEquals(listOf(36f, 24f), listOf(BADGE_WIDTH, BADGE_HEIGHT))
            assertEquals(listOf(3f, 5f, 2.5f, 7f, 10.5f), listOf(MADA_BAR_LEFT, MADA_BAR_WIDTH, MADA_BAR_HEIGHT, MADA_BLUE_TOP, MADA_GREEN_TOP))
            assertEquals(0xFF259BD6.toInt(), MADA_BLUE)
            assertEquals(0xFF84B740.toInt(), MADA_GREEN)
            assertEquals("mada", MADA_TEXT)
            assertEquals(listOf(8.5f, 19.5f, 13.5f), listOf(MADA_TEXT_SIZE, MADA_TEXT_X, MADA_TEXT_BASELINE))
            assertEquals(0xFF1F2A44.toInt(), MADA_TEXT_COLOR)
            // The bars and the word fit in the box.
            assertTrue(MADA_BAR_LEFT + MADA_BAR_WIDTH < MADA_TEXT_X && MADA_GREEN_TOP + MADA_BAR_HEIGHT <= MADA_BOX_HEIGHT)
        }
        // The payment view's badge row stays Mastercard and Visa (CONTRACT §19.2: a mada badge waits for the owner).
        assertEquals(listOf(PaymentViewAsset.MASTERCARD, PaymentViewAsset.VISA), PaymentViewAsset.BADGES)
    }
}

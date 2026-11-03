package com.payriff.android.card

import com.payriff.android.PayriffCardField
import com.payriff.android.PayriffLanguage
import com.payriff.android.PayriffRegion
import com.payriff.android.PayriffStrings
import com.payriff.android.internal.Languages
import com.payriff.android.internal.StringKey
import com.payriff.android.internal.card.CardApiClient
import com.payriff.android.internal.card.CardStrings
import com.payriff.android.internal.card.PaymentViewPlan
import com.payriff.android.internal.card.ResolvedStrings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

/** Arabic (CONTRACT §19.3): the effective language, the texts, and an order in Arabic. */
class ArabicTest {

    @Test
    fun theDeviceLanguageFallsBackToArabicInRegionSa() {
        assertEquals(PayriffLanguage.AR, Languages.effective(null, null, "tr-TR", PayriffRegion.SA))
        assertEquals(PayriffLanguage.EN, Languages.effective(null, null, "tr-TR", PayriffRegion.AZ))
        assertEquals(PayriffLanguage.AR, Languages.effective(null, null, "ar-EG", PayriffRegion.AZ))
        // The form's and the configuration's language still win.
        assertEquals(PayriffLanguage.EN, Languages.effective(PayriffLanguage.EN, PayriffLanguage.RU, "ar-SA", PayriffRegion.SA))
        assertEquals(PayriffLanguage.RU, Languages.effective(null, PayriffLanguage.RU, "ar-SA", PayriffRegion.SA))
    }

    @Test
    fun theTextsAreArabicAndTheCardPlaceholdersStayLatin() {
        val texts = ResolvedStrings(PayriffLanguage.AR)
        assertEquals("اسم حامل البطاقة", texts.label(PayriffCardField.HOLDER))
        assertEquals("اسم حامل البطاقة", texts.placeholder(PayriffCardField.HOLDER))
        assertEquals("1234 1234 1234 1234", texts.placeholder(PayriffCardField.NUMBER))
        assertEquals("MM / YY", texts.placeholder(PayriffCardField.EXPIRY))
        assertEquals("CVC", texts.placeholder(PayriffCardField.CVV))
        assertEquals("الدفع", texts.sheet(StringKey.TITLE))
        assertEquals("ar", CardStrings.code(PayriffLanguage.AR))
        // The Pay button keeps the amount's Latin digits; the logo replaces "Payriff" in the footer.
        val order = com.payriff.android.PayriffClientOrder("o1", BigDecimal("10.5"), "SAR", null, null, com.payriff.android.PayriffPaymentStatus.CREATED, "CREATED", null, listOf("CARD"))
        assertEquals("ادفع 10.50 SAR", PaymentViewPlan.payTitle(texts, order, com.payriff.android.PayriffPaymentViewStyle()))
        assertEquals("مدعوم من " to "", PaymentViewPlan.poweredByParts(PayriffLanguage.AR))
        // An app's own texts still win in Arabic.
        assertEquals("Öde", ResolvedStrings(PayriffLanguage.AR, config = PayriffStrings(pay = "Öde")).view(CardStrings.VIEW_PAY))
    }

    @Test
    fun anOrderInArabicIsHonoured() {
        val order = CardApiClient.parseOrder(
            JSONObject("""{"orderId":"o1","amount":10.5,"currency":"SAR","status":"CREATED","language":"AR","paymentMethods":["CARD"]}"""),
            "o1",
        )
        assertEquals(PayriffLanguage.AR, order.language)
    }
}

package com.payriff.android.vectors

import com.payriff.android.PayriffCardField
import com.payriff.android.PayriffClientOrder
import com.payriff.android.PayriffError
import com.payriff.android.PayriffErrorCode
import com.payriff.android.PayriffLanguage
import com.payriff.android.PayriffPaymentStatus
import com.payriff.android.PayriffPaymentViewStyle
import com.payriff.android.PayriffResult
import com.payriff.android.PayriffStrings
import com.payriff.android.internal.MapKeyValues
import com.payriff.android.internal.SheetStrings
import com.payriff.android.internal.StringKey
import com.payriff.android.internal.card.CardErrorCode
import com.payriff.android.internal.card.CardStrings
import com.payriff.android.internal.card.PaymentViewPlan
import com.payriff.android.internal.card.PaymentViewState
import com.payriff.android.internal.card.ResolvedStrings
import com.payriff.android.internal.card.SheetStringsCodec
import com.payriff.android.internal.card.StringField
import com.payriff.android.internal.card.StringSource
import com.payriff.android.testing.Vectors
import com.payriff.android.testing.Vectors.keyNames
import com.payriff.android.testing.Vectors.objects
import com.payriff.android.testing.Vectors.stringOrNull
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import java.math.BigDecimal

/**
 * `strings_override.json` (CONTRACT §18.6): every [PayriffStrings] field replaces its key wherever the SDK shows it,
 * the resolution order, the Pay button text, the `poweredBy` split, and `errorMessages` only on on-screen lines.
 */
class StringsOverrideVectorsTest {

    private val vector = Vectors.load("strings_override.json")

    @Test
    fun everyFieldReplacesItsKey() {
        val fields = vector.getJSONArray("fields").objects()
        assertEquals(39, fields.size)
        // PayriffStrings has exactly these fields, plus errorMessages; so has the internal table.
        val names = fields.map { it.getString("field") }
        val declared = PayriffStrings::class.java.declaredFields.filter { !it.isSynthetic && !Modifier.isStatic(it.modifiers) }
        assertEquals((names + "errorMessages").toSet(), declared.map { it.name }.toSet())
        assertEquals(names, StringField.entries.map { it.fieldName })
        val cardStrings = Vectors.load("card_strings.json").getJSONObject("messages")
        val sheetStrings = Vectors.load("strings.json").getJSONObject("strings")
        for (entry in fields) {
            val name = entry.getString("field")
            val key = entry.getString("key")
            val field = checkNotNull(StringField.named(name)) { name }
            assertEquals(name, entry.getString("source"), field.source.vectorName)
            assertEquals(name, key, field.key)
            // The public API: a Builder setter and a getter of this name (Java sees both).
            val strings = strings(name to "X")
            assertEquals(name, "X", PayriffStrings::class.java.getMethod("get" + name.replaceFirstChar(Char::uppercaseChar)).invoke(strings))
            assertEquals(name, "X", field.value(strings))
            for (language in PayriffLanguage.entries) {
                val code = CardStrings.code(language)
                val builtIn = when (field.source) {
                    StringSource.CARD -> cardStrings.getJSONObject(code).getJSONObject(key.substringBefore('.')).getString(key.substringAfter('.'))
                    StringSource.SHEET -> sheetStrings.getJSONObject(key).getString(code)
                }
                // Android draws no JavaScript dialogs (CONTRACT §10): dialog_ok and dialog_cancel have no built-in text here.
                if (field == StringField.DIALOG_OK || field == StringField.DIALOG_CANCEL) {
                    assertNull(name, field.builtIn(language))
                    continue
                }
                assertEquals("$name/$code built in", builtIn, shown(ResolvedStrings(language), field))
                // The field replaces its key in every language, from the configuration or from the view.
                assertEquals("$name/$code from the config", "X", shown(ResolvedStrings(language, config = strings), field))
                assertEquals("$name/$code from the view", "X", shown(ResolvedStrings(language, view = strings), field))
                // ... and nothing else.
                for (other in StringField.entries - field - StringField.DIALOG_OK - StringField.DIALOG_CANCEL) {
                    assertEquals("$name leaves ${other.fieldName}", other.builtIn(language), ResolvedStrings(language, config = strings).text(other))
                }
            }
        }
    }

    @Test
    fun resolutionOrder() {
        val cases = vector.getJSONArray("resolution").objects()
        assertEquals(6, cases.size)
        for (case in cases) {
            val texts = ResolvedStrings(
                language = PayriffLanguage.valueOf(case.getString("language").uppercase()),
                view = strings(case.getJSONObject("view")),
                config = strings(case.getJSONObject("config")),
            )
            val field = checkNotNull(StringField.named(case.getString("field")))
            assertEquals(case.getString("name"), case.getString("expect"), shown(texts, field))
        }
    }

    @Test
    fun payButtonText() {
        val cases = vector.getJSONArray("payButton").objects()
        assertEquals(6, cases.size)
        for (case in cases) {
            val name = case.getString("name")
            val json = case.getJSONObject("style")
            val style = PayriffPaymentViewStyle(payButtonTitle = json.stringOrNull("payButtonTitle"), payButtonLabel = json.stringOrNull("payButtonLabel"))
            val language = case.stringOrNull("language")?.let { PayriffLanguage.valueOf(it.uppercase()) } ?: PayriffLanguage.EN
            val amount = if (case.isNull("amount")) null else BigDecimal(case.get("amount").toString())
            val order = order(amount, case.stringOrNull("currency"))
            val strings = strings(case.getJSONObject("strings"))
            // The same text with the strings in the configuration or in the view.
            for (texts in listOf(ResolvedStrings(language, config = strings), ResolvedStrings(language, view = strings))) {
                assertEquals(name, case.getString("expect"), PaymentViewPlan.payTitle(texts, order, style))
                val state = PaymentViewState()
                assertEquals(name, PaymentViewPlan.payTitle(texts, null, style), state.payTitle(texts, style))
                state.orderDidLoad(order)
                assertEquals(name, case.getString("expect"), state.payTitle(texts, style))
            }
        }
    }

    @Test
    fun poweredBySplit() {
        val cases = vector.getJSONArray("poweredBy").objects()
        assertEquals(4, cases.size)
        for (case in cases) {
            val expect = case.getJSONObject("expect")
            assertEquals(case.getString("text"), expect.getString("before") to expect.getString("after"), PaymentViewPlan.poweredByParts(case.getString("text")))
        }
        // The built-in texts split as before revision 8.
        for (language in PayriffLanguage.entries) {
            assertEquals(PaymentViewPlan.poweredByParts(language), PaymentViewPlan.poweredByParts(CardStrings.view(language, CardStrings.VIEW_POWERED_BY)))
        }
    }

    @Test
    fun errorMessagesAreOnlyForOnScreenLines() {
        val custom = PayriffStrings(
            errorMessages = mapOf(
                PayriffErrorCode.SESSION_EXPIRED to "Sifarişin vaxtı bitib",
                PayriffErrorCode.NETWORK_ERROR to "İnternet yoxdur",
                PayriffErrorCode.ORDER_NOT_PAYABLE to "Ödənilə bilməz",
                PayriffErrorCode.DEVICE_COMPROMISED to "Bu cihazda olmaz",
                PayriffErrorCode.PAYMENT_FAILED to "   ",
            ),
        )
        val texts = ResolvedStrings(PayriffLanguage.EN, config = custom)
        // The payment view's error line: a failed load, and a payment that ends the order.
        val failed = PaymentViewState()
        val fatal = PayriffError(PayriffErrorCode.SESSION_EXPIRED, "Order not found", isFatal = true)
        val reported = failed.orderDidFail(fatal) as PayriffResult.Failed
        assertEquals("Sifarişin vaxtı bitib", failed.errorMessage(texts))
        assertEquals("the result keeps the developer-facing message", "Order not found", reported.error.message)
        assertEquals("without the app's texts nothing changes", "Order not found", failed.errorMessage(PayriffLanguage.EN))
        val ended = PaymentViewState().apply {
            orderDidLoad(order(BigDecimal.TEN, "AZN"))
            paymentDidStart()
            paymentDidFinish(PayriffResult.Failed(PayriffError(PayriffErrorCode.ORDER_NOT_PAYABLE, "The order cannot be paid (HTTP 400).")))
        }
        assertEquals("Ödənilə bilməz", ended.errorMessage(texts))
        // A line with a text of its own: that text first, then the code's entry.
        val notPayable = PaymentViewState().apply { orderDidLoad(order(BigDecimal.TEN, "AZN", status = "APPROVED")) }
        assertEquals("Ödənilə bilməz", notPayable.errorMessage(texts))
        assertEquals("Not by card", notPayable.errorMessage(ResolvedStrings(PayriffLanguage.EN, view = PayriffStrings(notPayable = "Not by card"), config = custom)))
        val blocked = PaymentViewState().apply { deviceDidBlock(PayriffError(PayriffErrorCode.DEVICE_COMPROMISED, "Card payments are not available on this device.")) }
        assertEquals("Bu cihazda olmaz", blocked.errorMessage(texts))
        // The 3-D Secure sheet's error state is the page that does not load.
        assertEquals("İnternet yoxdur", texts.errorLine(StringField.PAGE_LOAD_ERROR, PayriffErrorCode.NETWORK_ERROR))
        assertEquals(
            "Səhifə açılmadı",
            ResolvedStrings(PayriffLanguage.AZ, PayriffStrings(pageLoadError = "Səhifə açılmadı"), custom).errorLine(StringField.PAGE_LOAD_ERROR, PayriffErrorCode.NETWORK_ERROR),
        )
        assertEquals(
            SheetStrings.get(StringKey.PAGE_LOAD_ERROR, PayriffLanguage.RU),
            ResolvedStrings(PayriffLanguage.RU).errorLine(StringField.PAGE_LOAD_ERROR, PayriffErrorCode.NETWORK_ERROR),
        )
        // A blank entry counts as not set; the view's entry wins over the configuration's.
        assertNull(texts.errorMessage(PayriffErrorCode.PAYMENT_FAILED))
        val viewFirst = ResolvedStrings(PayriffLanguage.EN, PayriffStrings(errorMessages = mapOf(PayriffErrorCode.NETWORK_ERROR to "View")), custom)
        assertEquals("View", viewFirst.errorMessage(PayriffErrorCode.NETWORK_ERROR))
        assertEquals("Sifarişin vaxtı bitib", viewFirst.errorMessage(PayriffErrorCode.SESSION_EXPIRED))
    }

    @Test
    fun theSheetsTextsOfTheViewCrossTheIntent() {
        val view = PayriffStrings(
            sheetTitle = " Ödeme ",
            closeConfirmContinue = "Ödemeye devam et",
            pay = "Öde",
            errorMessages = mapOf(PayriffErrorCode.NETWORK_ERROR to "Bağlantı yok"),
        )
        val store = MapKeyValues()
        SheetStringsCodec.write(view, store)
        assertTrue("only strings cross the Intent boundary", store.map.values.all { it is String })
        val read = checkNotNull(SheetStringsCodec.read(store))
        assertEquals(" Ödeme ", read.sheetTitle)
        assertEquals("Ödemeye devam et", read.closeConfirmContinue)
        assertEquals(mapOf(PayriffErrorCode.NETWORK_ERROR to "Bağlantı yok"), read.errorMessages)
        assertNull("the sheet takes only its own texts", read.pay)
        assertNull(SheetStringsCodec.read(MapKeyValues()))
        // Every text the sheet shows can be replaced.
        assertEquals(StringKey.entries.map { StringField.sheet(it) }.toSet(), SheetStringsCodec.FIELDS.toSet())
    }

    /** What the screen shows for [field] with [texts]: through the helper the form, the view or the sheet uses. */
    private fun shown(texts: ResolvedStrings, field: StringField): String {
        val (group, name) = field.key.substringBefore('.') to field.key.substringAfter('.')
        val cardField = PayriffCardField.entries.firstOrNull { CardStrings.key(it) == group }
        return when {
            field.source == StringSource.SHEET -> texts.sheet(StringKey.entries.single { it.key == field.key })
            group == "label" -> texts.label(PayriffCardField.entries.single { CardStrings.key(it) == name })
            group == "placeholder" -> texts.placeholder(PayriffCardField.entries.single { CardStrings.key(it) == name })
            group == "group" -> texts.cardInformation()
            group == "view" -> texts.view(name)
            cardField != null -> texts.error(cardField, CardErrorCode.entries.single { it.value == name })
            else -> error("no screen shows ${field.key}")
        }
    }

    /** [PayriffStrings] through its Java builder, with [values] by field name. */
    private fun strings(vararg values: Pair<String, String>): PayriffStrings {
        val builder = PayriffStrings.Builder()
        for ((name, value) in values) PayriffStrings.Builder::class.java.getMethod(name, String::class.java).invoke(builder, value)
        return builder.build()
    }

    private fun strings(json: JSONObject): PayriffStrings = strings(*json.keyNames().map { it to json.getString(it) }.toTypedArray())

    private fun order(amount: BigDecimal?, currency: String?, status: String = "CREATED") = PayriffClientOrder(
        orderId = "o1",
        amount = amount,
        currency = currency,
        description = null,
        merchantName = null,
        status = PayriffPaymentStatus.fromRaw(status),
        rawStatus = status,
        language = null,
        paymentMethods = listOf("CARD"),
    )
}

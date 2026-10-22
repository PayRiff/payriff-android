package com.payriff.android.card

import com.payriff.android.PayriffCardBrand
import com.payriff.android.PayriffCardField
import com.payriff.android.PayriffLanguage
import com.payriff.android.internal.card.CardErrorCode
import com.payriff.android.internal.card.CardFormat
import com.payriff.android.internal.card.CardStrings
import com.payriff.android.internal.card.CardValidation
import com.payriff.android.internal.card.FieldUiState
import com.payriff.android.testing.Vectors
import com.payriff.android.testing.Vectors.keyNames
import com.payriff.android.testing.Vectors.objects
import com.payriff.android.testing.Vectors.stringOrNull
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/** `card_validation.json`, `card_format.json` and `card_strings.json` (CONTRACT §16.4): every case. */
class CardValidationVectorsTest {

    private val validation = Vectors.load("card_validation.json")

    private fun brandName(brand: PayriffCardBrand?) = brand?.name?.lowercase(Locale.ROOT)

    private fun codeName(code: CardErrorCode?) = code?.value

    @Test
    fun brandsAndLimits() {
        val brands = validation.getJSONArray("brands").objects()
        assertEquals(brands.size, CardValidation.BRANDS.size)
        for ((json, brand) in brands.zip(CardValidation.BRANDS)) {
            assertEquals(json.getString("brand"), brandName(brand.brand))
            val ranges = json.getJSONArray("ranges")
            assertEquals((0 until ranges.length()).map { ranges.getJSONArray(it).let { r -> r.getString(0) to r.getString(1) } }, brand.ranges)
            val lengths = json.getJSONArray("lengths")
            assertEquals((0 until lengths.length()).map { lengths.getInt(it) }, brand.lengths)
        }
        assertEquals(validation.getInt("maxPanDigits"), CardValidation.MAX_PAN_DIGITS)
        assertEquals(validation.getInt("maxHolderLength"), CardValidation.MAX_HOLDER_LENGTH)
    }

    @Test
    fun number() {
        val cases = validation.getJSONArray("number").objects()
        for (case in cases) {
            val input = case.getString("input")
            val result = CardValidation.validateNumber(input)
            assertEquals("valid of '$input'", case.getBoolean("valid"), result.valid)
            assertEquals("empty of '$input'", case.getBoolean("empty"), result.empty)
            assertEquals("errorCode of '$input'", case.stringOrNull("errorCode"), codeName(result.errorCode))
            assertEquals("brand of '$input'", case.stringOrNull("brand"), brandName(result.brand))
            assertEquals("value of '$input'", case.getString("value"), result.value)
        }
        assertEquals(26, cases.size)
    }

    @Test
    fun detectBrand() {
        val cases = validation.getJSONArray("detectBrand").objects()
        for (case in cases) {
            val digits = case.getString("digits")
            assertEquals("brand of '$digits'", case.stringOrNull("brand"), brandName(CardValidation.detectBrand(digits)))
            assertEquals("maxLength of '$digits'", case.getInt("maxLength"), CardValidation.maxPanLength(digits))
        }
        assertEquals(16, cases.size)
    }

    @Test
    fun expiry() {
        val expiry = validation.getJSONObject("expiry")
        val now = expiry.getJSONObject("now")
        val cases = expiry.getJSONArray("cases").objects()
        for (case in cases) {
            val input = case.getString("input")
            val result = CardValidation.validateExpiry(input, now.getInt("year"), now.getInt("month"))
            assertEquals("valid of '$input'", case.getBoolean("valid"), result.valid)
            assertEquals("empty of '$input'", case.getBoolean("empty"), result.empty)
            assertEquals("errorCode of '$input'", case.stringOrNull("errorCode"), codeName(result.errorCode))
            val value = case.opt("value") as? JSONObject
            if (value == null) {
                assertNull("value of '$input'", result.value)
            } else {
                assertEquals(value.getString("expiryMonth"), result.value?.month)
                assertEquals(value.getString("expiryYear"), result.value?.year)
            }
        }
        assertEquals(17, cases.size)
    }

    @Test
    fun cvvAndHolder() {
        for ((section, validate) in listOf<Pair<String, (String) -> com.payriff.android.internal.card.FieldCheck>>(
            "cvv" to { CardValidation.validateCvv(it) },
            "holder" to { CardValidation.validateHolder(it) },
        )) {
            for (case in validation.getJSONArray(section).objects()) {
                val input = case.getString("input")
                val result = validate(input)
                assertEquals("$section valid of '$input'", case.getBoolean("valid"), result.valid)
                assertEquals("$section empty of '$input'", case.getBoolean("empty"), result.empty)
                assertEquals("$section errorCode of '$input'", case.stringOrNull("errorCode"), codeName(result.errorCode))
                assertEquals("$section value of '$input'", case.getString("value"), result.value)
            }
        }
        assertEquals(7, validation.getJSONArray("cvv").length())
        assertEquals(11, validation.getJSONArray("holder").length())
    }

    @Test
    fun visibleError() {
        val cases = validation.getJSONArray("visibleError").objects()
        for (case in cases) {
            val code = case.stringOrNull("errorCode")?.let { name -> CardErrorCode.entries.single { it.value == name } }
            val ui = FieldUiState(case.getBoolean("focused"), case.getBoolean("touched"), case.getBoolean("submitted"))
            assertEquals("visibleError of $case", case.stringOrNull("expected"), codeName(CardValidation.visibleError(code, ui)))
        }
        assertEquals(48, cases.size)
    }

    @Test
    fun luhn() {
        for (case in validation.getJSONArray("luhn").objects()) {
            assertEquals(case.getString("digits"), case.getBoolean("valid"), CardValidation.luhn(case.getString("digits")))
        }
    }

    @Test
    fun formatVectors() {
        val format = Vectors.load("card_format.json")
        for (case in format.getJSONArray("number").objects()) {
            assertEquals(case.getString("input"), case.getString("expected"), CardFormat.formatNumber(case.getString("input")))
        }
        for (case in format.getJSONArray("expiry").objects()) {
            val actual = CardFormat.formatExpiry(case.getString("input"), case.getBoolean("inserting"))
            assertEquals("${case.getString("input")} inserting=${case.getBoolean("inserting")}", case.getString("expected"), actual)
        }
        for (case in format.getJSONArray("cvv").objects()) {
            assertEquals(case.getString("input"), case.getString("expected"), CardFormat.formatCvv(case.getString("input")))
        }
        // Locale-independent (CONTRACT §16.4): a device-locale upper-casing gives `İ` on Azerbaijani and Turkish devices.
        val saved = Locale.getDefault()
        try {
            for (locale in listOf(saved, Locale.forLanguageTag("az-AZ"), Locale.forLanguageTag("tr-TR"))) {
                Locale.setDefault(locale)
                for (case in format.getJSONArray("holder").objects()) {
                    val input = case.getString("input")
                    assertEquals("'$input' on $locale", case.getString("expected"), CardFormat.formatHolder(input))
                }
            }
        } finally {
            Locale.setDefault(saved)
        }
        assertEquals(listOf(11, 13, 5, 9), listOf("number", "expiry", "cvv", "holder").map { format.getJSONArray(it).length() })
    }

    @Test
    fun caretStaysAfterTheSameDigits() {
        // "4242 4242" with the caret after the 5th digit; typing reformatted the text.
        assertEquals(6, CardFormat.caretAfterReformat("42424242", 5, "4242 4242"))
        assertEquals(9, CardFormat.caretAfterReformat("42424242", 8, "4242 4242"))
        assertEquals(0, CardFormat.caretAfterReformat("4242", 0, "4242"))
    }

    @Test
    fun holderCaretStaysUnlessUpperCasingChangesTheLength() {
        assertEquals(2, CardFormat.caretAfterHolder("maria", 2, CardFormat.formatHolder("maria")))
        assertEquals(0, CardFormat.caretAfterHolder("maria", 0, "MARIA"))
        // "ß" → "SS": the caret goes to the end.
        assertEquals("STRASSE", CardFormat.formatHolder("straße"))
        assertEquals(7, CardFormat.caretAfterHolder("straße", 3, "STRASSE"))
    }

    @Test
    fun stringTableEqualsCardStringsJson() {
        val messages = Vectors.load("card_strings.json").getJSONObject("messages")
        assertEquals(messages.keyNames(), CardStrings.messages.keys)
        for (language in messages.keyNames()) {
            val groups = messages.getJSONObject(language)
            assertEquals(groups.keyNames(), CardStrings.messages.getValue(language).keys)
            for (group in groups.keyNames()) {
                val entries = groups.getJSONObject(group)
                val table = CardStrings.messages.getValue(language).getValue(group)
                assertEquals("$language.$group", entries.keyNames(), table.keys)
                for (key in entries.keyNames()) assertEquals("$language.$group.$key", entries.getString(key), table.getValue(key))
            }
        }
        // An error code without its own message falls back to the field's "invalid" message.
        assertEquals("Cardholder name is invalid", CardStrings.error(PayriffLanguage.EN, PayriffCardField.HOLDER, CardErrorCode.INCOMPLETE))
        assertEquals("Срок действия карты истёк", CardStrings.error(PayriffLanguage.RU, PayriffCardField.EXPIRY, CardErrorCode.EXPIRED))
        // The hosted payment page's copy: the same placeholders in every language, and the card box's group label.
        for (language in PayriffLanguage.entries) {
            assertEquals("1234 1234 1234 1234", CardStrings.placeholder(language, PayriffCardField.NUMBER))
            assertEquals("MM / YY", CardStrings.placeholder(language, PayriffCardField.EXPIRY))
            assertEquals("CVC", CardStrings.placeholder(language, PayriffCardField.CVV))
        }
        assertEquals("Card holder", CardStrings.label(PayriffLanguage.EN, PayriffCardField.HOLDER))
        assertEquals("Card information", CardStrings.cardInformation(PayriffLanguage.EN))
        assertEquals("Kart məlumatları", CardStrings.cardInformation(PayriffLanguage.AZ))
        assertEquals("Информация о карте", CardStrings.cardInformation(PayriffLanguage.RU))
        assertEquals("Security code", CardStrings.label(PayriffLanguage.EN, PayriffCardField.CVV))
    }
}

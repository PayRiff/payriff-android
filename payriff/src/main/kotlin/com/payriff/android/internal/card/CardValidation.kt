package com.payriff.android.internal.card

import com.payriff.android.PayriffCardBrand

/** Error codes of the card fields (CONTRACT §16.4, `card_validation.json`). */
internal enum class CardErrorCode(val value: String) {
    REQUIRED("required"),
    INCOMPLETE("incomplete"),
    INVALID("invalid"),
    UNSUPPORTED("unsupported"),
    EXPIRED("expired"),
}

/**
 * Result of validating one field. [value] is the normalised value: digits for number and CVV, the collapsed name
 * for the holder; meaningful only when [valid]. Never logged.
 */
internal class FieldCheck(
    val valid: Boolean,
    val empty: Boolean,
    val errorCode: CardErrorCode?,
    val brand: PayriffCardBrand? = null,
    val value: String = "",
)

/** A valid expiry: two-digit month and four-digit year. */
internal class Expiry(val month: String, val year: String)

/** Expiry validation with its value. */
internal class ExpiryCheck(val valid: Boolean, val empty: Boolean, val errorCode: CardErrorCode?, val value: Expiry?)

/** What a field's UI knows when deciding whether to show its error (CONTRACT §16.4 `visibleError`). */
internal class FieldUiState(val focused: Boolean, val touched: Boolean, val submitted: Boolean)

/**
 * Card field validation, ported 1:1 from the web SDK (`fields/validation.js`, Payriff.js 1.2.4). Pure functions.
 * Prefix ranges are compared as equal-length digit strings, which decides "could still become this brand" from a
 * partial number. One deliberate difference (CONTRACT §16.4, owner decision 2026-10-21): card numbers have at most
 * 16 digits for every brand, so Visa's 19-digit length is not accepted (the web SDK allows it).
 */
internal object CardValidation {

    class Brand(val brand: PayriffCardBrand, val ranges: List<Pair<String, String>>, val lengths: List<Int>)

    val BRANDS: List<Brand> = listOf(
        Brand(PayriffCardBrand.VISA, listOf("4" to "4"), listOf(16)),
        Brand(PayriffCardBrand.MASTERCARD, listOf("51" to "55", "2221" to "2720"), listOf(16)),
    )

    /** The most digits a card number may have, whatever its brand (CONTRACT §16.4). */
    const val MAX_PAN_DIGITS = 16
    const val MAX_HOLDER_LENGTH = 50
    const val CVV_LENGTH = 3
    private const val EXPIRY_DIGITS = 4
    private const val MONTHS_PER_YEAR = 12
    private const val MAX_YEARS_AHEAD = 20
    private const val CENTURY = 2000
    private const val LAST_CONTROL = 0x1F

    /** The ASCII digits of [raw], in order (JavaScript `\D` removes everything else, including non-ASCII digits). */
    fun digitsOnly(raw: CharSequence?): String {
        if (raw == null) return ""
        val out = StringBuilder(raw.length)
        for (c in raw) if (c in '0'..'9') out.append(c)
        return out.toString()
    }

    fun luhn(digits: String): Boolean {
        var sum = 0
        var double = false
        for (i in digits.length - 1 downTo 0) {
            var d = digits[i] - '0'
            if (double) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
            double = !double
        }
        return digits.isNotEmpty() && sum % 10 == 0
    }

    private fun prefixMatch(digits: String, lo: String, hi: String, full: Boolean): Boolean {
        if (full && digits.length < lo.length) return false
        val n = minOf(digits.length, lo.length)
        val p = digits.substring(0, n)
        return p >= lo.substring(0, n) && p <= hi.substring(0, n)
    }

    /** The brand once enough digits are typed to be certain, else `null`. */
    fun detectBrand(digits: String): PayriffCardBrand? =
        BRANDS.firstOrNull { b -> b.ranges.any { (lo, hi) -> prefixMatch(digits, lo, hi, full = true) } }?.brand

    private fun candidateBrands(digits: String): List<Brand> =
        BRANDS.filter { b -> b.ranges.any { (lo, hi) -> prefixMatch(digits, lo, hi, full = false) } }

    fun maxPanLength(digits: String): Int {
        val candidates = candidateBrands(digits)
        return if (candidates.isEmpty()) MAX_PAN_DIGITS else candidates.flatMap { it.lengths }.max()
    }

    fun validateNumber(raw: CharSequence?): FieldCheck {
        val digits = digitsOnly(raw)
        if (digits.isEmpty()) return FieldCheck(valid = false, empty = true, errorCode = CardErrorCode.REQUIRED)
        val brand = detectBrand(digits)
        val candidates = candidateBrands(digits)
        if (candidates.isEmpty()) return FieldCheck(false, false, CardErrorCode.UNSUPPORTED, null, digits)
        val lengths = if (brand != null) BRANDS.first { it.brand == brand }.lengths else candidates.flatMap { it.lengths }
        if (digits.length > lengths.max()) return FieldCheck(false, false, CardErrorCode.INVALID, brand, digits)
        if (brand == null || digits.length !in lengths) return FieldCheck(false, false, CardErrorCode.INCOMPLETE, brand, digits)
        if (!luhn(digits)) return FieldCheck(false, false, CardErrorCode.INVALID, brand, digits)
        return FieldCheck(true, false, null, brand, digits)
    }

    /**
     * Accepts `MM/YY` (any separator, or none). The card is valid through the last day of its expiry month, and at
     * most 20 years ahead. [nowYear] and [nowMonth] (1–12) come from the device clock.
     */
    fun validateExpiry(raw: CharSequence?, nowYear: Int, nowMonth: Int): ExpiryCheck {
        val digits = digitsOnly(raw)
        if (digits.isEmpty()) return ExpiryCheck(false, true, CardErrorCode.REQUIRED, null)
        if (digits.length < EXPIRY_DIGITS) return ExpiryCheck(false, false, CardErrorCode.INCOMPLETE, null)
        if (digits.length > EXPIRY_DIGITS) return ExpiryCheck(false, false, CardErrorCode.INVALID, null)
        val month = digits.substring(0, 2).toInt()
        val year = CENTURY + digits.substring(2, 4).toInt()
        if (month < 1 || month > MONTHS_PER_YEAR) return ExpiryCheck(false, false, CardErrorCode.INVALID, null)
        val nowIndex = nowYear * MONTHS_PER_YEAR + (nowMonth - 1)
        val expIndex = year * MONTHS_PER_YEAR + (month - 1)
        if (expIndex < nowIndex) return ExpiryCheck(false, false, CardErrorCode.EXPIRED, null)
        if (expIndex > nowIndex + MAX_YEARS_AHEAD * MONTHS_PER_YEAR) return ExpiryCheck(false, false, CardErrorCode.INVALID, null)
        return ExpiryCheck(true, false, null, Expiry(month.toString().padStart(2, '0'), year.toString()))
    }

    fun validateCvv(raw: CharSequence?): FieldCheck {
        val digits = digitsOnly(raw)
        if (digits.isEmpty()) return FieldCheck(false, true, CardErrorCode.REQUIRED)
        if (digits.length < CVV_LENGTH) return FieldCheck(false, false, CardErrorCode.INCOMPLETE, value = digits)
        if (digits.length > CVV_LENGTH) return FieldCheck(false, false, CardErrorCode.INVALID, value = digits)
        return FieldCheck(true, false, null, value = digits)
    }

    /** JavaScript `String(raw).trim().replace(/\s+/g, ' ')`. */
    fun normalizeHolder(raw: CharSequence?): String {
        val text = raw?.toString().orEmpty()
        val out = StringBuilder(text.length)
        var pendingSpace = false
        for (c in text) {
            if (isJsWhitespace(c)) {
                pendingSpace = out.isNotEmpty()
            } else {
                if (pendingSpace) out.append(' ')
                pendingSpace = false
                out.append(c)
            }
        }
        return out.toString()
    }

    fun validateHolder(raw: CharSequence?): FieldCheck {
        val name = normalizeHolder(raw)
        if (name.isEmpty()) return FieldCheck(false, true, CardErrorCode.REQUIRED)
        if (name.length > MAX_HOLDER_LENGTH || name.any { it.code <= LAST_CONTROL || it == '<' || it == '>' }) {
            return FieldCheck(false, false, CardErrorCode.INVALID, value = name)
        }
        return FieldCheck(true, false, null, value = name)
    }

    /**
     * Which error the customer should see now. Definite errors show at once; `incomplete` after the field lost focus
     * once or after a submit; `required` only after a submit; never `incomplete`/`required` while typing in the field.
     */
    fun visibleError(code: CardErrorCode?, ui: FieldUiState): CardErrorCode? {
        if (code == null) return null
        if (code == CardErrorCode.INVALID || code == CardErrorCode.UNSUPPORTED || code == CardErrorCode.EXPIRED) return code
        if (ui.focused && !ui.submitted) return null
        if (code == CardErrorCode.REQUIRED) return if (ui.submitted) code else null
        return if (ui.touched || ui.submitted) code else null
    }

    /** The characters JavaScript's `\s` and `trim()` treat as white space (code points, so no BOM appears in source). */
    private fun isJsWhitespace(c: Char): Boolean = c.code in JS_WHITESPACE || c.code in EN_QUAD..HAIR_SPACE

    private const val EN_QUAD = 0x2000
    private const val HAIR_SPACE = 0x200A
    private val JS_WHITESPACE = setOf(
        0x20, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0xA0, 0x1680, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF,
    )
}

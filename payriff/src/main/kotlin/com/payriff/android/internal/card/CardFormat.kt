package com.payriff.android.internal.card

/**
 * Display formatting of the card fields, ported 1:1 from the web SDK (`fields/format.js`). Formatting never changes
 * the validated value of number, expiry and CVV: the validators strip non-digits themselves. The holder is the
 * exception (CONTRACT §16.4): its upper-cased text is what is validated and sent.
 */
internal object CardFormat {

    private const val GROUP = 4
    private const val EXPIRY_DIGITS = 4

    /**
     * Groups of four digits separated by spaces, cut at the longest length the typed prefix allows: at most 16 digits
     * (CONTRACT §16.4), so typing stops there.
     */
    fun formatNumber(raw: CharSequence?): String {
        val all = CardValidation.digitsOnly(raw)
        val digits = all.take(CardValidation.maxPanLength(all))
        val out = StringBuilder(digits.length + digits.length / GROUP)
        for ((i, c) in digits.withIndex()) {
            if (i > 0 && i % GROUP == 0) out.append(' ')
            out.append(c)
        }
        return out.toString()
    }

    /**
     * `MM / YY`. [inserting] is `true` while typing and `false` while deleting, so backspace can remove the
     * separator: a single digit above 1 becomes `0d / `, two digits get the separator only while typing.
     */
    fun formatExpiry(raw: CharSequence?, inserting: Boolean): String {
        val d = CardValidation.digitsOnly(raw).take(EXPIRY_DIGITS)
        if (d.length == 1 && d > "1" && inserting) return "0$d / "
        if (d.length == 2 && inserting) return "$d / "
        if (d.length > 2) return "${d.substring(0, 2)} / ${d.substring(2)}"
        return d
    }

    fun formatCvv(raw: CharSequence?): String = CardValidation.digitsOnly(raw).take(CardValidation.CVV_LENGTH)

    /**
     * The cardholder name in upper case, like on the card (CONTRACT §16.4); nothing else changes. `String.uppercase()`
     * is the locale-independent Unicode mapping (`i` → `I`, `ı` → `I`, `ə` → `Ə`). Never use the device locale here:
     * on an Azerbaijani or Turkish device it turns `i` into `İ`.
     */
    fun formatHolder(raw: CharSequence?): String = raw?.toString().orEmpty().uppercase()

    /**
     * Where the caret goes after [formatHolder]: where it was, or at the end when upper-casing changed the length
     * (`ß` → `SS`).
     */
    fun caretAfterHolder(old: CharSequence, caret: Int, formatted: String): Int =
        if (formatted.length == old.length) caret.coerceIn(0, formatted.length) else formatted.length

    /**
     * Where the caret goes after reformatting (web `reformat`): at the end when it was at the end, otherwise after
     * the same number of digits as before.
     */
    fun caretAfterReformat(old: CharSequence, caret: Int, formatted: String): Int {
        val atEnd = caret >= old.length
        if (atEnd) return formatted.length
        val digitsBefore = CardValidation.digitsOnly(old.subSequence(0, caret.coerceIn(0, old.length))).length
        var pos = 0
        var seen = 0
        while (pos < formatted.length && seen < digitsBefore) {
            if (formatted[pos] in '0'..'9') seen++
            pos++
        }
        return pos
    }
}

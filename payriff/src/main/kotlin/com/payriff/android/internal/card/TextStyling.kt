package com.payriff.android.internal.card

import android.graphics.Typeface
import android.os.Build
import android.util.TypedValue
import android.widget.TextView
import com.payriff.android.PayriffTextStyle

/** Applies [text] (size in sp, weight, font and colour) to this view. */
internal fun TextView.applyTextStyle(text: ResolvedTextStyle) {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, text.size)
    typeface = typefaceOf(text)
    setTextColor(text.color)
}

/** The typeface of [text]; before Android 9 the weight is approximated as regular, medium or bold. */
internal fun typefaceOf(text: ResolvedTextStyle): Typeface {
    val base = text.typeface
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return Typeface.create(base ?: Typeface.DEFAULT, text.weight, false)
    return when {
        base != null -> Typeface.create(base, if (text.weight >= PayriffTextStyle.SEMIBOLD) Typeface.BOLD else Typeface.NORMAL)
        text.weight >= PayriffTextStyle.BOLD -> Typeface.DEFAULT_BOLD
        text.weight >= PayriffTextStyle.MEDIUM -> Typeface.create(MEDIUM_FAMILY, Typeface.NORMAL)
        else -> Typeface.DEFAULT
    }
}

private const val MEDIUM_FAMILY = "sans-serif-medium"

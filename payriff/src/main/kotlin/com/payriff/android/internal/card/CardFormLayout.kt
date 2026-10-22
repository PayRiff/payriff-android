package com.payriff.android.internal.card

import android.graphics.Typeface
import androidx.annotation.ColorInt
import com.payriff.android.PayriffCardField
import com.payriff.android.PayriffCardFormLayout
import com.payriff.android.PayriffCardFormStyle
import com.payriff.android.PayriffLanguage
import com.payriff.android.PayriffTextStyle
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * What [com.payriff.android.PayriffCardForm] shows and in which order (CONTRACT §16.4). Pure Kotlin, so the JVM unit
 * tests check the structure the form is built from.
 */
internal object CardFormPlan {

    /** What a section's label says. */
    enum class Label { HOLDER, CARD_INFORMATION, NUMBER, EXPIRY, CVV }

    /**
     * One outlined box: its label above it, its [rows] of fields top to bottom (the fields of a row side by side,
     * separated by a line, as are the rows), and one error line below it.
     */
    class Section(val label: Label, val rows: List<List<PayriffCardField>>) {
        val fields: List<PayriffCardField> = rows.flatten()

        override fun toString(): String = "Section($label, $rows)"
    }

    /**
     * The default of [com.payriff.android.PayriffCardForm.showHolder] (CONTRACT §16.4): the cardholder name is shown,
     * as on Payriff's hosted payment page. Integrators hide it with `showHolder = false`; the name is then sent as
     * `Unknown`.
     */
    const val DEFAULT_SHOW_HOLDER: Boolean = true

    private val holder = Section(Label.HOLDER, listOf(listOf(PayriffCardField.HOLDER)))

    /**
     * The form top to bottom. Each line holds one section, or several side by side (the expiry and CVV of the stacked
     * layout). The cardholder name comes first in the grouped layout and last in the stacked one.
     */
    fun lines(layout: PayriffCardFormLayout, showHolder: Boolean): List<List<Section>> = when (layout) {
        PayriffCardFormLayout.GROUPED -> listOfNotNull(
            listOf(holder).takeIf { showHolder },
            listOf(
                Section(
                    Label.CARD_INFORMATION,
                    listOf(listOf(PayriffCardField.NUMBER), listOf(PayriffCardField.EXPIRY, PayriffCardField.CVV)),
                ),
            ),
        )
        PayriffCardFormLayout.STACKED -> listOfNotNull(
            listOf(Section(Label.NUMBER, listOf(listOf(PayriffCardField.NUMBER)))),
            listOf(
                Section(Label.EXPIRY, listOf(listOf(PayriffCardField.EXPIRY))),
                Section(Label.CVV, listOf(listOf(PayriffCardField.CVV))),
            ),
            listOf(holder).takeIf { showHolder },
        )
    }

    /** The fields in the order they appear, which the keyboard's Next button follows; the last one gets Done. */
    fun focusOrder(lines: List<List<Section>>): List<PayriffCardField> = lines.flatten().flatMap { it.fields }

    /**
     * The one error line of [section]: the first of its fields, in order, that shows an error (number → expiry → CVC
     * in the card box), or `null`.
     */
    fun <E : Any> errorLine(section: Section, visibleError: (PayriffCardField) -> E?): Pair<PayriffCardField, E>? {
        for (field in section.fields) {
            val error = visibleError(field) ?: continue
            return field to error
        }
        return null
    }

    /** The text of [label]: the style's own label, or the copy of `card_strings.json` in [language]. */
    fun labelText(label: Label, style: PayriffCardFormStyle, language: PayriffLanguage): String = when (label) {
        Label.HOLDER -> style.holderLabel ?: CardStrings.label(language, PayriffCardField.HOLDER)
        Label.CARD_INFORMATION -> style.cardLabel ?: CardStrings.cardInformation(language)
        Label.NUMBER -> CardStrings.label(language, PayriffCardField.NUMBER)
        Label.EXPIRY -> CardStrings.label(language, PayriffCardField.EXPIRY)
        Label.CVV -> CardStrings.label(language, PayriffCardField.CVV)
    }

    /** Whether the visible label of [section] names its only field (`labelFor`). */
    fun labelNamesField(section: Section, style: PayriffCardFormStyle): Boolean = style.showLabels && section.fields.size == 1

    /**
     * What accessibility services call [field] of [section], or `null` when its visible label already names it. The
     * fields of the card box are named by `label.number`, `label.expiry` and `label.cvv`; a field alone in its section by
     * the section's label.
     */
    fun accessibilityLabel(section: Section, field: PayriffCardField, style: PayriffCardFormStyle, language: PayriffLanguage): String? =
        when {
            labelNamesField(section, style) -> null
            section.fields.size == 1 -> labelText(section.label, style, language)
            else -> CardStrings.label(language, field)
        }
}

/** A [PayriffTextStyle] merged over its default: every property decided. */
internal class ResolvedTextStyle(val size: Float, val weight: Int, val typeface: Typeface?, @ColorInt val color: Int)

/** [PayriffCardFormStyle] with every default filled in for one theme and screen density, sizes in pixels. */
internal class ResolvedCardFormStyle(
    val layout: PayriffCardFormLayout,
    val showLabels: Boolean,
    val label: ResolvedTextStyle,
    val input: ResolvedTextStyle,
    val error: ResolvedTextStyle,
    @ColorInt val placeholderColor: Int,
    @ColorInt val fieldBackgroundColor: Int,
    @ColorInt val borderColor: Int,
    @ColorInt val focusedBorderColor: Int,
    @ColorInt val errorColor: Int,
    val borderWidthPx: Int,
    val cornerRadiusPx: Float,
    val rowHeightPx: Int,
    val holderHeightPx: Int,
    val horizontalPaddingPx: Int,
    val labelSpacingPx: Int,
    val sectionSpacingPx: Int,
) {
    /** A section's outline: the error colour while it shows an error, else the focus colour while it has the focus. */
    @ColorInt
    fun outlineColor(showsError: Boolean, focused: Boolean): Int = when {
        showsError -> errorColor
        focused -> focusedBorderColor
        else -> borderColor
    }

    /**
     * The minimum height of [field]'s input inside its box. A row's height includes its outline, as on the hosted page
     * (CSS `border-box`), and the rows of a box share the line between them: the holder field is [holderHeightPx] tall,
     * and the card box with two rows is `2 × rowHeightPx − borderWidthPx`.
     */
    fun cellHeightPx(field: PayriffCardField): Int =
        ((if (field == PayriffCardField.HOLDER) holderHeightPx else rowHeightPx) - 2 * borderWidthPx).coerceAtLeast(0)
}

/**
 * The defaults of CONTRACT §16.4 with the page values of §16.14 (the card form of pay.payriff.com), and how a
 * [PayriffCardFormStyle] is resolved.
 */
internal object CardFormStyles {

    /** Labels. */
    const val TEXT_LIGHT = 0xFF34363A.toInt()
    const val TEXT_DARK = 0xFFE1E4E8.toInt()

    /** What the customer types. */
    const val INPUT_LIGHT = 0xFF4D4F54.toInt()
    const val INPUT_DARK = 0xFF8E8E93.toInt()
    const val PLACEHOLDER_LIGHT = 0xFF76787F.toInt()
    const val PLACEHOLDER_DARK = 0xFF5F6167.toInt()
    const val BORDER_LIGHT = 0xFFECEEF0.toInt()
    const val BORDER_DARK = 0xFF3F4145.toInt()
    const val ERROR_LIGHT = 0xFFEC4D3D.toInt()
    const val ERROR_DARK = 0xFFEC5545.toInt()

    /** Transparent: the fields show the page behind the form. */
    const val FIELD_BACKGROUND = 0x00000000

    /** Payriff's brand colour, the focused outline in both themes. */
    const val FOCUS = 0xFF4DBDC6.toInt()

    /** The text sizes in sp: labels 14 semibold, typed text and placeholders 14 regular, error lines 13 regular. */
    const val LABEL_TEXT_SIZE = 14f
    const val INPUT_TEXT_SIZE = 14f
    const val ERROR_TEXT_SIZE = 13f

    /**
     * Fills in the defaults of [style] for the light or dark ([night]) theme; [density] converts dp to pixels. Each text
     * style is merged over its default. Negative sizes count as 0.
     */
    fun resolve(style: PayriffCardFormStyle, night: Boolean, density: Float): ResolvedCardFormStyle {
        val error = style.errorColor ?: if (night) ERROR_DARK else ERROR_LIGHT
        return ResolvedCardFormStyle(
            layout = style.layout,
            showLabels = style.showLabels,
            label = resolveText(
                style.labelTextStyle,
                ResolvedTextStyle(LABEL_TEXT_SIZE, PayriffTextStyle.SEMIBOLD, null, if (night) TEXT_DARK else TEXT_LIGHT),
            ),
            input = resolveText(
                style.inputTextStyle,
                ResolvedTextStyle(INPUT_TEXT_SIZE, PayriffTextStyle.REGULAR, null, if (night) INPUT_DARK else INPUT_LIGHT),
            ),
            error = resolveText(style.errorTextStyle, ResolvedTextStyle(ERROR_TEXT_SIZE, PayriffTextStyle.REGULAR, null, error)),
            placeholderColor = style.placeholderColor ?: if (night) PLACEHOLDER_DARK else PLACEHOLDER_LIGHT,
            fieldBackgroundColor = style.fieldBackgroundColor ?: FIELD_BACKGROUND,
            borderColor = style.borderColor ?: if (night) BORDER_DARK else BORDER_LIGHT,
            focusedBorderColor = style.focusedBorderColor ?: FOCUS,
            errorColor = error,
            // A border thinner than a pixel still shows as one pixel.
            borderWidthPx = if (size(style.borderWidth) > 0f) max(1, px(style.borderWidth, density)) else 0,
            cornerRadiusPx = size(style.cornerRadius) * density,
            rowHeightPx = px(style.rowHeight, density),
            holderHeightPx = px(style.holderHeight, density),
            horizontalPaddingPx = px(style.horizontalPadding, density),
            labelSpacingPx = px(style.labelSpacing, density),
            sectionSpacingPx = px(style.sectionSpacing, density),
        )
    }

    /**
     * [style] merged over [default]: what [style] leaves out (`null`) keeps the default. A size below 1 sp counts as
     * 1 sp, and one that is not a number keeps the default; weights are kept within 1…1000.
     */
    fun resolveText(style: PayriffTextStyle, default: ResolvedTextStyle): ResolvedTextStyle {
        val size = style.size ?: default.size
        return ResolvedTextStyle(
            size = if (size.isFinite()) size.coerceAtLeast(1f) else default.size,
            weight = (style.weight ?: default.weight).coerceIn(1, 1000),
            typeface = style.typeface ?: default.typeface,
            color = style.color ?: default.color,
        )
    }

    /** [dp] in pixels; negative or not-a-number counts as 0. */
    fun px(dp: Float, density: Float): Int = (size(dp) * density).roundToInt()

    /** A size in dp; negative or not-a-number counts as 0. */
    fun size(dp: Float): Float = if (dp.isFinite()) dp.coerceAtLeast(0f) else 0f
}

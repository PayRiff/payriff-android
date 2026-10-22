package com.payriff.android

import android.graphics.Typeface
import androidx.annotation.ColorInt

/** How [PayriffCardForm] arranges its fields (CONTRACT §16.4). */
public enum class PayriffCardFormLayout {
    /**
     * The default, as on Payriff's hosted payment page: the cardholder name first (when shown), then one "Card
     * information" box with the card number on top and expiry | CVC below it. One error line under each section.
     */
    GROUPED,

    /** Every field separate, with its own label above it and its own error below it. */
    STACKED,
}

/**
 * A text style of [PayriffCardFormStyle].
 *
 * @property size Text size in sp.
 * @property weight Font weight from 1 to 1000, such as [REGULAR], [MEDIUM], [SEMIBOLD] or [BOLD]. Android 9 and later
 *   draw any weight; older versions use regular, medium (system font only) or bold.
 * @property color Text colour; `null` uses the form's default for this text (see [PayriffCardFormStyle]).
 * @property typeface The font, for example one from your resources; `null` uses the system font.
 */
public data class PayriffTextStyle @JvmOverloads constructor(
    public val size: Float = 16f,
    public val weight: Int = REGULAR,
    @ColorInt public val color: Int? = null,
    public val typeface: Typeface? = null,
) {
    public companion object {
        /** Font weight 400. */
        public const val REGULAR: Int = 400

        /** Font weight 500. */
        public const val MEDIUM: Int = 500

        /** Font weight 600. */
        public const val SEMIBOLD: Int = 600

        /** Font weight 700. */
        public const val BOLD: Int = 700
    }
}

/**
 * The look of [PayriffCardForm] (CONTRACT §16.4, with the page values of §16.14). Every property is optional: the
 * defaults reproduce the card form of Payriff's hosted payment page and follow the system light or dark theme, unless
 * you set a colour.
 *
 * ```
 * cardForm.style = PayriffCardFormStyle(
 *     cornerRadius = 16f,
 *     focusedBorderColor = brandColor,
 *     labelTextStyle = PayriffTextStyle(size = 13f, weight = PayriffTextStyle.BOLD),
 * )
 * ```
 *
 * Sizes are in dp and text sizes in sp. Java uses [Builder].
 *
 * @property layout [PayriffCardFormLayout.GROUPED] (default) or [PayriffCardFormLayout.STACKED].
 * @property labelTextStyle The labels above the fields: 14 sp semibold in `#34363A` (`#E1E4E8` in the dark theme).
 * @property inputTextStyle What the customer types, and the placeholders' size: 14 sp regular in `#4D4F54` (`#8E8E93` in
 *   the dark theme).
 * @property placeholderColor The placeholders (`1234 1234 1234 1234`, `MM / YY`, `CVC`); `null` is `#76787F` (`#5F6167`
 *   in the dark theme).
 * @property errorTextStyle The error lines: 13 sp regular in [errorColor].
 * @property showLabels Shows the labels above the fields. Defaults to `true`. Without them, accessibility services still
 *   read each field's label.
 * @property holderLabel The label of the cardholder name; `null` uses the form's language ("Card holder").
 * @property cardLabel The label of the card box in the grouped layout; `null` uses the form's language ("Card
 *   information").
 * @property fieldBackgroundColor The inside of the fields; `null` is transparent, so the fields show the background
 *   behind the form.
 * @property borderColor Outlines and the lines between the fields of a box; `null` is `#ECEEF0` (`#3F4145` in the dark
 *   theme).
 * @property focusedBorderColor The outline while one of its fields has the focus; `null` is Payriff's brand colour
 *   `#4DBDC6`.
 * @property errorColor The outline of a section that shows an error, an invalid field's text and, by default, the error
 *   lines; `null` is `#EC4D3D` (`#EC5545` in the dark theme).
 * @property borderWidth Outline and separator width in dp. Defaults to 1.
 * @property cornerRadius Corner radius of the outlines in dp. Defaults to 12.
 * @property rowHeight Height of a card field row in dp, its outline included (rows of a box share the line between
 *   them). Defaults to 40, so the card box with two rows is 79 dp tall. Larger text makes a row taller.
 * @property holderHeight Height of the cardholder name field in dp, its outline included. Defaults to 46.
 * @property horizontalPadding Space between a field's outline and its text in dp. Defaults to 14.
 * @property labelSpacing Space between a label and its field in dp. Defaults to 10.
 * @property sectionSpacing Space between the sections, such as the cardholder name and the card box, in dp. Defaults to
 *   16.
 */
public data class PayriffCardFormStyle(
    public val layout: PayriffCardFormLayout = PayriffCardFormLayout.GROUPED,
    public val labelTextStyle: PayriffTextStyle = PayriffTextStyle(size = 14f, weight = PayriffTextStyle.SEMIBOLD),
    public val inputTextStyle: PayriffTextStyle = PayriffTextStyle(size = 14f),
    @ColorInt public val placeholderColor: Int? = null,
    public val errorTextStyle: PayriffTextStyle = PayriffTextStyle(size = 13f),
    public val showLabels: Boolean = true,
    public val holderLabel: String? = null,
    public val cardLabel: String? = null,
    @ColorInt public val fieldBackgroundColor: Int? = null,
    @ColorInt public val borderColor: Int? = null,
    @ColorInt public val focusedBorderColor: Int? = null,
    @ColorInt public val errorColor: Int? = null,
    public val borderWidth: Float = 1f,
    public val cornerRadius: Float = 12f,
    public val rowHeight: Float = 40f,
    public val holderHeight: Float = 46f,
    public val horizontalPadding: Float = 14f,
    public val labelSpacing: Float = 10f,
    public val sectionSpacing: Float = 16f,
) {

    /** Returns a [Builder] pre-filled with this style. */
    public fun newBuilder(): Builder = Builder(this)

    /** Java-friendly builder for [PayriffCardFormStyle]. Every setter mirrors the property of the same name. */
    public class Builder internal constructor(private var style: PayriffCardFormStyle) {

        /** Starts from the default style. */
        public constructor() : this(PayriffCardFormStyle())

        /** Sets [PayriffCardFormStyle.layout]. */
        public fun layout(value: PayriffCardFormLayout): Builder = apply { style = style.copy(layout = value) }

        /** Sets [PayriffCardFormStyle.labelTextStyle]. */
        public fun labelTextStyle(value: PayriffTextStyle): Builder = apply { style = style.copy(labelTextStyle = value) }

        /** Sets [PayriffCardFormStyle.inputTextStyle]. */
        public fun inputTextStyle(value: PayriffTextStyle): Builder = apply { style = style.copy(inputTextStyle = value) }

        /** Sets [PayriffCardFormStyle.placeholderColor]. */
        public fun placeholderColor(@ColorInt value: Int?): Builder = apply { style = style.copy(placeholderColor = value) }

        /** Sets [PayriffCardFormStyle.errorTextStyle]. */
        public fun errorTextStyle(value: PayriffTextStyle): Builder = apply { style = style.copy(errorTextStyle = value) }

        /** Sets [PayriffCardFormStyle.showLabels]. */
        public fun showLabels(value: Boolean): Builder = apply { style = style.copy(showLabels = value) }

        /** Sets [PayriffCardFormStyle.holderLabel]. */
        public fun holderLabel(value: String?): Builder = apply { style = style.copy(holderLabel = value) }

        /** Sets [PayriffCardFormStyle.cardLabel]. */
        public fun cardLabel(value: String?): Builder = apply { style = style.copy(cardLabel = value) }

        /** Sets [PayriffCardFormStyle.fieldBackgroundColor]. */
        public fun fieldBackgroundColor(@ColorInt value: Int?): Builder = apply { style = style.copy(fieldBackgroundColor = value) }

        /** Sets [PayriffCardFormStyle.borderColor]. */
        public fun borderColor(@ColorInt value: Int?): Builder = apply { style = style.copy(borderColor = value) }

        /** Sets [PayriffCardFormStyle.focusedBorderColor]. */
        public fun focusedBorderColor(@ColorInt value: Int?): Builder = apply { style = style.copy(focusedBorderColor = value) }

        /** Sets [PayriffCardFormStyle.errorColor]. */
        public fun errorColor(@ColorInt value: Int?): Builder = apply { style = style.copy(errorColor = value) }

        /** Sets [PayriffCardFormStyle.borderWidth], in dp. */
        public fun borderWidth(value: Float): Builder = apply { style = style.copy(borderWidth = value) }

        /** Sets [PayriffCardFormStyle.cornerRadius], in dp. */
        public fun cornerRadius(value: Float): Builder = apply { style = style.copy(cornerRadius = value) }

        /** Sets [PayriffCardFormStyle.rowHeight], in dp. */
        public fun rowHeight(value: Float): Builder = apply { style = style.copy(rowHeight = value) }

        /** Sets [PayriffCardFormStyle.holderHeight], in dp. */
        public fun holderHeight(value: Float): Builder = apply { style = style.copy(holderHeight = value) }

        /** Sets [PayriffCardFormStyle.horizontalPadding], in dp. */
        public fun horizontalPadding(value: Float): Builder = apply { style = style.copy(horizontalPadding = value) }

        /** Sets [PayriffCardFormStyle.labelSpacing], in dp. */
        public fun labelSpacing(value: Float): Builder = apply { style = style.copy(labelSpacing = value) }

        /** Sets [PayriffCardFormStyle.sectionSpacing], in dp. */
        public fun sectionSpacing(value: Float): Builder = apply { style = style.copy(sectionSpacing = value) }

        /** Creates the style. */
        public fun build(): PayriffCardFormStyle = style
    }
}

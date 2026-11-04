package com.payriff.android

/** What Google's Pay button says next to the Google Pay mark (CONTRACT §17.10). Google localizes the text. */
public enum class PayriffGooglePayButtonType {
    /** The Google Pay mark alone. The default. */
    PLAIN,

    /** "Buy with" and the mark. */
    BUY,

    /** "Pay with" and the mark. */
    PAY,

    /** "Check out with" and the mark. */
    CHECKOUT,

    /** "Book with" and the mark. */
    BOOK,

    /** "Donate with" and the mark. */
    DONATE,

    /** "Order with" and the mark. */
    ORDER,

    /** "Subscribe with" and the mark. */
    SUBSCRIBE,
}

/** The colours of Google's Pay button (CONTRACT §17.10). */
public enum class PayriffGooglePayButtonTheme {
    /**
     * A dark button on a light page and a light button on a dark page, from the night mode of the view's context. The
     * default.
     */
    AUTOMATIC,

    /** The dark button, for light pages. */
    DARK,

    /** The light button, for dark pages. */
    LIGHT,
}

/**
 * The look of the Google Pay button of [PayriffPaymentView] ([PayriffPaymentViewStyle.googlePayButton]) and of
 * [PayriffGooglePayButton] (CONTRACT §17.10). The button is always Google's official one
 * (`com.google.android.gms.wallet.button.PayButton`): Google's brand rules allow nothing else to draw the Google Pay
 * mark, so these are its only options.
 *
 * ```
 * PayriffGooglePayButtonStyle(type = PayriffGooglePayButtonType.BUY, theme = PayriffGooglePayButtonTheme.DARK)
 * ```
 *
 * Sizes are in dp. Java uses [Builder].
 *
 * @property type What the button says next to the mark. Defaults to [PayriffGooglePayButtonType.PLAIN], the mark alone.
 * @property theme Dark or light. Defaults to [PayriffGooglePayButtonTheme.AUTOMATIC], which follows the night mode.
 * @property cornerRadius Corner radius in dp; a negative value counts as 0. `null` (the default) uses the payment view's
 *   [PayriffPaymentViewStyle.buttonCornerRadius] (16) in [PayriffPaymentView], and 16 in [PayriffGooglePayButton].
 * @property height Height in dp, at least 40: a lower value is raised to 40. `null` (the default) is, in
 *   [PayriffPaymentView], exactly the Pay button's height as laid out: [PayriffPaymentViewStyle.buttonHeight] (44), or
 *   more at large text sizes (CONTRACT §20.2); 44 in [PayriffGooglePayButton].
 */
public data class PayriffGooglePayButtonStyle(
    public val type: PayriffGooglePayButtonType = PayriffGooglePayButtonType.PLAIN,
    public val theme: PayriffGooglePayButtonTheme = PayriffGooglePayButtonTheme.AUTOMATIC,
    public val cornerRadius: Float? = null,
    public val height: Float? = null,
) {

    /** Returns a [Builder] pre-filled with this style. */
    public fun newBuilder(): Builder = Builder(this)

    /** Java-friendly builder for [PayriffGooglePayButtonStyle]. Every setter mirrors the property of the same name. */
    public class Builder internal constructor(private var style: PayriffGooglePayButtonStyle) {

        /** Starts from the default style. */
        public constructor() : this(PayriffGooglePayButtonStyle())

        /** Sets [PayriffGooglePayButtonStyle.type]. */
        public fun type(value: PayriffGooglePayButtonType): Builder = apply { style = style.copy(type = value) }

        /** Sets [PayriffGooglePayButtonStyle.theme]. */
        public fun theme(value: PayriffGooglePayButtonTheme): Builder = apply { style = style.copy(theme = value) }

        /** Sets [PayriffGooglePayButtonStyle.cornerRadius], in dp; `null` uses the default. */
        public fun cornerRadius(value: Float?): Builder = apply { style = style.copy(cornerRadius = value) }

        /** Sets [PayriffGooglePayButtonStyle.height], in dp; `null` uses the default. */
        public fun height(value: Float?): Builder = apply { style = style.copy(height = value) }

        /** Creates the style. */
        public fun build(): PayriffGooglePayButtonStyle = style
    }
}

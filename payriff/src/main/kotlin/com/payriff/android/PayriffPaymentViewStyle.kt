package com.payriff.android

import androidx.annotation.ColorInt

/**
 * Where [PayriffPaymentView] shows the wallet button, Google Pay, while it is offered (CONTRACT §20.1,
 * [PayriffPaymentViewStyle.walletPlacement]). Without a wallet button both placements show the card screen alone.
 */
public enum class PayriffWalletPlacement {
    /**
     * Above the card form, the default: the wallet button, the "Or pay by card" divider 16 dp below it (unless
     * [PayriffPaymentViewStyle.showWalletDivider] is `false`), then the form 16 dp below.
     */
    TOP,

    /**
     * Under the Pay button: the form and Pay first, the wallet button 12 dp below Pay, then the error line, the badges
     * and the secure line. There is no divider, so [PayriffPaymentViewStyle.showWalletDivider] and
     * [PayriffPaymentViewStyle.walletDividerText] have no effect.
     */
    BELOW_PAY_BUTTON,
}

/**
 * The look of [PayriffPaymentView] (CONTRACT §16.14). Every property is optional: the defaults reproduce the card
 * payment part of Payriff's hosted payment page (pay.payriff.com) and follow the system light or dark theme, unless you
 * set a colour.
 *
 * ```
 * paymentView.style = PayriffPaymentViewStyle(
 *     buttonColor = brandColor,
 *     buttonCornerRadius = 8f,
 *     payButtonLabel = "Pay now", // "Pay now 1.02 AZN"
 *     showPoweredBy = false,
 *     formStyle = PayriffCardFormStyle(focusedBorderColor = brandColor),
 * )
 * ```
 *
 * Sizes are in dp and text sizes in sp. Java uses [Builder].
 *
 * @property backgroundColor The page behind the form and the button; `null` is `#FCFCFD` (`#222224` in the dark theme).
 * @property padding Space around the content on every side, in dp. Defaults to 20. The view adds the system bars and
 *   the keyboard from the window insets it receives.
 * @property buttonColor The Pay button; `null` is Payriff's brand colour `#4DBDC6`.
 * @property buttonTextStyle The Pay button's label ("Pay 1.02 AZN"), merged over the default: 16 sp semibold, white.
 *   The spinner shown while paying uses its colour.
 * @property buttonHeight Height of the Pay button in dp; it grows with large text, while its title stays one line.
 *   Defaults to 44.
 * @property buttonCornerRadius Corner radius of the Pay button in dp. Defaults to 16.
 * @property payButtonLabel The word of the Pay button, in place of "Pay" in the view's language; it is shown as it is in
 *   every language. The amount and the currency still follow it ("Pay now 1.02 AZN"), and it shows alone while the
 *   amount is unknown. `null` (the default) or a blank text keeps "Pay", "Ödə" or "Оплатить" ([PayriffStrings.pay] when
 *   set). [payButtonTitle] wins over it.
 * @property secondaryTextColor The secure line and "Powered by"; `null` is `#76787F`.
 * @property showBadges Shows the Mastercard and Visa badges below the button. Defaults to `true`.
 * @property showSecureNote Shows the lock and "Proceed securely by PCI DSS". Defaults to `true`.
 * @property secureNoteText The text next to the lock, in place of "Proceed securely by PCI DSS" in the view's language;
 *   it is shown as it is in every language. `null` (the default) or a blank text keeps the localized text.
 * @property showPoweredBy Shows "Powered by" and the Payriff logo at the bottom. Defaults to `true`. In the dark theme the
 *   logo's wordmark is light (`#E1E4E8`).
 * @property formStyle The card form inside the view ([PayriffCardForm.style]); for example
 *   `PayriffCardFormStyle(showLabels = false)` hides "Card holder" and "Card information". Its error colour and error
 *   text style are also used for the view's error line under the Pay button, and its border colour for the lines of the
 *   wallet divider.
 * @property googlePayButton The Google Pay button, when Google Pay is offered ([PayriffConfig.googlePay], CONTRACT
 *   §17.9): Google's official button with its type, theme, corner radius and height. Without its own height it is
 *   exactly as high as the Pay button as laid out, also at large text sizes (CONTRACT §20.2); without its own corner
 *   radius it takes [buttonCornerRadius]. At least 40 dp high.
 * @property walletDividerText The text of the divider between the wallet button and the form, in place of "Or pay by
 *   card" in the view's language, trimmed. `null` (the default) or a blank text keeps the localized text.
 * @property showWalletDivider Shows the divider ("Or pay by card" between two lines) under the wallet button. Defaults to
 *   `true`. Without a wallet button, or with [walletPlacement] [PayriffWalletPlacement.BELOW_PAY_BUTTON], there is never
 *   a divider.
 * @property payButtonTitle The Pay button's whole text in every state, trimmed, for example "Pay €12": no amount or
 *   currency is added, even while the amount is unknown, and the spinner still replaces it while paying. It wins over
 *   [payButtonLabel] and [PayriffStrings.pay]. `null` (the default) or a blank text keeps the word and the amount.
 *   Google Pay's sheet and the bank's 3-D Secure page still show the order's real amount; showing the customer what is
 *   really charged is then up to you.
 * @property cornerSmoothing Smooths the corners of the Pay button, the badges and, unless
 *   [PayriffCardFormStyle.cornerSmoothing] is set, the form's outlines and brand mark box, from 0 (circular corners, the
 *   default) to 1, as Figma's corner smoothing (CONTRACT §18.2). Values outside 0…1 are clamped. Google's Pay button
 *   keeps its own shape.
 * @property buttonDisabledColor The disabled Pay button's background. With it or [buttonDisabledTextColor] set, the
 *   disabled button (also while paying) is drawn at full opacity in these colours, with [buttonColor] and the title's
 *   colour for the one left out; with neither (the default) it is dimmed to 50%.
 * @property buttonDisabledTextColor The disabled Pay button's title and spinner; see [buttonDisabledColor].
 * @property badgeBorderColor The frames of the Mastercard and Visa badges; `null` is `#ECEEF0`. It also colours the
 *   border of the brand mark box in the card number field, unless [PayriffCardFormStyle.brandMarkBorderColor] is set.
 * @property walletPlacement Where the Google Pay button goes while it is offered (CONTRACT §20.1): above the form
 *   ([PayriffWalletPlacement.TOP], the default) or 12 dp below the Pay button, without the divider
 *   ([PayriffWalletPlacement.BELOW_PAY_BUTTON]).
 */
public data class PayriffPaymentViewStyle(
    @ColorInt public val backgroundColor: Int? = null,
    public val padding: Float = 20f,
    @ColorInt public val buttonColor: Int? = null,
    public val buttonTextStyle: PayriffTextStyle = PayriffTextStyle(),
    public val buttonHeight: Float = 44f,
    public val buttonCornerRadius: Float = 16f,
    public val payButtonLabel: String? = null,
    @ColorInt public val secondaryTextColor: Int? = null,
    public val showBadges: Boolean = true,
    public val showSecureNote: Boolean = true,
    public val secureNoteText: String? = null,
    public val showPoweredBy: Boolean = true,
    public val formStyle: PayriffCardFormStyle = PayriffCardFormStyle(),
    public val googlePayButton: PayriffGooglePayButtonStyle = PayriffGooglePayButtonStyle(),
    public val walletDividerText: String? = null,
    public val showWalletDivider: Boolean = true,
    public val payButtonTitle: String? = null,
    public val cornerSmoothing: Float = 0f,
    @ColorInt public val buttonDisabledColor: Int? = null,
    @ColorInt public val buttonDisabledTextColor: Int? = null,
    @ColorInt public val badgeBorderColor: Int? = null,
    public val walletPlacement: PayriffWalletPlacement = PayriffWalletPlacement.TOP,
) {

    /** Returns a [Builder] pre-filled with this style. */
    public fun newBuilder(): Builder = Builder(this)

    /** Java-friendly builder for [PayriffPaymentViewStyle]. Every setter mirrors the property of the same name. */
    public class Builder internal constructor(private var style: PayriffPaymentViewStyle) {

        /** Starts from the default style. */
        public constructor() : this(PayriffPaymentViewStyle())

        /** Sets [PayriffPaymentViewStyle.backgroundColor]. */
        public fun backgroundColor(@ColorInt value: Int?): Builder = apply { style = style.copy(backgroundColor = value) }

        /** Sets [PayriffPaymentViewStyle.padding], in dp. */
        public fun padding(value: Float): Builder = apply { style = style.copy(padding = value) }

        /** Sets [PayriffPaymentViewStyle.buttonColor]. */
        public fun buttonColor(@ColorInt value: Int?): Builder = apply { style = style.copy(buttonColor = value) }

        /** Sets [PayriffPaymentViewStyle.buttonTextStyle]. */
        public fun buttonTextStyle(value: PayriffTextStyle): Builder = apply { style = style.copy(buttonTextStyle = value) }

        /** Sets [PayriffPaymentViewStyle.buttonHeight], in dp. */
        public fun buttonHeight(value: Float): Builder = apply { style = style.copy(buttonHeight = value) }

        /** Sets [PayriffPaymentViewStyle.buttonCornerRadius], in dp. */
        public fun buttonCornerRadius(value: Float): Builder = apply { style = style.copy(buttonCornerRadius = value) }

        /** Sets [PayriffPaymentViewStyle.payButtonLabel]. */
        public fun payButtonLabel(value: String?): Builder = apply { style = style.copy(payButtonLabel = value) }

        /** Sets [PayriffPaymentViewStyle.secondaryTextColor]. */
        public fun secondaryTextColor(@ColorInt value: Int?): Builder = apply { style = style.copy(secondaryTextColor = value) }

        /** Sets [PayriffPaymentViewStyle.showBadges]. */
        public fun showBadges(value: Boolean): Builder = apply { style = style.copy(showBadges = value) }

        /** Sets [PayriffPaymentViewStyle.showSecureNote]. */
        public fun showSecureNote(value: Boolean): Builder = apply { style = style.copy(showSecureNote = value) }

        /** Sets [PayriffPaymentViewStyle.secureNoteText]. */
        public fun secureNoteText(value: String?): Builder = apply { style = style.copy(secureNoteText = value) }

        /** Sets [PayriffPaymentViewStyle.showPoweredBy]. */
        public fun showPoweredBy(value: Boolean): Builder = apply { style = style.copy(showPoweredBy = value) }

        /** Sets [PayriffPaymentViewStyle.formStyle]. */
        public fun formStyle(value: PayriffCardFormStyle): Builder = apply { style = style.copy(formStyle = value) }

        /** Sets [PayriffPaymentViewStyle.googlePayButton]. */
        public fun googlePayButton(value: PayriffGooglePayButtonStyle): Builder = apply { style = style.copy(googlePayButton = value) }

        /** Sets [PayriffPaymentViewStyle.walletDividerText]. */
        public fun walletDividerText(value: String?): Builder = apply { style = style.copy(walletDividerText = value) }

        /** Sets [PayriffPaymentViewStyle.showWalletDivider]. */
        public fun showWalletDivider(value: Boolean): Builder = apply { style = style.copy(showWalletDivider = value) }

        /** Sets [PayriffPaymentViewStyle.payButtonTitle]. */
        public fun payButtonTitle(value: String?): Builder = apply { style = style.copy(payButtonTitle = value) }

        /** Sets [PayriffPaymentViewStyle.cornerSmoothing], from 0 to 1. */
        public fun cornerSmoothing(value: Float): Builder = apply { style = style.copy(cornerSmoothing = value) }

        /** Sets [PayriffPaymentViewStyle.buttonDisabledColor]. */
        public fun buttonDisabledColor(@ColorInt value: Int?): Builder = apply { style = style.copy(buttonDisabledColor = value) }

        /** Sets [PayriffPaymentViewStyle.buttonDisabledTextColor]. */
        public fun buttonDisabledTextColor(@ColorInt value: Int?): Builder = apply { style = style.copy(buttonDisabledTextColor = value) }

        /** Sets [PayriffPaymentViewStyle.badgeBorderColor]. */
        public fun badgeBorderColor(@ColorInt value: Int?): Builder = apply { style = style.copy(badgeBorderColor = value) }

        /** Sets [PayriffPaymentViewStyle.walletPlacement]. */
        public fun walletPlacement(value: PayriffWalletPlacement): Builder = apply { style = style.copy(walletPlacement = value) }

        /** Creates the style. */
        public fun build(): PayriffPaymentViewStyle = style
    }
}

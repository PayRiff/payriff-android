package com.payriff.android

import androidx.annotation.ColorInt

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
 * @property buttonHeight Height of the Pay button in dp; it grows with large text. Defaults to 44.
 * @property buttonCornerRadius Corner radius of the Pay button in dp. Defaults to 16.
 * @property payButtonLabel The word of the Pay button, in place of "Pay" in the view's language; it is shown as it is in
 *   every language. The amount and the currency still follow it ("Pay now 1.02 AZN"), and it shows alone while the
 *   amount is unknown. `null` (the default) or a blank text keeps "Pay", "Ödə" or "Оплатить".
 * @property secondaryTextColor The secure line and "Powered by"; `null` is `#76787F`.
 * @property showBadges Shows the Mastercard and Visa badges below the button. Defaults to `true`.
 * @property showSecureNote Shows the lock and "Proceed securely by PCI DSS". Defaults to `true`.
 * @property secureNoteText The text next to the lock, in place of "Proceed securely by PCI DSS" in the view's language;
 *   it is shown as it is in every language. `null` (the default) or a blank text keeps the localized text.
 * @property showPoweredBy Shows "Powered by" and the Payriff logo at the bottom. Defaults to `true`. In the dark theme the
 *   logo's wordmark is light (`#E1E4E8`).
 * @property formStyle The card form inside the view ([PayriffCardForm.style]); for example
 *   `PayriffCardFormStyle(showLabels = false)` hides "Card holder" and "Card information". Its error colour and error
 *   text style are also used for the view's error line under the Pay button.
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

        /** Creates the style. */
        public fun build(): PayriffPaymentViewStyle = style
    }
}

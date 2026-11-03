package com.payriff.android

/**
 * Your own texts for the SDK's screens (CONTRACT §18.6), for example from your app's localisation in a language the SDK
 * does not have. Every property is optional and replaces one built-in text in every language; together they cover all
 * visible text of [PayriffCardForm], [PayriffPaymentView] and the 3-D Secure sheet.
 *
 * ```
 * Payriff.configure(
 *     PayriffConfig(
 *         publishableKey = "pr_pk_live_…",
 *         strings = PayriffStrings(
 *             pay = "Öde",                                // "Öde 10.50 AZN"
 *             numberInvalid = "Kart numarası geçersiz",
 *             errorMessages = mapOf(PayriffErrorCode.NETWORK_ERROR to "İnternet bağlantısı yok"),
 *         ),
 *     ),
 * )
 * ```
 *
 * [PayriffConfig.strings] applies to every screen, the 3-D Secure sheet of [PayriffCardPaymentLauncher] included;
 * [PayriffPaymentView.strings] applies to that view and the sheets it opens. Each text is the view's value, else the
 * configuration's, else the built-in text in the effective language ([PayriffLanguage]). A value is shown exactly as
 * given; only one that is blank after trimming counts as not set. The view's style still wins over [pay]
 * ([PayriffPaymentViewStyle.payButtonTitle], [PayriffPaymentViewStyle.payButtonLabel]), [secureNote]
 * ([PayriffPaymentViewStyle.secureNoteText]) and [orPayByCard] ([PayriffPaymentViewStyle.walletDividerText]), and the
 * form's style over [holderLabel] and [cardInformationLabel] ([PayriffCardFormStyle.holderLabel],
 * [PayriffCardFormStyle.cardLabel]). Java uses [Builder].
 *
 * @property holderLabel The label of the cardholder name ("Card holder").
 * @property cardInformationLabel The label of the grouped layout's card box ("Card information").
 * @property numberAccessibilityLabel What accessibility services call the card number in the card box ("Card number");
 *   in the stacked layout also the visible label above it.
 * @property expiryAccessibilityLabel Likewise for the expiry ("Expiry date").
 * @property cvcAccessibilityLabel Likewise for the CVC ("Security code").
 * @property holderPlaceholder The placeholder of the cardholder name ("Card holder").
 * @property numberPlaceholder The placeholder of the card number (`1234 1234 1234 1234`).
 * @property expiryPlaceholder The placeholder of the expiry (`MM / YY`).
 * @property cvcPlaceholder The placeholder of the CVC (`CVC`).
 * @property numberRequired The card number's error after a payment attempt with an empty field.
 * @property numberIncomplete The card number's error when it is too short.
 * @property numberInvalid The card number's error when it fails the Luhn check, and for any other card number error
 *   without a text of its own.
 * @property numberUnsupported The card number's error for a brand the SDK does not accept.
 * @property expiryRequired The expiry's error after a payment attempt with an empty field.
 * @property expiryIncomplete The expiry's error when it is too short.
 * @property expiryInvalid The expiry's error for an impossible month or a year too far ahead.
 * @property expiryExpired The expiry's error for a date in the past.
 * @property cvcRequired The CVC's error after a payment attempt with an empty field.
 * @property cvcIncomplete The CVC's error when it is too short.
 * @property cvcInvalid The CVC's error for any other problem.
 * @property holderRequired The cardholder name's error after a payment attempt with an empty field.
 * @property holderInvalid The cardholder name's error for a name that is not allowed.
 * @property pay The word of [PayriffPaymentView]'s Pay button ("Pay"), followed by the amount and the currency.
 * @property secureNote The text next to the lock ("Proceed securely by PCI DSS").
 * @property poweredBy The footer ("Powered by Payriff"). The Payriff logo replaces the first "Payriff" in it; a text
 *   without "Payriff" is followed by a space and the logo.
 * @property orPayByCard The divider under the Google Pay button ("Or pay by card").
 * @property notPayable The error line of an order that cannot be paid by card.
 * @property deviceNotSupported The error line on a compromised device ([PayriffErrorCode.DEVICE_COMPROMISED]).
 * @property sheetTitle The 3-D Secure sheet's title ("Payment").
 * @property verifying The text under the 3-D Secure sheet's spinner while the status is checked.
 * @property close The 3-D Secure sheet's close button (for accessibility services) and the "Close" of its error state.
 * @property retry The "Try again" of the 3-D Secure sheet's error state.
 * @property closeConfirmTitle The title of the dialog that asks before the 3-D Secure sheet closes.
 * @property closeConfirmMessage The message of that dialog.
 * @property closeConfirmClose The button of that dialog that closes the sheet.
 * @property closeConfirmContinue The button of that dialog that keeps the sheet open.
 * @property pageLoadError The 3-D Secure sheet's error state when the bank's page does not load.
 * @property dialogOk The OK button of JavaScript dialogs where an SDK draws them itself. Android uses the WebView's own
 *   dialogs, so this SDK never shows it; it exists so one value works for every Payriff SDK.
 * @property dialogCancel Likewise for the Cancel button.
 * @property errorMessages The text of an on-screen error line for a [PayriffErrorCode]: the payment view's error line
 *   under the Pay button (an order that does not load or a payment that ends the order, and also
 *   [PayriffErrorCode.ORDER_NOT_PAYABLE] for an order that cannot be paid by card and
 *   [PayriffErrorCode.DEVICE_COMPROMISED] on a compromised device) and the 3-D Secure sheet's error state
 *   ([PayriffErrorCode.NETWORK_ERROR]). A line's own text ([notPayable], [deviceNotSupported], [pageLoadError]) wins over
 *   it. Without an entry the line shows the built-in text, or the error's [PayriffError.message].
 *   [PayriffError.message] in results never changes: it stays a developer-facing English text.
 */
public data class PayriffStrings(
    public val holderLabel: String? = null,
    public val cardInformationLabel: String? = null,
    public val numberAccessibilityLabel: String? = null,
    public val expiryAccessibilityLabel: String? = null,
    public val cvcAccessibilityLabel: String? = null,
    public val holderPlaceholder: String? = null,
    public val numberPlaceholder: String? = null,
    public val expiryPlaceholder: String? = null,
    public val cvcPlaceholder: String? = null,
    public val numberRequired: String? = null,
    public val numberIncomplete: String? = null,
    public val numberInvalid: String? = null,
    public val numberUnsupported: String? = null,
    public val expiryRequired: String? = null,
    public val expiryIncomplete: String? = null,
    public val expiryInvalid: String? = null,
    public val expiryExpired: String? = null,
    public val cvcRequired: String? = null,
    public val cvcIncomplete: String? = null,
    public val cvcInvalid: String? = null,
    public val holderRequired: String? = null,
    public val holderInvalid: String? = null,
    public val pay: String? = null,
    public val secureNote: String? = null,
    public val poweredBy: String? = null,
    public val orPayByCard: String? = null,
    public val notPayable: String? = null,
    public val deviceNotSupported: String? = null,
    public val sheetTitle: String? = null,
    public val verifying: String? = null,
    public val close: String? = null,
    public val retry: String? = null,
    public val closeConfirmTitle: String? = null,
    public val closeConfirmMessage: String? = null,
    public val closeConfirmClose: String? = null,
    public val closeConfirmContinue: String? = null,
    public val pageLoadError: String? = null,
    public val dialogOk: String? = null,
    public val dialogCancel: String? = null,
    public val errorMessages: Map<PayriffErrorCode, String> = emptyMap(),
) {

    /** Returns a [Builder] pre-filled with these texts. */
    public fun newBuilder(): Builder = Builder(this)

    /** Java-friendly builder for [PayriffStrings]. Every setter mirrors the property of the same name. */
    public class Builder internal constructor(private var strings: PayriffStrings) {

        /** Starts with no text of your own: every text is the built-in one. */
        public constructor() : this(PayriffStrings())

        /** Sets [PayriffStrings.holderLabel]. */
        public fun holderLabel(value: String?): Builder = apply { strings = strings.copy(holderLabel = value) }

        /** Sets [PayriffStrings.cardInformationLabel]. */
        public fun cardInformationLabel(value: String?): Builder = apply { strings = strings.copy(cardInformationLabel = value) }

        /** Sets [PayriffStrings.numberAccessibilityLabel]. */
        public fun numberAccessibilityLabel(value: String?): Builder = apply { strings = strings.copy(numberAccessibilityLabel = value) }

        /** Sets [PayriffStrings.expiryAccessibilityLabel]. */
        public fun expiryAccessibilityLabel(value: String?): Builder = apply { strings = strings.copy(expiryAccessibilityLabel = value) }

        /** Sets [PayriffStrings.cvcAccessibilityLabel]. */
        public fun cvcAccessibilityLabel(value: String?): Builder = apply { strings = strings.copy(cvcAccessibilityLabel = value) }

        /** Sets [PayriffStrings.holderPlaceholder]. */
        public fun holderPlaceholder(value: String?): Builder = apply { strings = strings.copy(holderPlaceholder = value) }

        /** Sets [PayriffStrings.numberPlaceholder]. */
        public fun numberPlaceholder(value: String?): Builder = apply { strings = strings.copy(numberPlaceholder = value) }

        /** Sets [PayriffStrings.expiryPlaceholder]. */
        public fun expiryPlaceholder(value: String?): Builder = apply { strings = strings.copy(expiryPlaceholder = value) }

        /** Sets [PayriffStrings.cvcPlaceholder]. */
        public fun cvcPlaceholder(value: String?): Builder = apply { strings = strings.copy(cvcPlaceholder = value) }

        /** Sets [PayriffStrings.numberRequired]. */
        public fun numberRequired(value: String?): Builder = apply { strings = strings.copy(numberRequired = value) }

        /** Sets [PayriffStrings.numberIncomplete]. */
        public fun numberIncomplete(value: String?): Builder = apply { strings = strings.copy(numberIncomplete = value) }

        /** Sets [PayriffStrings.numberInvalid]. */
        public fun numberInvalid(value: String?): Builder = apply { strings = strings.copy(numberInvalid = value) }

        /** Sets [PayriffStrings.numberUnsupported]. */
        public fun numberUnsupported(value: String?): Builder = apply { strings = strings.copy(numberUnsupported = value) }

        /** Sets [PayriffStrings.expiryRequired]. */
        public fun expiryRequired(value: String?): Builder = apply { strings = strings.copy(expiryRequired = value) }

        /** Sets [PayriffStrings.expiryIncomplete]. */
        public fun expiryIncomplete(value: String?): Builder = apply { strings = strings.copy(expiryIncomplete = value) }

        /** Sets [PayriffStrings.expiryInvalid]. */
        public fun expiryInvalid(value: String?): Builder = apply { strings = strings.copy(expiryInvalid = value) }

        /** Sets [PayriffStrings.expiryExpired]. */
        public fun expiryExpired(value: String?): Builder = apply { strings = strings.copy(expiryExpired = value) }

        /** Sets [PayriffStrings.cvcRequired]. */
        public fun cvcRequired(value: String?): Builder = apply { strings = strings.copy(cvcRequired = value) }

        /** Sets [PayriffStrings.cvcIncomplete]. */
        public fun cvcIncomplete(value: String?): Builder = apply { strings = strings.copy(cvcIncomplete = value) }

        /** Sets [PayriffStrings.cvcInvalid]. */
        public fun cvcInvalid(value: String?): Builder = apply { strings = strings.copy(cvcInvalid = value) }

        /** Sets [PayriffStrings.holderRequired]. */
        public fun holderRequired(value: String?): Builder = apply { strings = strings.copy(holderRequired = value) }

        /** Sets [PayriffStrings.holderInvalid]. */
        public fun holderInvalid(value: String?): Builder = apply { strings = strings.copy(holderInvalid = value) }

        /** Sets [PayriffStrings.pay]. */
        public fun pay(value: String?): Builder = apply { strings = strings.copy(pay = value) }

        /** Sets [PayriffStrings.secureNote]. */
        public fun secureNote(value: String?): Builder = apply { strings = strings.copy(secureNote = value) }

        /** Sets [PayriffStrings.poweredBy]. */
        public fun poweredBy(value: String?): Builder = apply { strings = strings.copy(poweredBy = value) }

        /** Sets [PayriffStrings.orPayByCard]. */
        public fun orPayByCard(value: String?): Builder = apply { strings = strings.copy(orPayByCard = value) }

        /** Sets [PayriffStrings.notPayable]. */
        public fun notPayable(value: String?): Builder = apply { strings = strings.copy(notPayable = value) }

        /** Sets [PayriffStrings.deviceNotSupported]. */
        public fun deviceNotSupported(value: String?): Builder = apply { strings = strings.copy(deviceNotSupported = value) }

        /** Sets [PayriffStrings.sheetTitle]. */
        public fun sheetTitle(value: String?): Builder = apply { strings = strings.copy(sheetTitle = value) }

        /** Sets [PayriffStrings.verifying]. */
        public fun verifying(value: String?): Builder = apply { strings = strings.copy(verifying = value) }

        /** Sets [PayriffStrings.close]. */
        public fun close(value: String?): Builder = apply { strings = strings.copy(close = value) }

        /** Sets [PayriffStrings.retry]. */
        public fun retry(value: String?): Builder = apply { strings = strings.copy(retry = value) }

        /** Sets [PayriffStrings.closeConfirmTitle]. */
        public fun closeConfirmTitle(value: String?): Builder = apply { strings = strings.copy(closeConfirmTitle = value) }

        /** Sets [PayriffStrings.closeConfirmMessage]. */
        public fun closeConfirmMessage(value: String?): Builder = apply { strings = strings.copy(closeConfirmMessage = value) }

        /** Sets [PayriffStrings.closeConfirmClose]. */
        public fun closeConfirmClose(value: String?): Builder = apply { strings = strings.copy(closeConfirmClose = value) }

        /** Sets [PayriffStrings.closeConfirmContinue]. */
        public fun closeConfirmContinue(value: String?): Builder = apply { strings = strings.copy(closeConfirmContinue = value) }

        /** Sets [PayriffStrings.pageLoadError]. */
        public fun pageLoadError(value: String?): Builder = apply { strings = strings.copy(pageLoadError = value) }

        /** Sets [PayriffStrings.dialogOk]. */
        public fun dialogOk(value: String?): Builder = apply { strings = strings.copy(dialogOk = value) }

        /** Sets [PayriffStrings.dialogCancel]. */
        public fun dialogCancel(value: String?): Builder = apply { strings = strings.copy(dialogCancel = value) }

        /** Sets [PayriffStrings.errorMessages], replacing every text set before. The map is copied. */
        public fun errorMessages(value: Map<PayriffErrorCode, String>): Builder = apply { strings = strings.copy(errorMessages = value.toMap()) }

        /** Sets the text of one error code in [PayriffStrings.errorMessages]; `null` removes it. */
        public fun errorMessage(code: PayriffErrorCode, value: String?): Builder = apply {
            strings = strings.copy(errorMessages = if (value == null) strings.errorMessages - code else strings.errorMessages + (code to value))
        }

        /** Creates the texts. */
        public fun build(): PayriffStrings = strings
    }
}

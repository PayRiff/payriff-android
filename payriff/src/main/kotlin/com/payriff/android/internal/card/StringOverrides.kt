package com.payriff.android.internal.card

import com.payriff.android.PayriffCardField
import com.payriff.android.PayriffErrorCode
import com.payriff.android.PayriffLanguage
import com.payriff.android.PayriffStrings
import com.payriff.android.internal.KeyValues
import com.payriff.android.internal.SheetStrings
import com.payriff.android.internal.StringKey

/** Where a built-in text lives (`strings_override.json` → `fields` → `source`). */
internal enum class StringSource(val vectorName: String) {
    /** [CardStrings], `card_strings.json`: the card form and the payment view. Keys are `<group>.<name>`. */
    CARD("card_strings"),

    /** [SheetStrings], `strings.json`: the 3-D Secure sheet. */
    SHEET("strings"),
}

/**
 * A field of [PayriffStrings] and the built-in text it replaces in every language (CONTRACT §18.6,
 * `strings_override.json` → `fields`).
 *
 * @property fieldName The property's name in [PayriffStrings].
 * @property key The built-in text's key in [source].
 * @property value Reads the field.
 */
internal enum class StringField(val fieldName: String, val source: StringSource, val key: String, val value: (PayriffStrings) -> String?) {
    HOLDER_LABEL("holderLabel", StringSource.CARD, "label.holder", { it.holderLabel }),
    CARD_INFORMATION_LABEL("cardInformationLabel", StringSource.CARD, "group.cardInformation", { it.cardInformationLabel }),
    NUMBER_ACCESSIBILITY_LABEL("numberAccessibilityLabel", StringSource.CARD, "label.number", { it.numberAccessibilityLabel }),
    EXPIRY_ACCESSIBILITY_LABEL("expiryAccessibilityLabel", StringSource.CARD, "label.expiry", { it.expiryAccessibilityLabel }),
    CVC_ACCESSIBILITY_LABEL("cvcAccessibilityLabel", StringSource.CARD, "label.cvv", { it.cvcAccessibilityLabel }),
    HOLDER_PLACEHOLDER("holderPlaceholder", StringSource.CARD, "placeholder.holder", { it.holderPlaceholder }),
    NUMBER_PLACEHOLDER("numberPlaceholder", StringSource.CARD, "placeholder.number", { it.numberPlaceholder }),
    EXPIRY_PLACEHOLDER("expiryPlaceholder", StringSource.CARD, "placeholder.expiry", { it.expiryPlaceholder }),
    CVC_PLACEHOLDER("cvcPlaceholder", StringSource.CARD, "placeholder.cvv", { it.cvcPlaceholder }),
    NUMBER_REQUIRED("numberRequired", StringSource.CARD, "number.required", { it.numberRequired }),
    NUMBER_INCOMPLETE("numberIncomplete", StringSource.CARD, "number.incomplete", { it.numberIncomplete }),
    NUMBER_INVALID("numberInvalid", StringSource.CARD, "number.invalid", { it.numberInvalid }),
    NUMBER_UNSUPPORTED("numberUnsupported", StringSource.CARD, "number.unsupported", { it.numberUnsupported }),
    EXPIRY_REQUIRED("expiryRequired", StringSource.CARD, "expiry.required", { it.expiryRequired }),
    EXPIRY_INCOMPLETE("expiryIncomplete", StringSource.CARD, "expiry.incomplete", { it.expiryIncomplete }),
    EXPIRY_INVALID("expiryInvalid", StringSource.CARD, "expiry.invalid", { it.expiryInvalid }),
    EXPIRY_EXPIRED("expiryExpired", StringSource.CARD, "expiry.expired", { it.expiryExpired }),
    CVC_REQUIRED("cvcRequired", StringSource.CARD, "cvv.required", { it.cvcRequired }),
    CVC_INCOMPLETE("cvcIncomplete", StringSource.CARD, "cvv.incomplete", { it.cvcIncomplete }),
    CVC_INVALID("cvcInvalid", StringSource.CARD, "cvv.invalid", { it.cvcInvalid }),
    HOLDER_REQUIRED("holderRequired", StringSource.CARD, "holder.required", { it.holderRequired }),
    HOLDER_INVALID("holderInvalid", StringSource.CARD, "holder.invalid", { it.holderInvalid }),
    PAY("pay", StringSource.CARD, "view.pay", { it.pay }),
    SECURE_NOTE("secureNote", StringSource.CARD, "view.secure", { it.secureNote }),
    POWERED_BY("poweredBy", StringSource.CARD, "view.poweredBy", { it.poweredBy }),
    OR_PAY_BY_CARD("orPayByCard", StringSource.CARD, "view.orPayByCard", { it.orPayByCard }),
    NOT_PAYABLE("notPayable", StringSource.CARD, "view.notPayable", { it.notPayable }),
    DEVICE_NOT_SUPPORTED("deviceNotSupported", StringSource.CARD, "view.deviceNotSupported", { it.deviceNotSupported }),
    SHEET_TITLE("sheetTitle", StringSource.SHEET, "title", { it.sheetTitle }),
    VERIFYING("verifying", StringSource.SHEET, "verifying", { it.verifying }),
    CLOSE("close", StringSource.SHEET, "close", { it.close }),
    RETRY("retry", StringSource.SHEET, "retry", { it.retry }),
    CLOSE_CONFIRM_TITLE("closeConfirmTitle", StringSource.SHEET, "cancel_confirm_title", { it.closeConfirmTitle }),
    CLOSE_CONFIRM_MESSAGE("closeConfirmMessage", StringSource.SHEET, "cancel_confirm_message", { it.closeConfirmMessage }),
    CLOSE_CONFIRM_CLOSE("closeConfirmClose", StringSource.SHEET, "cancel_confirm_yes", { it.closeConfirmClose }),
    CLOSE_CONFIRM_CONTINUE("closeConfirmContinue", StringSource.SHEET, "cancel_confirm_no", { it.closeConfirmContinue }),
    PAGE_LOAD_ERROR("pageLoadError", StringSource.SHEET, "page_load_error", { it.pageLoadError }),

    /** Only for SDKs that draw JavaScript dialogs themselves (CONTRACT §10); Android has no built-in text for it. */
    DIALOG_OK("dialogOk", StringSource.SHEET, "dialog_ok", { it.dialogOk }),
    DIALOG_CANCEL("dialogCancel", StringSource.SHEET, "dialog_cancel", { it.dialogCancel }),
    ;

    /** The built-in text in [language], or `null` for a text this SDK never shows ([DIALOG_OK], [DIALOG_CANCEL]). */
    fun builtIn(language: PayriffLanguage): String? {
        val code = CardStrings.code(language)
        return when (source) {
            StringSource.CARD -> CardStrings.messages[code]?.get(key.substringBefore('.'))?.get(key.substringAfter('.'))
            StringSource.SHEET -> SheetStrings.table[key]?.get(code)
        }
    }

    companion object {
        private val byKey: Map<String, StringField> by lazy { entries.associateBy { it.source.name + ":" + it.key } }

        /** The field of [key] in [source], or `null`. */
        fun of(source: StringSource, key: String): StringField? = byKey[source.name + ":" + key]

        /** The field named [fieldName] in [PayriffStrings], or `null`. */
        fun named(fieldName: String): StringField? = entries.firstOrNull { it.fieldName == fieldName }

        /** `label.<field>`: the holder's label, or what accessibility services call a field of the card box. */
        fun label(field: PayriffCardField): StringField = card("label." + CardStrings.key(field))

        fun placeholder(field: PayriffCardField): StringField = card("placeholder." + CardStrings.key(field))

        /** The error [errorCode] of [field]; a code without a text of its own uses the field's `invalid`, as [CardStrings.error] does. */
        fun error(field: PayriffCardField, errorCode: CardErrorCode): StringField {
            val group = CardStrings.key(field)
            return of(StringSource.CARD, group + "." + errorCode.value) ?: card(group + "." + CardErrorCode.INVALID.value)
        }

        /** `view.<name>`, one of the `CardStrings.VIEW_*` names. */
        fun view(name: String): StringField = card("view.$name")

        fun sheet(key: StringKey): StringField = checkNotNull(of(StringSource.SHEET, key.key)) { "No PayriffStrings field for ${key.key}" }

        private fun card(key: String): StringField = checkNotNull(of(StringSource.CARD, key)) { "No PayriffStrings field for $key" }
    }
}

/**
 * The texts of one screen in its effective [language] (CONTRACT §18.6): each field is the view's [PayriffStrings]
 * value, else the configuration's, else the built-in text. A value is used exactly as given; only one that is blank
 * after trimming counts as not set (`strings_override.json` → `resolution`). Pure Kotlin, so the JVM unit tests check
 * the resolution.
 *
 * @param view [com.payriff.android.PayriffPaymentView.strings], for the view and the sheets it opens.
 * @param config [com.payriff.android.PayriffConfig.strings].
 */
internal class ResolvedStrings(
    val language: PayriffLanguage,
    private val view: PayriffStrings? = null,
    private val config: PayriffStrings? = null,
) {
    /** The app's text of [field]: the view's, else the configuration's; `null` when neither sets it. */
    fun override(field: StringField): String? = given(view?.let(field.value)) ?: given(config?.let(field.value))

    /** The text of [field] as the screen shows it. */
    fun text(field: StringField): String =
        override(field) ?: checkNotNull(field.builtIn(language)) { "No built-in text for ${field.fieldName}" }

    fun label(field: PayriffCardField): String = text(StringField.label(field))

    fun cardInformation(): String = text(StringField.CARD_INFORMATION_LABEL)

    fun placeholder(field: PayriffCardField): String = text(StringField.placeholder(field))

    fun error(field: PayriffCardField, errorCode: CardErrorCode): String = text(StringField.error(field, errorCode))

    /** A text of the payment view: one of the `CardStrings.VIEW_*` names. */
    fun view(name: String): String = text(StringField.view(name))

    fun sheet(key: StringKey): String = text(StringField.sheet(key))

    /** The app's text of an on-screen error line for [code] ([PayriffStrings.errorMessages]): the view's, else the configuration's. */
    fun errorMessage(code: PayriffErrorCode): String? =
        given(view?.errorMessages?.get(code)) ?: given(config?.errorMessages?.get(code))

    /**
     * An error line that has a text of its own, [field], shown for errors of [code]: the app's [field], else its
     * [errorMessage] for [code], else the built-in text of [field].
     */
    fun errorLine(field: StringField, code: PayriffErrorCode): String = override(field) ?: errorMessage(code) ?: text(field)

    private fun given(value: String?): String? = value?.takeUnless { it.isBlank() }
}

/**
 * The 3-D Secure sheet's texts of a view's [PayriffStrings] (CONTRACT §18.6) in the sheet's Intent, so they survive
 * process death; the sheet resolves them over [com.payriff.android.PayriffConfig.strings] again. Only strings cross the
 * Intent boundary.
 */
internal object SheetStringsCodec {
    private const val PREFIX = "payriff.strings."
    private const val ERROR_PREFIX = "payriff.strings.errorMessages."

    /** The fields the sheet shows. */
    val FIELDS: List<StringField> = listOf(
        StringField.SHEET_TITLE,
        StringField.VERIFYING,
        StringField.CLOSE,
        StringField.RETRY,
        StringField.CLOSE_CONFIRM_TITLE,
        StringField.CLOSE_CONFIRM_MESSAGE,
        StringField.CLOSE_CONFIRM_CLOSE,
        StringField.CLOSE_CONFIRM_CONTINUE,
        StringField.PAGE_LOAD_ERROR,
    )

    fun write(strings: PayriffStrings, out: KeyValues) {
        for (field in FIELDS) field.value(strings)?.let { out.putString(PREFIX + field.fieldName, it) }
        for ((code, text) in strings.errorMessages) out.putString(ERROR_PREFIX + code.name, text)
    }

    /** The sheet's texts in [input], or `null` when it holds none. */
    fun read(input: KeyValues): PayriffStrings? {
        fun text(field: StringField): String? = input.getString(PREFIX + field.fieldName)
        val errors = PayriffErrorCode.entries.mapNotNull { code -> input.getString(ERROR_PREFIX + code.name)?.let { code to it } }.toMap()
        val strings = PayriffStrings(
            sheetTitle = text(StringField.SHEET_TITLE),
            verifying = text(StringField.VERIFYING),
            close = text(StringField.CLOSE),
            retry = text(StringField.RETRY),
            closeConfirmTitle = text(StringField.CLOSE_CONFIRM_TITLE),
            closeConfirmMessage = text(StringField.CLOSE_CONFIRM_MESSAGE),
            closeConfirmClose = text(StringField.CLOSE_CONFIRM_CLOSE),
            closeConfirmContinue = text(StringField.CLOSE_CONFIRM_CONTINUE),
            pageLoadError = text(StringField.PAGE_LOAD_ERROR),
            errorMessages = errors,
        )
        return strings.takeUnless { it == PayriffStrings() }
    }
}

package com.payriff.android.internal.card

import com.payriff.android.PayriffCardField
import com.payriff.android.PayriffLanguage

/**
 * Card form and payment view copy in az/en/ru: exactly `vectors/card_strings.json`. The labels, placeholders, the "Card
 * information" group label and the payment view's texts follow Payriff's hosted payment page; the field errors come from
 * the web SDK (`fields/i18n.js`). A unit test checks the table. In-code strings keep the AAR free of resources that host
 * apps could accidentally override.
 */
internal object CardStrings {

    /**
     * language code -> group (`number`, `expiry`, `cvv`, `holder`, `placeholder`, `label`, `group`, `view`) -> key ->
     * text.
     */
    val messages: Map<String, Map<String, Map<String, String>>> = mapOf(
        "az" to mapOf(
            "number" to mapOf(
                "required" to "Kart nömrəsini daxil edin",
                "incomplete" to "Kart nömrəsi tam deyil",
                "invalid" to "Kart nömrəsi yanlışdır",
                "unsupported" to "Bu kart növü dəstəklənmir",
            ),
            "expiry" to mapOf(
                "required" to "Bitmə tarixini daxil edin",
                "incomplete" to "Bitmə tarixi tam deyil",
                "invalid" to "Bitmə tarixi yanlışdır",
                "expired" to "Kartın müddəti bitib",
            ),
            "cvv" to mapOf(
                "required" to "CVV daxil edin",
                "incomplete" to "CVV tam deyil",
                "invalid" to "CVV yanlışdır",
            ),
            "holder" to mapOf(
                "required" to "Kart sahibinin adını daxil edin",
                "invalid" to "Kart sahibinin adı yanlışdır",
            ),
            "placeholder" to mapOf(
                "number" to "1234 1234 1234 1234",
                "expiry" to "MM / YY",
                "cvv" to "CVC",
                "holder" to "Kart sahibinin adı",
            ),
            "label" to mapOf(
                "number" to "Kart nömrəsi",
                "expiry" to "Bitmə tarixi",
                "cvv" to "CVV kodu",
                "holder" to "Kart sahibinin adı",
            ),
            "group" to mapOf(
                "cardInformation" to "Kart məlumatları",
            ),
            "view" to mapOf(
                "pay" to "Ödə",
                "secure" to "PCI DSS ilə təhlükəsiz ödəniş",
                "poweredBy" to "Payriff tərəfindən hazırlanıb",
                "notPayable" to "Bu sifarişi kartla ödəmək mümkün deyil",
            ),
        ),
        "en" to mapOf(
            "number" to mapOf(
                "required" to "Enter your card number",
                "incomplete" to "Card number is incomplete",
                "invalid" to "Card number is invalid",
                "unsupported" to "This card type is not supported",
            ),
            "expiry" to mapOf(
                "required" to "Enter the expiry date",
                "incomplete" to "Expiry date is incomplete",
                "invalid" to "Expiry date is invalid",
                "expired" to "This card has expired",
            ),
            "cvv" to mapOf(
                "required" to "Enter the CVV",
                "incomplete" to "CVV is incomplete",
                "invalid" to "CVV is invalid",
            ),
            "holder" to mapOf(
                "required" to "Enter the cardholder name",
                "invalid" to "Cardholder name is invalid",
            ),
            "placeholder" to mapOf(
                "number" to "1234 1234 1234 1234",
                "expiry" to "MM / YY",
                "cvv" to "CVC",
                "holder" to "Card holder",
            ),
            "label" to mapOf(
                "number" to "Card number",
                "expiry" to "Expiry date",
                "cvv" to "Security code",
                "holder" to "Card holder",
            ),
            "group" to mapOf(
                "cardInformation" to "Card information",
            ),
            "view" to mapOf(
                "pay" to "Pay",
                "secure" to "Proceed securely by PCI DSS",
                "poweredBy" to "Powered by Payriff",
                "notPayable" to "This order cannot be paid by card",
            ),
        ),
        "ru" to mapOf(
            "number" to mapOf(
                "required" to "Введите номер карты",
                "incomplete" to "Номер карты введён не полностью",
                "invalid" to "Неверный номер карты",
                "unsupported" to "Этот тип карты не поддерживается",
            ),
            "expiry" to mapOf(
                "required" to "Введите срок действия",
                "incomplete" to "Срок действия введён не полностью",
                "invalid" to "Неверный срок действия",
                "expired" to "Срок действия карты истёк",
            ),
            "cvv" to mapOf(
                "required" to "Введите CVV",
                "incomplete" to "CVV введён не полностью",
                "invalid" to "Неверный CVV",
            ),
            "holder" to mapOf(
                "required" to "Введите имя владельца карты",
                "invalid" to "Неверное имя владельца карты",
            ),
            "placeholder" to mapOf(
                "number" to "1234 1234 1234 1234",
                "expiry" to "MM / YY",
                "cvv" to "CVC",
                "holder" to "Владелец карты",
            ),
            "label" to mapOf(
                "number" to "Номер карты",
                "expiry" to "Срок действия",
                "cvv" to "Код безопасности",
                "holder" to "Владелец карты",
            ),
            "group" to mapOf(
                "cardInformation" to "Информация о карте",
            ),
            "view" to mapOf(
                "pay" to "Оплатить",
                "secure" to "Безопасная оплата по PCI DSS",
                "poweredBy" to "При поддержке Payriff",
                "notPayable" to "Этот заказ нельзя оплатить картой",
            ),
        ),
    )

    /** The language code of [language] (`az`, `en`, `ru`). */
    fun code(language: PayriffLanguage): String = when (language) {
        PayriffLanguage.AZ -> "az"
        PayriffLanguage.EN -> "en"
        PayriffLanguage.RU -> "ru"
    }

    fun label(language: PayriffLanguage, field: PayriffCardField): String = group(language, "label").getValue(key(field))

    fun placeholder(language: PayriffLanguage, field: PayriffCardField): String = group(language, "placeholder").getValue(key(field))

    /** The label of the grouped layout's card box ("Card information"). */
    fun cardInformation(language: PayriffLanguage): String = group(language, "group").getValue("cardInformation")

    /** The payment view's button title, followed by the amount: "Pay". */
    const val VIEW_PAY = "pay"

    /** The payment view's line under the badges: "Proceed securely by PCI DSS". */
    const val VIEW_SECURE = "secure"

    /** The payment view's footer, "Powered by Payriff", where the logo replaces "Payriff". */
    const val VIEW_POWERED_BY = "poweredBy"

    /** The payment view's error line for an order that cannot be paid by card: "This order cannot be paid by card". */
    const val VIEW_NOT_PAYABLE = "notPayable"

    /**
     * The payment view's copy (CONTRACT §16.14): [VIEW_PAY] (followed by the amount), [VIEW_SECURE] (the PCI DSS line),
     * [VIEW_POWERED_BY] (split at "Payriff", where the logo goes) or [VIEW_NOT_PAYABLE] (the error line).
     */
    fun view(language: PayriffLanguage, key: String): String = group(language, "view").getValue(key)

    /** The message for [errorCode] on [field]; a code without a specific message falls back to the field's `invalid`. */
    fun error(language: PayriffLanguage, field: PayriffCardField, errorCode: CardErrorCode): String {
        val messages = group(language, key(field))
        return messages[errorCode.value] ?: messages.getValue(CardErrorCode.INVALID.value)
    }

    /** The JSON name of [field]: `number`, `expiry`, `cvv` or `holder`. */
    fun key(field: PayriffCardField): String = when (field) {
        PayriffCardField.NUMBER -> "number"
        PayriffCardField.EXPIRY -> "expiry"
        PayriffCardField.CVV -> "cvv"
        PayriffCardField.HOLDER -> "holder"
    }

    private fun group(language: PayriffLanguage, name: String): Map<String, String> =
        messages.getValue(code(language)).getValue(name)
}

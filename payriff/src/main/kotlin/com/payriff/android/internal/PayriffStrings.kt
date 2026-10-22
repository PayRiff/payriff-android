package com.payriff.android.internal

import com.payriff.android.PayriffLanguage

/** Keys of the 3-D Secure sheet's strings (CONTRACT §10, vectors/strings.json). */
internal enum class StringKey(val key: String) {
    TITLE("title"),
    VERIFYING("verifying"),
    CLOSE("close"),
    RETRY("retry"),
    CANCEL_CONFIRM_TITLE("cancel_confirm_title"),
    CANCEL_CONFIRM_MESSAGE("cancel_confirm_message"),
    CANCEL_CONFIRM_YES("cancel_confirm_yes"),
    CANCEL_CONFIRM_NO("cancel_confirm_no"),
    PAGE_LOAD_ERROR("page_load_error"),
}

/**
 * The 3-D Secure sheet's strings. Every text must equal `vectors/strings.json`; a unit test checks it. The vector's
 * other keys belonged to the hosted payment page, which this SDK no longer has.
 * In-code strings keep the AAR free of resources that host apps could accidentally override.
 */
internal object PayriffStrings {

    /** key -> language code (`az`, `en`, `ru`) -> text. */
    val table: Map<String, Map<String, String>> = mapOf(
        "title" to mapOf(
            "az" to "Ödəniş",
            "en" to "Payment",
            "ru" to "Оплата",
        ),
        "verifying" to mapOf(
            "az" to "Ödəniş yoxlanılır…",
            "en" to "Checking payment…",
            "ru" to "Проверяем платёж…",
        ),
        "close" to mapOf(
            "az" to "Bağla",
            "en" to "Close",
            "ru" to "Закрыть",
        ),
        "retry" to mapOf(
            "az" to "Yenidən cəhd et",
            "en" to "Try again",
            "ru" to "Повторить",
        ),
        "cancel_confirm_title" to mapOf(
            "az" to "Ödənişi ləğv edək?",
            "en" to "Cancel payment?",
            "ru" to "Отменить оплату?",
        ),
        "cancel_confirm_message" to mapOf(
            "az" to "Ödəniş hələ tamamlanmayıb. Pəncərəni bağlasanız, ödəniş ləğv olunacaq.",
            "en" to "The payment isn't complete yet. If you close this window, the payment will be cancelled.",
            "ru" to "Оплата ещё не завершена. Если закрыть окно, оплата будет отменена.",
        ),
        "cancel_confirm_yes" to mapOf(
            "az" to "Ləğv et",
            "en" to "Cancel payment",
            "ru" to "Отменить",
        ),
        "cancel_confirm_no" to mapOf(
            "az" to "Ödənişə davam et",
            "en" to "Continue payment",
            "ru" to "Продолжить оплату",
        ),
        "page_load_error" to mapOf(
            "az" to "Səhifə yüklənmədi. İnternet bağlantınızı yoxlayın.",
            "en" to "The page couldn't be loaded. Check your internet connection.",
            "ru" to "Не удалось загрузить страницу. Проверьте подключение к интернету.",
        ),
    )

    /** The text of [key] in [language]. */
    fun get(key: StringKey, language: PayriffLanguage): String {
        val texts = checkNotNull(table[key.key]) { "Missing string ${key.key}" }
        return checkNotNull(texts[language.code]) { "Missing ${language.code} text for ${key.key}" }
    }

    private val PayriffLanguage.code: String
        get() = when (this) {
            PayriffLanguage.AZ -> "az"
            PayriffLanguage.EN -> "en"
            PayriffLanguage.RU -> "ru"
        }
}

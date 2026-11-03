package com.payriff.android.internal

import com.payriff.android.PayriffLanguage
import com.payriff.android.PayriffRegion
import java.util.Locale

/** Device language mapping of CONTRACT §3.1 and §19.3 (`language.json`). */
internal object Languages {

    /**
     * Maps a locale tag such as `az-AZ`, `ru_KZ`, `ar-SA` or `en-US` to a [PayriffLanguage]. A language the SDK does not
     * have gives [PayriffLanguage.EN] in [PayriffRegion.AZ] and [PayriffLanguage.AR] in [PayriffRegion.SA], as
     * Payriff.js defaults Saudi Arabia to Arabic.
     */
    fun fromLocaleTag(tag: String?, region: PayriffRegion = PayriffRegion.AZ): PayriffLanguage {
        val primary = tag.orEmpty().substringBefore('-').substringBefore('_').lowercase(Locale.ROOT)
        return when (primary) {
            "az" -> PayriffLanguage.AZ
            "en" -> PayriffLanguage.EN
            "ru" -> PayriffLanguage.RU
            "ar" -> PayriffLanguage.AR
            else -> if (region == PayriffRegion.SA) PayriffLanguage.AR else PayriffLanguage.EN
        }
    }

    /** Whether [language] lays the SDK's views out right-to-left by itself (CONTRACT §19.3): Arabic. */
    fun isRightToLeft(language: PayriffLanguage): Boolean = language == PayriffLanguage.AR

    /** `form language ?: config language ?: device language` (in [region]). */
    fun effective(
        formLanguage: PayriffLanguage?,
        configLanguage: PayriffLanguage?,
        deviceLocaleTag: String?,
        region: PayriffRegion = PayriffRegion.AZ,
    ): PayriffLanguage = formLanguage ?: configLanguage ?: fromLocaleTag(deviceLocaleTag, region)
}

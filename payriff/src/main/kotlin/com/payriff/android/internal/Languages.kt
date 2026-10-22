package com.payriff.android.internal

import com.payriff.android.PayriffLanguage
import java.util.Locale

/** Device language mapping of CONTRACT §3.1. */
internal object Languages {

    /** Maps a locale tag such as `az-AZ`, `ru_KZ` or `en-US` to a [PayriffLanguage]. */
    fun fromLocaleTag(tag: String?): PayriffLanguage {
        val primary = tag.orEmpty().substringBefore('-').substringBefore('_').lowercase(Locale.ROOT)
        return when (primary) {
            "az" -> PayriffLanguage.AZ
            "ru" -> PayriffLanguage.RU
            else -> PayriffLanguage.EN
        }
    }

    /** `form language ?: config language ?: device language`. */
    fun effective(formLanguage: PayriffLanguage?, configLanguage: PayriffLanguage?, deviceLocaleTag: String?): PayriffLanguage =
        formLanguage ?: configLanguage ?: fromLocaleTag(deviceLocaleTag)
}

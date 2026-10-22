package com.payriff.android.vectors

import com.payriff.android.PayriffLanguage
import com.payriff.android.internal.Languages
import com.payriff.android.internal.PayriffStrings
import com.payriff.android.internal.StringKey
import com.payriff.android.testing.Vectors
import com.payriff.android.testing.Vectors.keyNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** vectors/language.json and strings.json. */
class SmallVectorsTest {

    /** CONTRACT §3.1. */
    @Test
    fun languageVectors() {
        val cases = Vectors.cases("language.json")
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            val locale = case.getString("locale")
            assertEquals("locale '$locale'", PayriffLanguage.valueOf(case.getString("expected")), Languages.fromLocaleTag(locale))
        }
        println("language.json: ${cases.size} cases passed")
    }

    /**
     * CONTRACT §10 (revision 5: the strings the 3-D Secure sheet uses): every key of the in-code table is a
     * [StringKey], and every text equals strings.json exactly. The vector's other keys belonged to the hosted payment
     * page, which the SDK no longer has.
     */
    @Test
    fun stringTableMatchesStringsJson() {
        val json = Vectors.load("strings.json").getJSONObject("strings")
        val expected: Map<String, Map<String, String>> = json.keyNames().associateWith { key ->
            val texts = json.getJSONObject(key)
            texts.keyNames().associateWith { texts.getString(it) }
        }
        assertEquals(StringKey.entries.map { it.key }.toSet(), PayriffStrings.table.keys)
        for ((key, texts) in PayriffStrings.table) {
            assertEquals(key, expected[key], texts)
        }
        for (key in StringKey.entries) {
            for (language in PayriffLanguage.entries) {
                val code = language.name.lowercase()
                assertEquals("${key.key}/$code", expected.getValue(key.key).getValue(code), PayriffStrings.get(key, language))
            }
        }
        println("strings.json: ${StringKey.entries.size} of ${expected.size} keys x 3 languages passed (the rest are hosted-page strings)")
    }
}

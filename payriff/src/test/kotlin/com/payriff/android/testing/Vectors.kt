package com.payriff.android.testing

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert
import java.math.BigDecimal

/** Loads the shared test vectors (spec/vectors, synced into src/test/resources/vectors). */
object Vectors {

    fun load(name: String): JSONObject {
        val stream = checkNotNull(Vectors::class.java.getResourceAsStream("/vectors/$name")) { "Missing vector file $name" }
        return JSONObject(stream.bufferedReader(Charsets.UTF_8).use { it.readText() })
    }

    fun cases(name: String, section: String = "cases"): List<JSONObject> = load(name).getJSONArray(section).objects()

    fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    /** A string member, or `null` when it is absent or JSON null. */
    fun JSONObject.stringOrNull(name: String): String? = if (!has(name) || isNull(name)) null else getString(name)

    /** A boolean member, or `null` when it is absent or JSON null. */
    fun JSONObject.booleanOrNull(name: String): Boolean? = if (!has(name) || isNull(name)) null else getBoolean(name)

    /** Member names. (Only the Android org.json API is visible at compile time, which has no keySet().) */
    fun JSONObject.keyNames(): Set<String> {
        val names = LinkedHashSet<String>()
        val iterator = keys()
        while (iterator.hasNext()) names += iterator.next()
        return names
    }

    /** The members of a JSON object as a map of strings. */
    fun JSONObject.toStringMap(): Map<String, String> = keyNames().associateWith { getString(it) }

    /**
     * Compares two JSON values structurally: the same keys (a key with a `null` value must be present on both
     * sides), numbers numerically, strings and booleans exactly.
     */
    fun assertJsonEquals(path: String, expected: Any?, actual: Any?) {
        val e = expected.takeUnless { it == JSONObject.NULL }
        val a = actual.takeUnless { it == JSONObject.NULL }
        when (e) {
            null -> Assert.assertNull("$path: expected null, got $a", a)
            is JSONObject -> {
                Assert.assertTrue("$path: expected an object, got $a", a is JSONObject)
                val obj = a as JSONObject
                Assert.assertEquals("$path: keys", e.keyNames(), obj.keyNames())
                for (key in e.keyNames()) assertJsonEquals("$path.$key", e.opt(key), obj.opt(key))
            }
            is JSONArray -> {
                Assert.assertTrue("$path: expected an array, got $a", a is JSONArray)
                val array = a as JSONArray
                Assert.assertEquals("$path: length", e.length(), array.length())
                for (i in 0 until e.length()) assertJsonEquals("$path[$i]", e.opt(i), array.opt(i))
            }
            is Boolean -> Assert.assertEquals(path, e, a)
            is Number -> {
                Assert.assertTrue("$path: expected a number, got $a", a is Number)
                Assert.assertEquals("$path: $e vs $a", 0, BigDecimal(e.toString()).compareTo(BigDecimal(a.toString())))
            }
            else -> Assert.assertEquals(path, e, a)
        }
    }
}

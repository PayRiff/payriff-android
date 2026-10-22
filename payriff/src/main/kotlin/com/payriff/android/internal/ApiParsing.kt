package com.payriff.android.internal

import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/** The Payriff response envelope: its success code, its message and safe JSON parsing (CONTRACT §16.3). */
internal object ApiParsing {

    const val SUCCESS_CODE = "00000"
    private val SUPPORT_TICKET = Regex("Support ticket:\\s*(\\d+)")

    /**
     * Splits Payriff's message at the first newline: the trimmed first line is the message, and the digits
     * after `Support ticket:` in the rest are the ticket.
     */
    fun splitMessage(raw: String?): Pair<String?, String?> {
        if (raw == null) return null to null
        val newline = raw.indexOf('\n')
        val first = (if (newline < 0) raw else raw.substring(0, newline)).trim().takeIf { it.isNotEmpty() }
        val rest = if (newline < 0) "" else raw.substring(newline + 1)
        val ticket = SUPPORT_TICKET.find(rest)?.groupValues?.get(1)
        return first to ticket
    }

    /**
     * The body as a JSON object, or `null` when it is empty, not JSON, or not an object. `org.json` parses nested
     * values recursively, so a pathologically deep body overflows the stack; that is treated as unparseable too,
     * never as a crash. Only [StackOverflowError] is caught, not every `Error`.
     */
    fun parseObject(body: String): JSONObject? {
        if (body.isBlank()) return null
        return try {
            JSONTokener(body).nextValue() as? JSONObject
        } catch (e: JSONException) {
            null
        } catch (e: StackOverflowError) {
            null
        }
    }
}

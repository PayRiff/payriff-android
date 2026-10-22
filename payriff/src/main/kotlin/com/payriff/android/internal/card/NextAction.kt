package com.payriff.android.internal.card

import com.payriff.android.internal.Urls
import org.json.JSONArray
import org.json.JSONObject

/**
 * The next step after `/pay` or `/three-ds` (CONTRACT §16.7, `card_next_action.json`). Every URL is an absolute https
 * URL. The 3-D Secure values ([Challenge.form], [Method.methodData]) are bank data, never logged: every [toString]
 * leaves them out.
 */
internal sealed class NextAction {

    /** Open the 3DS sheet and POST [form] (`application/x-www-form-urlencoded`) to [url]. */
    class Challenge(val url: String, val form: List<Pair<String, String>>) : NextAction() {
        override fun toString(): String = "Challenge(url=${Urls.redact(url)}, fields=${form.size})"
    }

    /** 3DS Method: POST `threeDSMethodData` to [methodUrl] and an empty POST to [methodUrlServer], each when set. */
    class Method(val methodUrl: String?, val methodData: String?, val methodUrlServer: String?) : NextAction() {
        /** Whether there is a hidden POST to make. */
        val hasWork: Boolean get() = methodUrl != null || methodUrlServer != null

        override fun toString(): String =
            "Method(methodUrl=${methodUrl?.let(Urls::redact)}, methodUrlServer=${methodUrlServer?.let(Urls::redact)})"
    }

    /** Wait a second, then ask `/three-ds` again. */
    object Wait : NextAction() {
        override fun toString(): String = "Wait"
    }

    /** Open the 3DS sheet with [url] (GET). */
    class Redirect(val url: String) : NextAction() {
        override fun toString(): String = "Redirect(url=${Urls.redact(url)})"
    }

    /** Poll the status. */
    object CheckStatus : NextAction() {
        override fun toString(): String = "CheckStatus"
    }

    /** Unknown or unsafe: one status check decides. */
    object Invalid : NextAction() {
        override fun toString(): String = "Invalid"
    }

    companion object {
        private const val METHOD_URL = "threeDSMethodUrl"
        private const val METHOD_DATA = "threeDSMethodData"
        private const val METHOD_URL_SERVER = "threeDSMethodUrlServer"

        /** Classifies a `/pay` or `/three-ds` payload. */
        fun parse(payload: JSONObject?): NextAction {
            if (payload == null) return Invalid
            return when (payload.opt("nextAction") as? String) {
                "THREE_DS" -> threeDs(payload)
                "WAIT" -> Wait
                "REDIRECT" -> (payload.opt("redirectUrl") as? String)?.takeIf(Urls::isAbsoluteHttpsUrl)?.let(::Redirect) ?: Invalid
                "CHECK_STATUS" -> CheckStatus
                else -> Invalid
            }
        }

        private fun threeDs(payload: JSONObject): NextAction {
            val pairs = clientRequest(payload.opt("clientRequest"))
            val accessUrl = (payload.opt("accessUrl") as? String)?.takeIf { it.isNotEmpty() }
            if (accessUrl != null) {
                if (!Urls.isAbsoluteHttpsUrl(accessUrl)) return Invalid
                return Challenge(accessUrl, pairs.map { (key, value) -> key to value.orEmpty() })
            }
            val values = LinkedHashMap<String, String?>()
            for ((key, value) in pairs) values[key] = value
            val methodUrl = values[METHOD_URL]?.takeIf { it.isNotEmpty() }
            val methodUrlServer = values[METHOD_URL_SERVER]?.takeIf { it.isNotEmpty() }
            if (methodUrl != null && !Urls.isAbsoluteHttpsUrl(methodUrl)) return Invalid
            if (methodUrlServer != null && !Urls.isAbsoluteHttpsUrl(methodUrlServer)) return Invalid
            return Method(methodUrl, values[METHOD_DATA], methodUrlServer)
        }

        /** Entries whose key is a string and whose value is a string or null; anything else is dropped. */
        private fun clientRequest(value: Any?): List<Pair<String, String?>> {
            val array = value as? JSONArray ?: return emptyList()
            val out = ArrayList<Pair<String, String?>>(array.length())
            for (i in 0 until array.length()) {
                val entry = array.opt(i) as? JSONObject ?: continue
                val key = entry.opt("key") as? String ?: continue
                val raw = entry.opt("value")
                val item = when {
                    raw == null || raw == JSONObject.NULL -> null
                    raw is String -> raw
                    else -> continue
                }
                out += key to item
            }
            return out
        }
    }
}

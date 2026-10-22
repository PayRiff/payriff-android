package com.payriff.example.android

import android.os.Bundle
import org.json.JSONException
import org.json.JSONObject

/**
 * An order your backend created, as the main screen receives it: the `orderId` and the order's `clientSecret`
 * (CONTRACT §16.1). Your server creates the order with its secret key (`POST /api/v3/orders`) and returns them; here
 * they are pasted or typed, then handed to the payment screens.
 */
data class PastedOrder(val orderId: String, val clientSecret: String) {

    /** Never shows the client secret. */
    override fun toString(): String = "PastedOrder(orderId=$orderId, clientSecret=***)"

    /** The order as intent extras or fragment arguments; [from] reads it back. */
    fun toBundle(): Bundle = Bundle().apply {
        putString(KEY_ORDER_ID, orderId)
        putString(KEY_CLIENT_SECRET, clientSecret)
    }

    companion object {
        private val WHITE_SPACE = Regex("\\s+")
        private const val KEY_ORDER_ID = "com.payriff.example.android.extra.ORDER_ID"
        private const val KEY_CLIENT_SECRET = "com.payriff.example.android.extra.CLIENT_SECRET"

        /**
         * Reads [text]: the JSON `{"orderId": "…", "clientSecret": "…"}` your server returned, or the two values
         * separated by white space. Returns `null` when the text holds no order.
         */
        fun parse(text: CharSequence?): PastedOrder? {
            val trimmed = text?.toString()?.trim().orEmpty()
            if (trimmed.startsWith("{")) {
                val json = try {
                    JSONObject(trimmed)
                } catch (e: JSONException) {
                    return null
                }
                return of(json.opt("orderId") as? String, json.opt("clientSecret") as? String)
            }
            val parts = trimmed.split(WHITE_SPACE)
            return if (parts.size == 2) of(parts[0], parts[1]) else null
        }

        /** The order saved by [toBundle], or `null` when [bundle] holds none. */
        fun from(bundle: Bundle?): PastedOrder? =
            bundle?.let { of(it.getString(KEY_ORDER_ID), it.getString(KEY_CLIENT_SECRET)) }

        private fun of(orderId: String?, clientSecret: String?): PastedOrder? {
            val id = orderId?.trim().orEmpty()
            val secret = clientSecret?.trim().orEmpty()
            return if (id.isEmpty() || secret.isEmpty()) null else PastedOrder(id, secret)
        }
    }
}

package com.payriff.android.internal

import android.os.Bundle
import com.payriff.android.PayriffCancelReason
import com.payriff.android.PayriffCardField
import com.payriff.android.PayriffError
import com.payriff.android.PayriffErrorCode
import com.payriff.android.PayriffFieldError
import com.payriff.android.PayriffPayment
import com.payriff.android.PayriffPaymentStatus
import com.payriff.android.PayriffPendingReason
import com.payriff.android.PayriffResult
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Minimal key/value store, implemented by [Bundle] in production and by a map in JVM tests. Only strings and
 * booleans are stored, so no class loading or Parcelable is involved when the system restores data.
 */
internal interface KeyValues {
    fun putString(key: String, value: String?)
    fun putBoolean(key: String, value: Boolean)
    fun getString(key: String): String?
    fun getBoolean(key: String): Boolean
}

internal class BundleKeyValues(val bundle: Bundle) : KeyValues {
    override fun putString(key: String, value: String?) = bundle.putString(key, value)
    override fun putBoolean(key: String, value: Boolean) = bundle.putBoolean(key, value)
    override fun getString(key: String): String? = bundle.getString(key)
    override fun getBoolean(key: String): Boolean = bundle.getBoolean(key, false)
}

internal class MapKeyValues(val map: MutableMap<String, Any?> = LinkedHashMap()) : KeyValues {
    override fun putString(key: String, value: String?) {
        map[key] = value
    }

    override fun putBoolean(key: String, value: Boolean) {
        map[key] = value
    }

    override fun getString(key: String): String? = map[key] as? String
    override fun getBoolean(key: String): Boolean = map[key] as? Boolean ?: false
}

/** Encodes a [PayriffResult] into the result intent of the 3-D Secure sheet. */
internal object ResultCodec {
    private const val TYPE = "payriff.result.type"
    private const val REASON = "payriff.result.reason"
    private const val HAS_PAYMENT = "payriff.result.hasPayment"
    private const val ORDER_ID = "payriff.result.payment.orderId"
    private const val STATUS = "payriff.result.payment.status"
    private const val RAW_STATUS = "payriff.result.payment.rawStatus"
    private const val AMOUNT = "payriff.result.payment.amount"
    private const val CURRENCY = "payriff.result.payment.currency"
    private const val TRANSACTION_ID = "payriff.result.payment.transactionId"
    private const val ERROR_CODE = "payriff.result.error.code"
    private const val ERROR_MESSAGE = "payriff.result.error.message"
    private const val ERROR_RETRYABLE = "payriff.result.error.isRetryable"
    private const val ERROR_HTTP_STATUS = "payriff.result.error.httpStatus"
    private const val ERROR_PAYRIFF_CODE = "payriff.result.error.payriffCode"
    private const val ERROR_SUPPORT_TICKET = "payriff.result.error.supportTicket"
    private const val ERROR_FATAL = "payriff.result.error.isFatal"
    private const val ERROR_FIELDS = "payriff.result.error.fields"

    fun write(result: PayriffResult, out: KeyValues) {
        val type = when (result) {
            is PayriffResult.Success -> "success"
            is PayriffResult.Declined -> "declined"
            is PayriffResult.Cancelled -> "cancelled"
            is PayriffResult.Pending -> "pending"
            is PayriffResult.Failed -> "failed"
        }
        out.putString(TYPE, type)
        when (result) {
            is PayriffResult.Cancelled -> out.putString(REASON, result.reason.name)
            is PayriffResult.Pending -> out.putString(REASON, result.reason.name)
            else -> Unit
        }
        val payment = result.payment
        out.putBoolean(HAS_PAYMENT, payment != null)
        if (payment != null) {
            out.putString(ORDER_ID, payment.orderId)
            out.putString(STATUS, payment.status.name)
            out.putString(RAW_STATUS, payment.rawStatus)
            out.putString(AMOUNT, payment.amount?.toPlainString())
            out.putString(CURRENCY, payment.currency)
            out.putString(TRANSACTION_ID, payment.transactionId)
        }
        if (result is PayriffResult.Failed) {
            val error = result.error
            out.putString(ERROR_CODE, error.code.name)
            out.putString(ERROR_MESSAGE, error.message)
            out.putBoolean(ERROR_RETRYABLE, error.isRetryable)
            out.putString(ERROR_HTTP_STATUS, error.httpStatus?.toString())
            out.putString(ERROR_PAYRIFF_CODE, error.payriffCode)
            out.putString(ERROR_SUPPORT_TICKET, error.supportTicket)
            out.putBoolean(ERROR_FATAL, error.isFatal)
            if (error.fields.isNotEmpty()) out.putString(ERROR_FIELDS, encodeFields(error.fields))
        }
    }

    fun read(input: KeyValues): PayriffResult? {
        val type = input.getString(TYPE) ?: return null
        val payment = if (input.getBoolean(HAS_PAYMENT)) readPayment(input) else null
        val reason = input.getString(REASON)
        return when (type) {
            "success" -> payment?.let { PayriffResult.Success(it) }
            "declined" -> payment?.let { PayriffResult.Declined(it) }
            "cancelled" -> PayriffResult.Cancelled(
                payment = payment,
                reason = PayriffCancelReason.entries.firstOrNull { it.name == reason } ?: PayriffCancelReason.USER_CLOSED,
            )
            "pending" -> payment?.let {
                PayriffResult.Pending(
                    payment = it,
                    reason = PayriffPendingReason.entries.firstOrNull { r -> r.name == reason } ?: PayriffPendingReason.NOT_FINAL,
                )
            }
            "failed" -> PayriffResult.Failed(readError(input))
            else -> null
        }
    }

    private fun readPayment(input: KeyValues): PayriffPayment? {
        val orderId = input.getString(ORDER_ID) ?: return null
        return PayriffPayment(
            orderId = orderId,
            status = input.getString(STATUS)?.let { name -> PayriffPaymentStatus.entries.firstOrNull { it.name == name } }
                ?: PayriffPaymentStatus.UNKNOWN,
            rawStatus = input.getString(RAW_STATUS),
            amount = input.getString(AMOUNT)?.toBigDecimalOrNull(),
            currency = input.getString(CURRENCY),
            transactionId = input.getString(TRANSACTION_ID),
        )
    }

    private fun readError(input: KeyValues): PayriffError = PayriffError(
        code = input.getString(ERROR_CODE)?.let { name -> PayriffErrorCode.entries.firstOrNull { it.name == name } }
            ?: PayriffErrorCode.UNKNOWN,
        message = input.getString(ERROR_MESSAGE) ?: "Unknown error.",
        isRetryable = input.getBoolean(ERROR_RETRYABLE),
        httpStatus = input.getString(ERROR_HTTP_STATUS)?.toIntOrNull(),
        payriffCode = input.getString(ERROR_PAYRIFF_CODE),
        supportTicket = input.getString(ERROR_SUPPORT_TICKET),
        isFatal = input.getBoolean(ERROR_FATAL),
        fields = input.getString(ERROR_FIELDS)?.let(::decodeFields).orEmpty(),
    )

    /** Field errors of a validation failure: field, code and localized message only, never input (CONTRACT §16.4). */
    private fun encodeFields(fields: List<PayriffFieldError>): String {
        val array = JSONArray()
        for (field in fields) {
            array.put(JSONObject().put("field", field.field.name).put("errorCode", field.errorCode).put("error", field.error))
        }
        return array.toString()
    }

    private fun decodeFields(json: String): List<PayriffFieldError>? = try {
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            val field = PayriffCardField.entries.firstOrNull { it.name == item.optString("field") } ?: return@mapNotNull null
            PayriffFieldError(field, item.optString("errorCode"), item.optString("error"))
        }
    } catch (e: JSONException) {
        null
    }
}

package com.payriff.android.internal

import com.payriff.android.PayriffCancelReason
import com.payriff.android.PayriffError
import com.payriff.android.PayriffPayment
import com.payriff.android.PayriffPendingReason
import com.payriff.android.PayriffResult
import org.json.JSONObject
import java.math.BigDecimal

/** Cross-platform JSON shape of a [PayriffResult] (CONTRACT §5.3): every key present, `null` when unknown. */
internal object ResultJson {

    fun encode(result: PayriffResult): String {
        val json = JSONObject()
        when (result) {
            is PayriffResult.Success -> {
                json.put("type", "success")
                json.put("payment", payment(result.payment))
            }
            is PayriffResult.Declined -> {
                json.put("type", "declined")
                json.put("payment", payment(result.payment))
            }
            is PayriffResult.Cancelled -> {
                json.put("type", "cancelled")
                json.put("reason", cancelReason(result.reason))
                json.put("payment", payment(result.payment))
            }
            is PayriffResult.Pending -> {
                json.put("type", "pending")
                json.put("reason", pendingReason(result.reason))
                json.put("payment", payment(result.payment))
            }
            is PayriffResult.Failed -> {
                json.put("type", "failed")
                json.put("error", error(result.error))
            }
        }
        return json.toString()
    }

    /** Canonical (camelCase) name of a cancel reason. */
    fun cancelReason(reason: PayriffCancelReason): String = when (reason) {
        PayriffCancelReason.USER_CLOSED -> "userClosed"
        PayriffCancelReason.CANCELED -> "canceled"
        PayriffCancelReason.EXPIRED -> "expired"
    }

    /** Canonical (camelCase) name of a pending reason. */
    fun pendingReason(reason: PayriffPendingReason): String = when (reason) {
        PayriffPendingReason.NOT_FINAL -> "notFinal"
        PayriffPendingReason.UNVERIFIED -> "unverified"
        PayriffPendingReason.VERIFICATION_FAILED -> "verificationFailed"
        PayriffPendingReason.UNEXPECTED_STATUS -> "unexpectedStatus"
    }

    private fun payment(payment: PayriffPayment?): Any {
        if (payment == null) return JSONObject.NULL
        return JSONObject()
            .put("orderId", payment.orderId)
            .put("status", payment.status.name)
            .put("rawStatus", payment.rawStatus ?: JSONObject.NULL)
            .put("amount", payment.amount?.let(::normalizedAmount) ?: JSONObject.NULL)
            .put("currency", payment.currency ?: JSONObject.NULL)
            .put("transactionId", payment.transactionId ?: JSONObject.NULL)
    }

    /** The amount as a JSON number: no trailing zeros and no exponent. */
    private fun normalizedAmount(amount: BigDecimal): BigDecimal {
        val stripped = amount.stripTrailingZeros()
        return if (stripped.scale() < 0) stripped.setScale(0) else stripped
    }

    private fun error(error: PayriffError): JSONObject = JSONObject()
        .put("code", error.code.name)
        .put("message", error.message)
        .put("isRetryable", error.isRetryable)
        .put("httpStatus", error.httpStatus ?: JSONObject.NULL)
        .put("payriffCode", error.payriffCode ?: JSONObject.NULL)
        .put("supportTicket", error.supportTicket ?: JSONObject.NULL)
}

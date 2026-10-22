package com.payriff.android.vectors

import com.payriff.android.PayriffCancelReason
import com.payriff.android.PayriffError
import com.payriff.android.PayriffErrorCode
import com.payriff.android.PayriffPayment
import com.payriff.android.PayriffPaymentStatus
import com.payriff.android.PayriffPendingReason
import com.payriff.android.PayriffResult
import com.payriff.android.internal.MapKeyValues
import com.payriff.android.internal.ResultCodec
import com.payriff.android.internal.ResultJson
import com.payriff.android.testing.Vectors
import com.payriff.android.testing.Vectors.assertJsonEquals
import com.payriff.android.testing.Vectors.keyNames
import com.payriff.android.testing.Vectors.stringOrNull
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * vectors/result_json.json (CONTRACT §5.3): each case builds the result the JSON describes, serialises it with
 * [PayriffResult.toJson] and compares the JSON structurally. Every key must be present, `null` when unknown.
 */
class ResultJsonVectorsTest {

    @Test
    fun everyResultSerialisesToTheSharedShape() {
        val cases = Vectors.cases("result_json.json")
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            val name = case.getString("name")
            val expected = case.getJSONObject("json")
            val result = resultFrom(expected)
            val actual = JSONObject(result.toJson())
            assertJsonEquals(name, expected, actual)
            // The same result crosses the Intent boundary of the 3-D Secure sheet unchanged.
            val store = MapKeyValues()
            ResultCodec.write(result, store)
            assertEquals("$name (Intent codec)", result, ResultCodec.read(store))
        }
        println("result_json.json: ${cases.size} cases passed")
    }

    /** The §5.3 key sets, independent of the vector file: `reason` only on cancelled and pending. */
    @Test
    fun everyVariantHasTheContractKeys() {
        val payment = PayriffPayment("o1", PayriffPaymentStatus.UNKNOWN)
        val results = listOf(
            PayriffResult.Success(payment),
            PayriffResult.Declined(payment),
            PayriffResult.Cancelled(null, PayriffCancelReason.USER_CLOSED),
            PayriffResult.Cancelled(payment, PayriffCancelReason.CANCELED),
            PayriffResult.Pending(payment, PayriffPendingReason.UNVERIFIED),
            PayriffResult.Failed(PayriffError(PayriffErrorCode.UNKNOWN, "x")),
        )
        for (result in results) {
            val json = JSONObject(result.toJson())
            val type = json.getString("type")
            val expectedKeys = when (type) {
                "success", "declined" -> setOf("type", "payment")
                "cancelled", "pending" -> setOf("type", "reason", "payment")
                "failed" -> setOf("type", "error")
                else -> throw AssertionError("Unknown result type $type")
            }
            assertEquals(type, expectedKeys, json.keyNames())
            if (json.has("payment") && !json.isNull("payment")) {
                assertEquals(PAYMENT_KEYS, json.getJSONObject("payment").keyNames())
            }
            if (json.has("error")) assertEquals(ERROR_KEYS, json.getJSONObject("error").keyNames())
        }
    }

    private companion object {
        val PAYMENT_KEYS = setOf("orderId", "status", "rawStatus", "amount", "currency", "transactionId")
        val ERROR_KEYS = setOf("code", "message", "isRetryable", "httpStatus", "payriffCode", "supportTicket")
    }

    private fun resultFrom(json: JSONObject): PayriffResult {
        val payment = if (json.has("payment") && !json.isNull("payment")) paymentFrom(json.getJSONObject("payment")) else null
        return when (val type = json.getString("type")) {
            "success" -> PayriffResult.Success(checkNotNull(payment))
            "declined" -> PayriffResult.Declined(checkNotNull(payment))
            "cancelled" -> PayriffResult.Cancelled(payment, PayriffCancelReason.entries.single { ResultJson.cancelReason(it) == json.getString("reason") })
            "pending" -> PayriffResult.Pending(
                checkNotNull(payment),
                PayriffPendingReason.entries.single { ResultJson.pendingReason(it) == json.getString("reason") },
            )
            "failed" -> PayriffResult.Failed(errorFrom(json.getJSONObject("error")))
            else -> throw AssertionError("Unknown result type $type")
        }
    }

    private fun paymentFrom(json: JSONObject) = PayriffPayment(
        orderId = json.getString("orderId"),
        status = PayriffPaymentStatus.valueOf(json.getString("status")),
        rawStatus = json.stringOrNull("rawStatus"),
        amount = if (json.isNull("amount")) null else BigDecimal(json.get("amount").toString()),
        currency = json.stringOrNull("currency"),
        transactionId = json.stringOrNull("transactionId"),
    )

    private fun errorFrom(json: JSONObject) = PayriffError(
        code = PayriffErrorCode.valueOf(json.getString("code")),
        message = json.getString("message"),
        isRetryable = json.getBoolean("isRetryable"),
        httpStatus = if (json.isNull("httpStatus")) null else json.getInt("httpStatus"),
        payriffCode = json.stringOrNull("payriffCode"),
        supportTicket = json.stringOrNull("supportTicket"),
    )
}

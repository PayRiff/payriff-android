package com.payriff.android.internal

import com.payriff.android.PayriffCancelReason
import com.payriff.android.PayriffError
import com.payriff.android.PayriffErrorCode
import com.payriff.android.PayriffPayment
import com.payriff.android.PayriffPaymentStatus
import com.payriff.android.PayriffPendingReason
import com.payriff.android.PayriffResult
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** The 3-D Secure sheet's result codec (through the same key/value interface the Bundle adapter implements) and the JSON shape. */
class CodecTest {

    private val payment = PayriffPayment(
        orderId = "order-1",
        status = PayriffPaymentStatus.APPROVED,
        rawStatus = "approved",
        amount = BigDecimal("10.50"),
        currency = "AZN",
        transactionId = "884213",
    )

    @Test
    fun everyResultVariantRoundTrips() {
        val results = listOf(
            PayriffResult.Success(payment),
            PayriffResult.Declined(PayriffPayment("o", PayriffPaymentStatus.DECLINED, "DECLINED")),
            PayriffResult.Cancelled(null, PayriffCancelReason.USER_CLOSED),
            PayriffResult.Cancelled(payment, PayriffCancelReason.EXPIRED),
            PayriffResult.Pending(PayriffPayment("o", PayriffPaymentStatus.UNKNOWN), PayriffPendingReason.NOT_FINAL),
            PayriffResult.Pending(payment, PayriffPendingReason.UNEXPECTED_STATUS),
            PayriffResult.Failed(
                PayriffError(PayriffErrorCode.PAYMENT_FAILED, "Insufficient funds", true, 402, "15002", "123456"),
            ),
            PayriffResult.Failed(PayriffError(PayriffErrorCode.NETWORK_ERROR, "offline", isRetryable = true)),
        )
        for (result in results) {
            val store = MapKeyValues()
            ResultCodec.write(result, store)
            assertTrue("only strings and booleans cross the Intent boundary", store.map.values.all { it == null || it is String || it is Boolean })
            assertEquals(result, ResultCodec.read(store))
        }
    }

    @Test
    fun missingOrUnknownResultDataDecodesToNull() {
        assertNull(ResultCodec.read(MapKeyValues()))
        assertNull(ResultCodec.read(MapKeyValues(mutableMapOf("payriff.result.type" to "bogus"))))
    }

    @Test
    fun resultJsonShape() {
        val success = JSONObject(PayriffResult.Success(payment).toJson())
        assertEquals("success", success.getString("type"))
        val json = success.getJSONObject("payment")
        assertEquals("order-1", json.getString("orderId"))
        assertEquals("APPROVED", json.getString("status"))
        assertEquals("approved", json.getString("rawStatus"))
        assertEquals(0, BigDecimal(json.get("amount").toString()).compareTo(BigDecimal("10.5")))
        assertEquals("AZN", json.getString("currency"))
        assertEquals("884213", json.getString("transactionId"))
        assertFalse("the adoption rule is gone (revision 5)", json.has("originalOrderId"))

        val cancelled = JSONObject(PayriffResult.Cancelled(null, PayriffCancelReason.USER_CLOSED).toJson())
        assertEquals("cancelled", cancelled.getString("type"))
        assertEquals("userClosed", cancelled.getString("reason"))
        assertTrue(cancelled.isNull("payment"))

        val pending = JSONObject(PayriffResult.Pending(payment, PayriffPendingReason.NOT_FINAL).toJson())
        assertEquals("notFinal", pending.getString("reason"))

        val failed = JSONObject(
            PayriffResult.Failed(PayriffError(PayriffErrorCode.RATE_LIMITED, "slow down", true, 429)).toJson(),
        ).getJSONObject("error")
        assertEquals("RATE_LIMITED", failed.getString("code"))
        assertEquals("slow down", failed.getString("message"))
        assertTrue(failed.getBoolean("isRetryable"))
        assertEquals(429, failed.getInt("httpStatus"))
        assertTrue(failed.isNull("payriffCode"))
    }

    @Test
    fun canonicalReasonNamesCoverEveryValue() {
        assertEquals(
            listOf("userClosed", "canceled", "expired"),
            PayriffCancelReason.entries.map(ResultJson::cancelReason),
        )
        assertEquals(
            listOf("notFinal", "unverified", "verificationFailed", "unexpectedStatus"),
            PayriffPendingReason.entries.map(ResultJson::pendingReason),
        )
    }
}

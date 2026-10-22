package com.payriff.android.vectors

import com.payriff.android.PayriffPaymentStatus
import com.payriff.android.internal.ResultJson
import com.payriff.android.internal.StatusOutcome
import com.payriff.android.testing.Vectors
import com.payriff.android.testing.Vectors.stringOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** vectors/status.json: status normalization and outcome groups (CONTRACT §8.1–§8.2). */
class StatusVectorsTest {

    @Test
    fun everyStatusCaseMatches() {
        val cases = Vectors.cases("status.json")
        assertTrue(cases.isNotEmpty())
        val failures = cases.mapNotNull { case ->
            val raw = case.stringOrNull("raw")
            val status = PayriffPaymentStatus.fromRaw(raw)
            val outcome = StatusOutcome.of(status)
            val reason = StatusOutcome.cancelReasonOf(status)?.let(ResultJson::cancelReason)
            val actual = Triple(status.name, canonical(outcome), reason)
            val expected = Triple(case.getString("status"), case.getString("outcome"), case.stringOrNull("cancelReason"))
            if (actual == expected) null else "raw=$raw: expected $expected, got $actual"
        }
        assertEquals("Failing cases:\n" + failures.joinToString("\n"), emptyList<String>(), failures)
        println("status.json: ${cases.size} cases passed")
    }

    @Test
    fun everyCanonicalStatusParsesToItself() {
        PayriffPaymentStatus.entries.filter { it != PayriffPaymentStatus.UNKNOWN }.forEach {
            assertEquals(it, PayriffPaymentStatus.fromRaw(it.name))
        }
    }

    private fun canonical(outcome: StatusOutcome): String = when (outcome) {
        StatusOutcome.SUCCESS -> "success"
        StatusOutcome.DECLINED -> "declined"
        StatusOutcome.CANCELLED -> "cancelled"
        StatusOutcome.UNEXPECTED_FINAL -> "unexpectedFinal"
        StatusOutcome.NON_FINAL -> "nonFinal"
    }
}

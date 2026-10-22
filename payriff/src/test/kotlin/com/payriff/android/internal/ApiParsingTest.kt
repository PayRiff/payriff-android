package com.payriff.android.internal

import com.payriff.android.PayriffErrorCode
import com.payriff.android.internal.card.CardApiErrors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Parsing hardening (audit AND-09) of the Payriff envelope. */
class ApiParsingTest {

    /**
     * `org.json` parses nested values recursively. A pathologically deep body (only possible if TLS were broken) used
     * to throw StackOverflowError past every `catch (Exception)`; it is now an ordinary unparseable body.
     */
    @Test
    fun aDeeplyNestedBodyIsUnparseableNotACrash() {
        val depth = 200_000
        val body = "[".repeat(depth) + "]".repeat(depth)
        assertNull(ApiParsing.parseObject(body))
        val objects = "{\"a\":".repeat(depth) + "1" + "}".repeat(depth)
        assertNull(ApiParsing.parseObject(objects))
        // The card flow then maps the response by its HTTP status alone.
        assertEquals(PayriffErrorCode.PAYMENT_FAILED, CardApiErrors.map(502, objects).code)
        assertEquals(PayriffErrorCode.SESSION_EXPIRED, CardApiErrors.map(404, body).code)
    }

    @Test
    fun ordinaryBodiesStillParse() {
        assertEquals("00000", ApiParsing.parseObject("""{"code":"00000","payload":{"a":[[1]]}}""")?.getString("code"))
        assertNull(ApiParsing.parseObject("[1]"))
        assertNull(ApiParsing.parseObject("not json"))
        assertNull(ApiParsing.parseObject("  "))
    }

    @Test
    fun theSupportTicketIsSplitFromTheMessage() {
        assertEquals("Card declined" to "123456", ApiParsing.splitMessage("  Card declined \n Support ticket: 123456"))
        assertEquals("Card declined" to null, ApiParsing.splitMessage("Card declined"))
        assertEquals(null to null, ApiParsing.splitMessage(null))
    }
}

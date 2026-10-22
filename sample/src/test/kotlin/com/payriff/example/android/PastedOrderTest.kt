package com.payriff.example.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Paste on the main screen: the order JSON your server returns, or the two values separated by white space. */
class PastedOrderTest {

    @Test
    fun readsTheOrderJson() {
        assertEquals(PastedOrder("o1", "pr_cs_abc"), PastedOrder.parse("""{"orderId": " o1 ", "clientSecret": "pr_cs_abc"}"""))
        // Other fields, white space and new lines around it are fine.
        assertEquals(
            PastedOrder("o1", "pr_cs_abc"),
            PastedOrder.parse("\n  {\"orderId\":\"o1\",\"clientSecret\":\"pr_cs_abc\",\"amount\":1.02}\n"),
        )
    }

    @Test
    fun readsTwoValuesSeparatedByWhiteSpace() {
        assertEquals(PastedOrder("o2", "pr_cs_def"), PastedOrder.parse("  o2\n pr_cs_def "))
        assertEquals(PastedOrder("o3", "pr_cs_ghi"), PastedOrder.parse("o3\tpr_cs_ghi"))
    }

    @Test
    fun anythingElseHoldsNoOrder() {
        for (text in listOf(
            null,
            "",
            "   ",
            "o3",
            "a b c",
            """{"orderId":"o4"}""",
            """{"orderId":"o4","clientSecret":"  "}""",
            """{"orderId":1,"clientSecret":"pr_cs_x"}""",
            """{"orderId":"o5","clientSecret":null}""",
            """{"orderId":""",
            """["o5","pr_cs_x"]""",
        )) {
            assertNull(text, PastedOrder.parse(text))
        }
    }

    @Test
    fun neverShowsTheClientSecret() {
        val text = PastedOrder("o1", "pr_cs_secret").toString()
        assertEquals("PastedOrder(orderId=o1, clientSecret=***)", text)
        assertFalse("pr_cs_secret" in text)
    }
}

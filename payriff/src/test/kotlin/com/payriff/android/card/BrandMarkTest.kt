package com.payriff.android.card

import com.payriff.android.PayriffCardBrand
import com.payriff.android.internal.card.BrandMarks
import com.payriff.android.internal.card.PathCommand
import com.payriff.android.internal.card.PathData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The brand marks of the card number field (CONTRACT §16.4) and the path parser behind the Visa wordmark. */
class BrandMarkTest {

    @Test
    fun visaWordmarkParsesIntoItsFiveShapes() {
        val commands = BrandMarks.visaCommands
        assertEquals(45, commands.size)
        assertEquals(5, commands.count { it is PathCommand.MoveTo })
        assertEquals(21, commands.count { it is PathCommand.LineTo })
        assertEquals(15, commands.count { it is PathCommand.CubicTo })
        assertEquals(4, commands.count { it === PathCommand.Close })
        assertTrue("starts with a move", commands.first() is PathCommand.MoveTo)
        // 5 move and 21 line end points, 15 curves of three points each.
        assertEquals(71, PathData.points(commands).size)
    }

    @Test
    fun visaWordmarkSpansItsDocumentedBox() {
        // CONTRACT §16.4: a 24-unit box; the wordmark covers x 0…24 and y 8.124…15.875 (control points included).
        val points = PathData.points(BrandMarks.visaCommands)
        assertEquals(0f, points.minOf { it.first }, DELTA)
        assertEquals(BrandMarks.VISA_BOX, points.maxOf { it.first }, DELTA)
        assertEquals(BrandMarks.VISA_TOP, points.minOf { it.second }, DELTA)
        assertEquals(BrandMarks.VISA_BOTTOM, points.maxOf { it.second }, DELTA)
        val first = BrandMarks.visaCommands.first() as PathCommand.MoveTo
        assertEquals(9.112f, first.x, DELTA)
        assertEquals(8.262f, first.y, DELTA)
    }

    @Test
    fun parserReadsCommandsWithOrWithoutSeparators() {
        val commands = PathData.parse("M1 2L3,4 C5 6 7 8 9 10Z")
        assertEquals(4, commands.size)
        val line = commands[1] as PathCommand.LineTo
        assertEquals(3f, line.x, DELTA)
        assertEquals(4f, line.y, DELTA)
        val curve = commands[2] as PathCommand.CubicTo
        assertEquals(listOf(5f, 6f, 7f, 8f, 9f, 10f), listOf(curve.x1, curve.y1, curve.x2, curve.y2, curve.x, curve.y))
        assertTrue(commands[3] === PathCommand.Close)
        assertEquals(emptyList<PathCommand>(), PathData.parse("  "))
    }

    @Test
    fun parserRefusesWhatTheContractDoesNotUse() {
        for (data in listOf("m1 2", "M1 2 h3", "M1 2 3 4", "M1", "C1 2 3 4 5", "M1 x", "M1 NaN", "Q1 2 3 4")) {
            try {
                PathData.parse(data)
                fail("'$data' should be refused")
            } catch (expected: IllegalArgumentException) {
                // Only absolute M/L/C/Z with every number present.
            }
        }
    }

    @Test
    fun marksAreLabelledWithTheBrandName() {
        assertEquals("Visa", BrandMarks.label(PayriffCardBrand.VISA))
        assertEquals("Mastercard", BrandMarks.label(PayriffCardBrand.MASTERCARD))
        assertNull(BrandMarks.label(null))
    }

    @Test
    fun badgeAndMastercardGeometryFollowTheContract() {
        assertEquals(36f, BrandMarks.BADGE_WIDTH, DELTA)
        assertEquals(24f, BrandMarks.BADGE_HEIGHT, DELTA)
        assertEquals(4f, BrandMarks.BADGE_CORNER, DELTA)
        assertEquals(1f, BrandMarks.BADGE_BORDER, DELTA)
        assertEquals(26f, BrandMarks.MARK_WIDTH, DELTA)
        // The circles are 0.382 w apart and the pair is centred in the mark.
        assertEquals(0.382f, BrandMarks.MASTERCARD_RIGHT_CENTRE - BrandMarks.MASTERCARD_RADIUS, DELTA)
        assertEquals(1f, BrandMarks.MASTERCARD_RIGHT_CENTRE + BrandMarks.MASTERCARD_RADIUS, DELTA)
        // CONTRACT §16.4: the marks cross-fade in at most 150 ms.
        assertEquals(150L, BrandMarks.CROSS_FADE_MILLIS)
        assertEquals(0xFF1A1F71.toInt(), BrandMarks.VISA_BLUE)
        assertEquals(0xFFEB001B.toInt(), BrandMarks.MASTERCARD_RED)
        assertEquals(0xFFF79E1B.toInt(), BrandMarks.MASTERCARD_YELLOW)
        assertEquals(0xFFFF5F00.toInt(), BrandMarks.MASTERCARD_ORANGE)
        assertEquals(0xFFD1D1D6.toInt(), BrandMarks.BADGE_STROKE)
        assertEquals(0xFFFFFFFF.toInt(), BrandMarks.BADGE_FILL)
    }

    private companion object {
        const val DELTA = 1e-4f
    }
}

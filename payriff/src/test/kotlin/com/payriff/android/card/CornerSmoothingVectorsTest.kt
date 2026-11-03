package com.payriff.android.card

import com.payriff.android.internal.card.PathCommand
import com.payriff.android.internal.card.PathData
import com.payriff.android.internal.card.Squircle
import com.payriff.android.testing.Vectors
import com.payriff.android.testing.Vectors.objects
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * `corner_smoothing.json` (CONTRACT §18.2): Figma's corner smoothing as in `figma-squircle` with `preserveSmoothing`
 * false. Every case's corner parameters and top-right points, the mirrored corners, smoothing 0 as the circular rounded
 * rectangle, and the border inside the bounds. Tolerance 0.001.
 */
class CornerSmoothingVectorsTest {

    @Test
    fun everyCaseHasTheVectorsCornerAndTopRightPoints() {
        val vector = Vectors.load("corner_smoothing.json")
        // The formulas of `algorithm` are what Squircle.corner and Squircle.outline compute; the cases check them.
        assertEquals(6, vector.getJSONArray("algorithm").length())
        val cases = vector.getJSONArray("cases").objects()
        assertEquals(13, cases.size)
        for (case in cases) {
            val name = case.getString("name")
            val width = case.getDouble("width")
            val height = case.getDouble("height")
            val radius = case.getDouble("radius")
            val smoothing = case.getDouble("smoothing")
            val expect = case.getJSONObject("expect")
            val corner = Squircle.corner(width, height, radius, smoothing)
            for ((key, actual) in listOf(
                "radius" to corner.radius,
                "smoothing" to corner.smoothing,
                "p" to corner.p,
                "a" to corner.a,
                "b" to corner.b,
                "c" to corner.c,
                "d" to corner.d,
                "arcSectionLength" to corner.arcSectionLength,
                "arcMeasure" to corner.arcMeasure,
            )) {
                assertEquals("$name: $key", expect.getDouble(key), actual, TOLERANCE)
            }

            val outline = Squircle.outline(0.0, 0.0, width, height, radius, smoothing)
            val topRight = expect.getJSONObject("topRight")
            val start = outline[0] as PathCommand.MoveTo
            assertPoint("$name: start", topRight.getJSONArray("start"), start.x, start.y)
            val cubic1 = outline[1] as PathCommand.CubicTo
            assertCubic("$name: cubic1", topRight.getJSONArray("cubic1"), cubic1)
            val arc = outline[2] as PathCommand.ArcTo
            val expectedArc = topRight.getJSONObject("arc")
            assertPoint("$name: arc centre", expectedArc.getJSONArray("center"), arc.centerX, arc.centerY)
            assertEquals("$name: arc radius", expectedArc.getDouble("radius"), arc.radius.toDouble(), TOLERANCE)
            assertEquals("$name: arc start", expectedArc.getDouble("startAngle"), arc.startAngle.toDouble(), TOLERANCE)
            assertEquals("$name: arc end", expectedArc.getDouble("endAngle"), (arc.startAngle + arc.sweepAngle).toDouble(), TOLERANCE)
            assertCubic("$name: cubic2", topRight.getJSONArray("cubic2"), outline[3] as PathCommand.CubicTo)
            // The pieces join: the first cubic ends where the arc starts.
            val arcStart = Math.toRadians(arc.startAngle.toDouble())
            assertEquals(name, cubic1.x.toDouble(), arc.centerX + arc.radius * cos(arcStart), TOLERANCE)
            assertEquals(name, cubic1.y.toDouble(), arc.centerY + arc.radius * sin(arcStart), TOLERANCE)

            // "The other corners are the same shape mirrored." Mirroring in one axis reverses the direction of travel.
            val tr = corner(outline, TOP_RIGHT)
            assertPoints("$name: bottom right", tr.map { (x, y) -> x to height - y }.reversed(), corner(outline, BOTTOM_RIGHT))
            assertPoints("$name: bottom left", tr.map { (x, y) -> width - x to height - y }, corner(outline, BOTTOM_LEFT))
            assertPoints("$name: top left", tr.map { (x, y) -> width - x to y }.reversed(), corner(outline, TOP_LEFT))
            assertTrue(name, outline.last() === PathCommand.Close)
        }
    }

    @Test
    fun smoothingZeroIsTheCircularRoundedRectangle() {
        for ((width, height, radius) in listOf(Triple(300.0, 48.0, 12.0), Triple(300.0, 44.0, 16.0), Triple(42.0, 28.0, 4.0))) {
            val corner = Squircle.corner(width, height, radius, 0.0)
            assertEquals(0.0, corner.smoothing, 0.0)
            assertEquals(radius, corner.p, TOLERANCE)
            val outline = Squircle.outline(0.0, 0.0, width, height, radius, 0.0)
            val arcs = outline.filterIsInstance<PathCommand.ArcTo>()
            // Quarter circles of the radius around the corners' centres, clockwise from the top right ...
            assertEquals(listOf(90f, 90f, 90f, 90f), arcs.map { it.sweepAngle })
            assertEquals(listOf(-90f, 0f, 90f, 180f), arcs.map { it.startAngle })
            assertTrue(arcs.all { it.radius == radius.toFloat() })
            assertEquals(
                listOf(width - radius to radius, width - radius to height - radius, radius to height - radius, radius to radius),
                arcs.map { it.centerX.toDouble() to it.centerY.toDouble() },
            )
            // ... and cubics that are single points, so only the arcs and the straight sides remain.
            for (cubic in outline.filterIsInstance<PathCommand.CubicTo>()) {
                for ((x, y) in listOf(cubic.x1 to cubic.y1, cubic.x2 to cubic.y2)) {
                    assertEquals(cubic.x.toDouble(), x.toDouble(), TOLERANCE)
                    assertEquals(cubic.y.toDouble(), y.toDouble(), TOLERANCE)
                }
            }
        }
    }

    @Test
    fun smoothingIsClampedAndTheBorderRunsInsideTheBounds() {
        assertEquals(0f, Squircle.clamp(-0.2f), 0f)
        assertEquals(1f, Squircle.clamp(1.5f), 0f)
        assertEquals(0.6f, Squircle.clamp(0.6f), 0f)
        assertEquals("not a number counts as 0", 0f, Squircle.clamp(Float.NaN), 0f)
        assertEquals("not a number counts as 0", 0.0, Squircle.corner(300.0, 48.0, 12.0, Double.NaN).smoothing, 0.0)
        assertEquals("no radius: square corners", 0.0, Squircle.corner(300.0, 48.0, Double.NaN, 0.6).radius, 0.0)
        // A border of width bw: the shape inset by bw / 2, with radius max(0, R − bw / 2) (CONTRACT §18.2).
        val border = Squircle.border(0f, 0f, 300f, 48f, 12f, 2f)
        assertEquals(listOf(1f, 1f, 299f, 47f, 11f), listOf(border.left, border.top, border.right, border.bottom, border.radius))
        assertEquals(0f, Squircle.border(0f, 0f, 10f, 10f, 0.5f, 3f).radius, 0f)
        // The badges' frames: 1 dp inside a 42×28 badge with outer radius 4, as the old framed PNGs (inset 0.5, radius 3.5).
        val frame = Squircle.border(0f, 0f, 42f, 28f, 4f, 1f)
        assertEquals(listOf(0.5f, 0.5f, 41.5f, 27.5f, 3.5f), listOf(frame.left, frame.top, frame.right, frame.bottom, frame.radius))
        // Every point of a smoothed outline lies inside its rectangle.
        val points = PathData.points(Squircle.outline(10.0, 20.0, 36.0, 24.0, 4.0, 1.0))
        assertTrue(points.all { (x, y) -> x in 10f - EPSILON..46f + EPSILON && y in 20f - EPSILON..44f + EPSILON })
    }

    /**
     * The points of the corner whose first cubic is [outline]`[index]`, in the direction of travel: where it starts,
     * the first cubic, the end of the arc and the second cubic.
     */
    private fun corner(outline: List<PathCommand>, index: Int): List<Pair<Double, Double>> {
        val start = when (val before = outline[index - 1]) {
            is PathCommand.MoveTo -> before.x to before.y
            is PathCommand.LineTo -> before.x to before.y
            else -> error("unexpected $before")
        }
        val first = outline[index] as PathCommand.CubicTo
        val arc = outline[index + 1] as PathCommand.ArcTo
        val second = outline[index + 2] as PathCommand.CubicTo
        return listOf(
            start,
            first.x1 to first.y1,
            first.x2 to first.y2,
            first.x to first.y,
            arc.endX to arc.endY,
            second.x1 to second.y1,
            second.x2 to second.y2,
            second.x to second.y,
        ).map { (x, y) -> x.toDouble() to y.toDouble() }
    }

    private fun assertPoints(message: String, expected: List<Pair<Double, Double>>, actual: List<Pair<Double, Double>>) {
        assertEquals(message, expected.size, actual.size)
        for ((i, pair) in expected.zip(actual).withIndex()) {
            assertEquals("$message point $i x", pair.first.first, pair.second.first, TOLERANCE)
            assertEquals("$message point $i y", pair.first.second, pair.second.second, TOLERANCE)
        }
    }

    private fun assertPoint(message: String, expected: JSONArray, x: Float, y: Float) {
        assertEquals("$message x", expected.getDouble(0), x.toDouble(), TOLERANCE)
        assertEquals("$message y", expected.getDouble(1), y.toDouble(), TOLERANCE)
    }

    private fun assertCubic(message: String, expected: JSONArray, cubic: PathCommand.CubicTo) {
        assertPoint("$message control 1", expected.getJSONArray(0), cubic.x1, cubic.y1)
        assertPoint("$message control 2", expected.getJSONArray(1), cubic.x2, cubic.y2)
        assertPoint("$message end", expected.getJSONArray(2), cubic.x, cubic.y)
    }

    private companion object {
        const val TOLERANCE = 0.001
        const val EPSILON = 0.001f

        /** The index of each corner's first cubic in Squircle.outline. */
        const val TOP_RIGHT = 1
        const val BOTTOM_RIGHT = 5
        const val BOTTOM_LEFT = 9
        const val TOP_LEFT = 13
    }
}

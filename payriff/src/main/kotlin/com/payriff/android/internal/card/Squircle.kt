package com.payriff.android.internal.card

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.annotation.ColorInt
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Corner smoothing (CONTRACT §18.2, `corner_smoothing.json`): Figma's smoothed rounded rectangle, as in the MIT
 * `figma-squircle` algorithm with `preserveSmoothing` false, with one radius for all four corners. Each corner is a
 * cubic, a circular arc and a cubic; with smoothing 0 the cubics are points and the arc is a quarter circle, so the
 * shape is the circular-arc rounded rectangle. Pure Kotlin, so the JVM unit tests check the geometry; [SquirclePaths]
 * and [SquircleDrawable] draw it.
 */
internal object Squircle {

    /**
     * The shape of every corner of one rectangle, in its units.
     *
     * @property radius The radius, at most half the shorter side; 0 makes the corners square.
     * @property smoothing The smoothing, clamped to 0…1 and then limited by the room the sides leave.
     * @property p How far the corner reaches along each side, from the rectangle's corner.
     * @property arcMeasure The arc's angle in degrees: 90 without smoothing, 0 with full smoothing.
     */
    class Corner(
        val radius: Double,
        val smoothing: Double,
        val p: Double,
        val a: Double,
        val b: Double,
        val c: Double,
        val d: Double,
        val arcSectionLength: Double,
        val arcMeasure: Double,
    )

    /** [smoothing] clamped to 0…1; not a number counts as 0. */
    fun clamp(smoothing: Float): Float = if (smoothing.isNaN()) 0f else smoothing.coerceIn(0f, 1f)

    /** The corners of a [width]×[height] rectangle with [radius] and [smoothing] (the vector's `algorithm`). */
    fun corner(width: Double, height: Double, radius: Double, smoothing: Double): Corner {
        val budget = max(0.0, min(width, height) / 2)
        val clamped = if (smoothing.isNaN()) 0.0 else smoothing.coerceIn(0.0, 1.0)
        // Not a number, or no room: square corners.
        if (!(radius > 0.0) || budget <= 0.0) return Corner(0.0, clamped, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, QUARTER)
        val r = min(radius, budget)
        val p = min((1 + clamped) * r, budget)
        val s = min(clamped, budget / r - 1)
        val arcMeasure = QUARTER * (1 - s)
        val arcSectionLength = sin(Math.toRadians(arcMeasure / 2)) * r * sqrt(2.0)
        val alpha = (QUARTER - arcMeasure) / 2
        val p3ToP4 = r * tan(Math.toRadians(alpha / 2))
        val beta = HALF_QUARTER * s
        val c = p3ToP4 * cos(Math.toRadians(beta))
        val d = c * tan(Math.toRadians(beta))
        val b = (p - arcSectionLength - c - d) / 3
        return Corner(r, s, p, 2 * b, b, c, d, arcSectionLength, arcMeasure)
    }

    /**
     * The outline of the rectangle at ([left], [top]), [width]×[height], clockwise from the top edge: per corner a cubic,
     * an arc and a cubic, joined by the straight sides. Always the full form, so the top-right corner is exactly the
     * vector's `topRight`; the other corners are the same shape mirrored.
     */
    fun outline(left: Double, top: Double, width: Double, height: Double, radius: Double, smoothing: Double): List<PathCommand> {
        val k = corner(width, height, radius, smoothing)
        val right = left + width
        val bottom = top + height
        val p = k.p
        val r = k.radius
        val ab = k.a + k.b
        val abc = ab + k.c
        val sweep = k.arcMeasure
        val half = sweep / 2
        fun cubic(x1: Double, y1: Double, x2: Double, y2: Double, x: Double, y: Double) =
            PathCommand.CubicTo(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), x.toFloat(), y.toFloat())
        fun arc(cx: Double, cy: Double, middle: Double) =
            PathCommand.ArcTo(cx.toFloat(), cy.toFloat(), r.toFloat(), (middle - half).toFloat(), sweep.toFloat())
        fun line(x: Double, y: Double) = PathCommand.LineTo(x.toFloat(), y.toFloat())
        return listOf(
            PathCommand.MoveTo((right - p).toFloat(), top.toFloat()),
            // Top right: along the top edge, round the corner, down the right edge.
            cubic(right - p + k.a, top, right - p + ab, top, right - p + abc, top + k.d),
            arc(right - r, top + r, TOP_RIGHT),
            cubic(right, top + p - ab, right, top + p - k.a, right, top + p),
            line(right, bottom - p),
            // Bottom right.
            cubic(right, bottom - p + k.a, right, bottom - p + ab, right - k.d, bottom - p + abc),
            arc(right - r, bottom - r, BOTTOM_RIGHT),
            cubic(right - p + ab, bottom, right - p + k.a, bottom, right - p, bottom),
            line(left + p, bottom),
            // Bottom left.
            cubic(left + p - k.a, bottom, left + p - ab, bottom, left + p - abc, bottom - k.d),
            arc(left + r, bottom - r, BOTTOM_LEFT),
            cubic(left, bottom - p + ab, left, bottom - p + k.a, left, bottom - p),
            line(left, top + p),
            // Top left.
            cubic(left, top + p - k.a, left, top + p - ab, left + k.d, top + p - abc),
            arc(left + r, top + r, TOP_LEFT),
            cubic(left + p - ab, top, left + p - k.a, top, left + p, top),
            PathCommand.Close,
        )
    }

    /** A rectangle and a radius in pixels. */
    class Box(val left: Float, val top: Float, val right: Float, val bottom: Float, val radius: Float)

    /**
     * Where a border of [strokeWidth] runs (CONTRACT §18.2): the rectangle inset by half the width, with the radius
     * `max(0, radius − strokeWidth / 2)`, so the stroke stays inside the rectangle.
     */
    fun border(left: Float, top: Float, right: Float, bottom: Float, radius: Float, strokeWidth: Float): Box {
        val half = strokeWidth / 2
        return Box(left + half, top + half, right - half, bottom - half, max(0f, radius - half))
    }

    private const val QUARTER = 90.0
    private const val HALF_QUARTER = 45.0

    /** The middle angle of each corner's arc, in degrees clockwise from +x on the screen. */
    private const val TOP_RIGHT = -45.0
    private const val BOTTOM_RIGHT = 45.0
    private const val BOTTOM_LEFT = 135.0
    private const val TOP_LEFT = 225.0
}

/** [Squircle] outlines as [Path]s (CONTRACT §18.2). Main thread only. */
internal object SquirclePaths {

    /**
     * Sets [path] to the outline of the rectangle from ([left], [top]) to ([right], [bottom]) with [radius] and
     * [smoothing]. Without smoothing it is the rounded rectangle of [Path.addRoundRect], the shape the SDK drew before
     * revision 8; without a radius, the rectangle.
     */
    fun set(path: Path, left: Float, top: Float, right: Float, bottom: Float, radius: Float, smoothing: Float) {
        path.rewind()
        val width = right - left
        val height = bottom - top
        if (!(width > 0f) || !(height > 0f)) return
        val corner = Squircle.corner(width.toDouble(), height.toDouble(), radius.toDouble(), smoothing.toDouble())
        val r = corner.radius.toFloat()
        when {
            corner.radius <= 0.0 -> path.addRect(left, top, right, bottom, Path.Direction.CW)
            corner.smoothing <= 0.0 -> path.addRoundRect(left, top, right, bottom, r, r, Path.Direction.CW)
            else -> {
                val outline = Squircle.outline(left.toDouble(), top.toDouble(), width.toDouble(), height.toDouble(), corner.radius, corner.smoothing)
                PathData.append(path, outline)
            }
        }
    }

    /** The border of width [strokeWidth] along the same shape, inside the rectangle ([Squircle.border]). */
    fun setBorder(path: Path, left: Float, top: Float, right: Float, bottom: Float, radius: Float, smoothing: Float, strokeWidth: Float) {
        val box = Squircle.border(left, top, right, bottom, radius, strokeWidth)
        set(path, box.left, box.top, box.right, box.bottom, box.radius, smoothing)
    }
}

/**
 * A rounded rectangle with corner smoothing (CONTRACT §18.2) as a background: the fill along the outline, the border
 * inside it ([SquirclePaths.setBorder]), and the outline for clipping and shadows. With smoothing 0 it is the circular
 * rounded rectangle. It replaces the `GradientDrawable`s of the card form's outlines and the Pay button, and frames the
 * badges. Main thread only.
 */
internal class SquircleDrawable : Drawable() {

    @ColorInt
    private var fillColor: Int = Color.TRANSPARENT

    @ColorInt
    private var strokeColor: Int = Color.TRANSPARENT
    private var strokeWidth = 0f
    private var cornerRadius = 0f
    private var smoothing = 0f
    private var paintAlpha = OPAQUE
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fillPath = Path()
    private val strokePath = Path()

    /** The fill; transparent draws none. */
    fun setFill(@ColorInt color: Int) {
        if (fillColor == color) return
        fillColor = color
        invalidateSelf()
    }

    /** The border, [widthPx] wide in [color]; a width of 0 draws none. */
    fun setStroke(widthPx: Float, @ColorInt color: Int) {
        val width = if (widthPx.isFinite()) max(0f, widthPx) else 0f
        if (strokeWidth == width && strokeColor == color) return
        val reshape = strokeWidth != width
        strokeWidth = width
        strokeColor = color
        if (reshape) updatePaths()
        invalidateSelf()
    }

    /** The corner radius in pixels and the smoothing (0…1). */
    fun setShape(radiusPx: Float, smoothing: Float) {
        val radius = if (radiusPx.isFinite()) max(0f, radiusPx) else 0f
        val clamped = Squircle.clamp(smoothing)
        if (cornerRadius == radius && this.smoothing == clamped) return
        cornerRadius = radius
        this.smoothing = clamped
        updatePaths()
        invalidateSelf()
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        updatePaths()
    }

    override fun draw(canvas: Canvas) {
        if (Color.alpha(fillColor) != 0) {
            fillPaint.color = fillColor
            fillPaint.alpha = modulate(Color.alpha(fillColor))
            canvas.drawPath(fillPath, fillPaint)
        }
        if (strokeWidth > 0f && Color.alpha(strokeColor) != 0) {
            strokePaint.color = strokeColor
            strokePaint.alpha = modulate(Color.alpha(strokeColor))
            strokePaint.strokeWidth = strokeWidth
            canvas.drawPath(strokePath, strokePaint)
        }
    }

    override fun setAlpha(alpha: Int) {
        if (paintAlpha == alpha) return
        paintAlpha = alpha
        invalidateSelf()
    }

    override fun getAlpha(): Int = paintAlpha

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        strokePaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    /** The outline (CONTRACT §18.2: clipping uses it). Before Android 11 a smoothed shape is approximated by its rounded rectangle. */
    override fun getOutline(outline: Outline) {
        val b = bounds
        val corner = Squircle.corner(b.width().toDouble(), b.height().toDouble(), cornerRadius.toDouble(), smoothing.toDouble())
        when {
            corner.radius <= 0.0 -> outline.setRect(b)
            corner.smoothing > 0.0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> outline.setPath(fillPath)
            else -> outline.setRoundRect(b, corner.radius.toFloat())
        }
    }

    private fun updatePaths() {
        val left = bounds.left.toFloat()
        val top = bounds.top.toFloat()
        val right = bounds.right.toFloat()
        val bottom = bounds.bottom.toFloat()
        SquirclePaths.set(fillPath, left, top, right, bottom, cornerRadius, smoothing)
        SquirclePaths.setBorder(strokePath, left, top, right, bottom, cornerRadius, smoothing, strokeWidth)
    }

    private fun modulate(alpha: Int): Int = alpha * paintAlpha / OPAQUE

    private companion object {
        const val OPAQUE = 255
    }
}

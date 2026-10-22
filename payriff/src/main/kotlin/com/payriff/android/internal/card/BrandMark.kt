package com.payriff.android.internal.card

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import com.payriff.android.PayriffCardBrand
import kotlin.math.min
import kotlin.math.roundToInt

/** One command of absolute SVG path data (CONTRACT §16.4 uses `M`, `L`, `C` and `Z` only), in the path's own units. */
internal sealed class PathCommand {
    class MoveTo(val x: Float, val y: Float) : PathCommand()

    class LineTo(val x: Float, val y: Float) : PathCommand()

    class CubicTo(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val x: Float, val y: Float) : PathCommand()

    object Close : PathCommand()
}

/** A tiny parser for absolute M/L/C/Z path data. Pure Kotlin, so the JVM unit tests can check it. */
internal object PathData {

    /**
     * Parses [data]: the commands `M x y`, `L x y`, `C x1 y1 x2 y2 x y` and `Z`, each written out (no implicit
     * repetition), separated by white space or commas.
     *
     * @throws IllegalArgumentException for anything else: relative or other commands, a missing or malformed number.
     */
    fun parse(data: String): List<PathCommand> {
        val tokens = tokenize(data)
        val commands = ArrayList<PathCommand>()
        var i = 0
        fun number(): Float {
            val token = tokens.getOrNull(i++) ?: throw IllegalArgumentException("Path data ends inside a command")
            return token.toFloatOrNull()?.takeIf { it.isFinite() } ?: throw IllegalArgumentException("Unexpected path token '$token'")
        }
        while (i < tokens.size) {
            commands += when (val command = tokens[i++]) {
                "M" -> PathCommand.MoveTo(number(), number())
                "L" -> PathCommand.LineTo(number(), number())
                "C" -> PathCommand.CubicTo(number(), number(), number(), number(), number(), number())
                "Z" -> PathCommand.Close
                else -> throw IllegalArgumentException("Unexpected path token '$command'")
            }
        }
        return commands
    }

    /** Every point of [commands] (end points and control points), in order, as `x` to `y`. */
    fun points(commands: List<PathCommand>): List<Pair<Float, Float>> = commands.flatMap { command ->
        when (command) {
            is PathCommand.MoveTo -> listOf(command.x to command.y)
            is PathCommand.LineTo -> listOf(command.x to command.y)
            is PathCommand.CubicTo -> listOf(command.x1 to command.y1, command.x2 to command.y2, command.x to command.y)
            PathCommand.Close -> emptyList()
        }
    }

    /** Builds an [android.graphics.Path] from [commands]. */
    fun toPath(commands: List<PathCommand>): Path {
        val path = Path()
        for (command in commands) {
            when (command) {
                is PathCommand.MoveTo -> path.moveTo(command.x, command.y)
                is PathCommand.LineTo -> path.lineTo(command.x, command.y)
                is PathCommand.CubicTo -> path.cubicTo(command.x1, command.y1, command.x2, command.y2, command.x, command.y)
                PathCommand.Close -> path.close()
            }
        }
        return path
    }

    /** Command letters become tokens of their own; numbers are split at white space and commas. */
    private fun tokenize(data: String): List<String> {
        val tokens = ArrayList<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) tokens += current.toString()
            current.setLength(0)
        }
        for (c in data) {
            when {
                c == 'M' || c == 'L' || c == 'C' || c == 'Z' -> {
                    flush()
                    tokens += c.toString()
                }
                c.isWhitespace() || c == ',' -> flush()
                else -> current.append(c)
            }
        }
        flush()
        return tokens
    }
}

/** The card marks of CONTRACT §16.4. */
internal object BrandMarks {

    /** The Visa wordmark (Simple Icons, CC0) in a 24-unit box: x 0…24, y 8.124…15.875. */
    const val VISA_PATH_DATA: String =
        "M9.112 8.262 L5.97 15.758 L3.92 15.758 L2.374 9.775 C2.28 9.407 2.199 9.272 1.913 9.117 C1.447 8.864 0.677 " +
            "8.627 0 8.479 L0.046 8.262 L3.346 8.262 C3.792 8.262 4.171 8.586 4.24 9.026 L5.057 13.364 L7.075 8.262 Z " +
            "M17.145 13.311 C17.153 11.332 14.409 11.223 14.428 10.339 C14.434 10.07 14.69 9.784 15.25 9.711 C15.906 " +
            "9.649 16.567 9.765 17.163 10.047 L17.503 8.457 C16.923 8.239 16.309 8.126 15.689 8.124 C13.772 8.124 12.423 " +
            "9.144 12.411 10.603 C12.399 11.682 13.374 12.283 14.109 12.643 C14.865 13.01 15.119 13.246 15.115 13.574 " +
            "C15.11 14.078 14.513 14.299 13.955 14.308 C12.98 14.323 12.415 14.045 11.963 13.835 L11.612 15.477 C12.065 " +
            "15.685 12.901 15.867 13.768 15.875 C15.805 15.875 17.138 14.869 17.145 13.311 M22.206 15.758 L24 15.758 " +
            "L22.435 8.262 L20.779 8.262 C20.417 8.259 20.09 8.477 19.953 8.812 L17.044 15.758 L19.08 15.758 L19.485 " +
            "14.638 L21.973 14.638 Z M20.043 13.102 L21.063 10.287 L21.651 13.102 Z M11.883 8.262 L10.28 15.758 L8.34 " +
            "15.758 L9.945 8.262 Z"

    /** The path box of [VISA_PATH_DATA] and the part of it the wordmark covers. */
    const val VISA_BOX = 24f
    const val VISA_TOP = 8.124f
    const val VISA_BOTTOM = 15.875f

    /** The badge, in dp. */
    const val BADGE_WIDTH = 36f
    const val BADGE_HEIGHT = 24f
    const val BADGE_CORNER = 4f
    const val BADGE_BORDER = 1f

    /** The width of a mark inside the badge, in dp. */
    const val MARK_WIDTH = 26f

    /** Mastercard: each circle's radius and its centre's distance from the mark's left edge, as fractions of its width. */
    const val MASTERCARD_RADIUS = 0.309f
    const val MASTERCARD_RIGHT_CENTRE = 0.691f

    const val BADGE_FILL = 0xFFFFFFFF.toInt()
    const val BADGE_STROKE = 0xFFD1D1D6.toInt()
    const val VISA_BLUE = 0xFF1A1F71.toInt()
    const val MASTERCARD_RED = 0xFFEB001B.toInt()
    const val MASTERCARD_YELLOW = 0xFFF79E1B.toInt()
    const val MASTERCARD_ORANGE = 0xFFFF5F00.toInt()

    /** The longest cross-fade between two marks (CONTRACT §16.4: at most 150 ms). */
    const val CROSS_FADE_MILLIS = 150L

    /** What accessibility services announce for [brand]; `null` while it is unknown. */
    fun label(brand: PayriffCardBrand?): String? = when (brand) {
        PayriffCardBrand.VISA -> "Visa"
        PayriffCardBrand.MASTERCARD -> "Mastercard"
        null -> null
    }

    val visaCommands: List<PathCommand> by lazy { PathData.parse(VISA_PATH_DATA) }
}

/**
 * The brand inside the card number field (CONTRACT §16.4): the brand's mark on a 36×24 dp white badge with a
 * 1 dp `#D1D1D6` border and 4 dp corners (white in the dark theme too, as card marks are), or a neutral card outline
 * while the brand is unknown. A change cross-fades in 150 ms (instantly when animations are off), and accessibility
 * services read the mark as "Visa" or "Mastercard". Main thread only.
 */
internal class BrandMarkView(context: Context) : View(context) {

    /** The brand shown, or `null` for the neutral outline. */
    var brand: PayriffCardBrand? = null
        private set

    /** Colour of the neutral outline. */
    var outlineColor: Int = DEFAULT_OUTLINE
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** The mark that fades out while [brand] fades in, and how far the cross-fade is (1 = done). */
    private var previous: PayriffCardBrand? = null
    private var fraction = 1f
    private var animator: ValueAnimator? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val badge = RectF()
    private val border = RectF()
    private val card = RectF()
    private val stripe = RectF()
    private val visaUnits = PathData.toPath(BrandMarks.visaCommands)
    private val visa = Path()
    private val leftCircle = Path()
    private val rightCircle = Path()
    private val overlap = Path()
    private val transform = Matrix()
    private var unit = 0f
    private var badgeCorner = 0f
    private var cardCorner = 0f

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Shows [value]; with [animate] (and while attached) the marks cross-fade. */
    fun setBrand(value: PayriffCardBrand?, animate: Boolean) {
        if (value == brand) return
        val shown = brand
        val running = animator
        animator = null
        running?.cancel()
        brand = value
        val label = BrandMarks.label(value)
        contentDescription = label
        importantForAccessibility = if (label != null) IMPORTANT_FOR_ACCESSIBILITY_YES else IMPORTANT_FOR_ACCESSIBILITY_NO
        if (!animate || !isAttachedToWindow) {
            previous = null
            fraction = 1f
            invalidate()
            return
        }
        previous = shown
        fraction = 0f
        val fade = ValueAnimator.ofFloat(0f, 1f)
        fade.duration = BrandMarks.CROSS_FADE_MILLIS
        fade.interpolator = LinearInterpolator()
        fade.addUpdateListener {
            fraction = it.animatedFraction
            invalidate()
        }
        fade.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (animator !== animation) return
                animator = null
                previous = null
                fraction = 1f
                invalidate()
            }
        })
        animator = fade
        fade.start()
    }

    override fun onDetachedFromWindow() {
        animator?.end()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // The 36×24 badge, scaled to fit and centred: one unit is one dp at the size the form gives it.
        unit = min(w / BrandMarks.BADGE_WIDTH, h / BrandMarks.BADGE_HEIGHT)
        val left = (w - BrandMarks.BADGE_WIDTH * unit) / 2
        val top = (h - BrandMarks.BADGE_HEIGHT * unit) / 2
        badge.set(left, top, left + BrandMarks.BADGE_WIDTH * unit, top + BrandMarks.BADGE_HEIGHT * unit)
        badgeCorner = BrandMarks.BADGE_CORNER * unit
        // The 1 dp border lies inside the badge.
        val half = BrandMarks.BADGE_BORDER * unit / 2
        border.set(badge.left + half, badge.top + half, badge.right - half, badge.bottom - half)

        // Visa: the wordmark scaled to 26 dp wide, centred.
        val markWidth = BrandMarks.MARK_WIDTH * unit
        val markLeft = badge.centerX() - markWidth / 2
        val scale = markWidth / BrandMarks.VISA_BOX
        val markHeight = (BrandMarks.VISA_BOTTOM - BrandMarks.VISA_TOP) * scale
        transform.setScale(scale, scale)
        transform.postTranslate(markLeft, badge.centerY() - markHeight / 2 - BrandMarks.VISA_TOP * scale)
        visaUnits.transform(transform, visa)

        // Mastercard: two circles of radius 0.309 w at 0.309 w and 0.691 w, and their intersection.
        val radius = BrandMarks.MASTERCARD_RADIUS * markWidth
        leftCircle.rewind()
        leftCircle.addCircle(markLeft + radius, badge.centerY(), radius, Path.Direction.CW)
        rightCircle.rewind()
        rightCircle.addCircle(markLeft + BrandMarks.MASTERCARD_RIGHT_CENTRE * markWidth, badge.centerY(), radius, Path.Direction.CW)
        overlap.rewind()
        overlap.op(leftCircle, rightCircle, Path.Op.INTERSECT)

        // Unknown brand: a 30×20 card outline with a magnetic stripe.
        card.set(
            badge.centerX() - OUTLINE_WIDTH / 2 * unit,
            badge.centerY() - OUTLINE_HEIGHT / 2 * unit,
            badge.centerX() + OUTLINE_WIDTH / 2 * unit,
            badge.centerY() + OUTLINE_HEIGHT / 2 * unit,
        )
        cardCorner = BrandMarks.BADGE_CORNER * unit
        val stripeTop = card.top + (STRIPE_OFFSET - STRIPE_HEIGHT / 2) * unit
        stripe.set(card.left, stripeTop, card.right, stripeTop + STRIPE_HEIGHT * unit)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (unit <= 0f) return
        if (fraction < 1f) drawLayer(canvas, previous, 1f - fraction)
        drawLayer(canvas, brand, fraction)
    }

    /** Draws one mark at [opacity] as a group, so its overlapping shapes fade together. */
    private fun drawLayer(canvas: Canvas, mark: PayriffCardBrand?, opacity: Float) {
        val alpha = (opacity * OPAQUE).roundToInt()
        if (alpha <= 0) return
        val saved = if (alpha < OPAQUE) canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), alpha) else canvas.save()
        when (mark) {
            PayriffCardBrand.VISA -> {
                drawBadge(canvas)
                fill.color = BrandMarks.VISA_BLUE
                canvas.drawPath(visa, fill)
            }
            PayriffCardBrand.MASTERCARD -> {
                drawBadge(canvas)
                fill.color = BrandMarks.MASTERCARD_RED
                canvas.drawPath(leftCircle, fill)
                fill.color = BrandMarks.MASTERCARD_YELLOW
                canvas.drawPath(rightCircle, fill)
                fill.color = BrandMarks.MASTERCARD_ORANGE
                canvas.drawPath(overlap, fill)
            }
            null -> {
                stroke.color = outlineColor
                stroke.strokeWidth = OUTLINE_STROKE * unit
                canvas.drawRoundRect(card, cardCorner, cardCorner, stroke)
                fill.color = outlineColor
                canvas.drawRect(stripe, fill)
            }
        }
        canvas.restoreToCount(saved)
    }

    private fun drawBadge(canvas: Canvas) {
        fill.color = BrandMarks.BADGE_FILL
        canvas.drawRoundRect(badge, badgeCorner, badgeCorner, fill)
        stroke.color = BrandMarks.BADGE_STROKE
        stroke.strokeWidth = BrandMarks.BADGE_BORDER * unit
        val corner = badgeCorner - BrandMarks.BADGE_BORDER * unit / 2
        canvas.drawRoundRect(border, corner, corner, stroke)
    }

    private companion object {
        const val DEFAULT_OUTLINE = 0xFF9CA3AF.toInt()
        const val OPAQUE = 255
        const val OUTLINE_WIDTH = 30f
        const val OUTLINE_HEIGHT = 20f
        const val OUTLINE_STROKE = 1.5f
        const val STRIPE_OFFSET = 6f
        const val STRIPE_HEIGHT = 3f
    }
}

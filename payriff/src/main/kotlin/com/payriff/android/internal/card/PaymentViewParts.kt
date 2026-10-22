package com.payriff.android.internal.card

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.text.style.ReplacementSpan
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.graphics.withTranslation
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The Pay button of [com.payriff.android.PayriffPaymentView] (CONTRACT §16.14): full width, a filled rounded shape and
 * a one-line title. 50% opacity while disabled or busy and 80% while pressed; while busy a spinner in the title's
 * colour replaces the title. Accessibility services read it as a button named by its title.
 */
internal class PayButton(context: Context) : FrameLayout(context) {

    private val label = TextView(context)
    private val spinner = ProgressBar(context)
    private val shape = GradientDrawable()

    /** The title, for example "Pay 1.02 AZN". */
    var title: String = ""
        set(value) {
            if (field == value) return
            field = value
            label.text = value
            contentDescription = value
        }

    /** Shows the spinner instead of the title. */
    var isBusy: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            label.visibility = if (value) INVISIBLE else VISIBLE
            spinner.visibility = if (value) VISIBLE else GONE
            updateAlpha()
        }

    init {
        background = shape
        isClickable = true
        isFocusable = true
        val h = dp(PaymentViewPlan.BUTTON_PADDING_HORIZONTAL_DP)
        val v = dp(PaymentViewPlan.BUTTON_PADDING_VERTICAL_DP)
        setPaddingRelative(h, v, h, v)

        label.gravity = Gravity.CENTER
        label.maxLines = 1
        label.ellipsize = TextUtils.TruncateAt.END
        // The button itself carries the title for accessibility services.
        label.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        spinner.isIndeterminate = true
        spinner.visibility = GONE
        spinner.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        val size = dp(PaymentViewPlan.SPINNER_SIZE_DP)
        addView(spinner, LayoutParams(size, size, Gravity.CENTER))

        ViewCompat.setAccessibilityDelegate(
            this,
            object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = Button::class.java.name
                }
            },
        )
        updateAlpha()
    }

    /** The look: [color] fill, [text] for the title (and its colour for the spinner), height and corner radius in pixels. */
    fun apply(@ColorInt color: Int, text: ResolvedTextStyle, minHeightPx: Int, cornerRadiusPx: Float) {
        shape.setColor(color)
        shape.cornerRadius = cornerRadiusPx
        label.applyTextStyle(text)
        spinner.indeterminateTintList = ColorStateList.valueOf(text.color)
        minimumHeight = minHeightPx
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        updateAlpha()
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        updateAlpha()
    }

    private fun updateAlpha() {
        alpha = when {
            !isEnabled || isBusy -> PaymentViewPlan.DISABLED_ALPHA
            isPressed -> PaymentViewPlan.PRESSED_ALPHA
            else -> 1f
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}

/**
 * The lock before the secure line of [com.payriff.android.PayriffPaymentView] (CONTRACT §16.14), drawn in code from
 * [LockGlyph] in the largest square that fits, centred. It is decoration: accessibility services skip it, and the
 * secure text next to it says what it means.
 */
internal class LockView(context: Context) : View(context) {

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = LockGlyph.COLOR
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LockGlyph.COLOR }
    private val shackle = Path()
    private val arc = RectF()
    private val body = RectF()
    private var bodyCorner = 0f
    private var keyholeX = 0f
    private var keyholeY = 0f
    private var keyholeRadius = 0f

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val side = min(w, h).toFloat()
        val left = (w - side) / 2
        val top = (h - side) / 2
        fun x(fraction: Float) = left + fraction * side
        fun y(fraction: Float) = top + fraction * side

        stroke.strokeWidth = LockGlyph.STROKE_WIDTH * side
        val radius = LockGlyph.SHACKLE_RADIUS * side
        val centreX = x(LockGlyph.SHACKLE_CENTRE_X)
        val centreY = y(LockGlyph.SHACKLE_CENTRE_Y)
        arc.set(centreX - radius, centreY - radius, centreX + radius, centreY + radius)
        shackle.rewind()
        shackle.moveTo(x(LockGlyph.SHACKLE_LEFT), y(LockGlyph.SHACKLE_BOTTOM))
        shackle.lineTo(x(LockGlyph.SHACKLE_LEFT), centreY)
        // From the left end (180°) clockwise over the top to the right end.
        shackle.arcTo(arc, 180f, 180f, false)
        shackle.lineTo(x(LockGlyph.SHACKLE_RIGHT), y(LockGlyph.SHACKLE_BOTTOM))

        body.set(x(LockGlyph.BODY_LEFT), y(LockGlyph.BODY_TOP), x(LockGlyph.BODY_RIGHT), y(LockGlyph.BODY_BOTTOM))
        bodyCorner = LockGlyph.BODY_CORNER_RADIUS * side
        keyholeX = x(LockGlyph.KEYHOLE_X)
        keyholeY = y(LockGlyph.KEYHOLE_Y)
        keyholeRadius = LockGlyph.KEYHOLE_RADIUS * side
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (keyholeRadius <= 0f) return
        canvas.drawPath(shackle, stroke)
        canvas.drawRoundRect(body, bodyCorner, bodyCorner, stroke)
        canvas.drawCircle(keyholeX, keyholeY, keyholeRadius, fill)
    }
}

/**
 * Draws [drawable] at [widthPx]×[heightPx] in place of the text it spans, centred on the middle of the text line, as
 * Flutter's `PlaceholderAlignment.middle` does: the Payriff logo where "Payriff" is in "Powered by Payriff"
 * (CONTRACT §16.14). The text stays in the view, so accessibility services read "Powered by Payriff". The line grows when
 * the image is taller than the text.
 */
internal class LogoSpan(private val drawable: Drawable, private val widthPx: Int, private val heightPx: Int) : ReplacementSpan() {

    private val metrics = Paint.FontMetricsInt()

    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        if (fm != null) {
            // The layout may pass metrics that were never filled in: set every field.
            paint.getFontMetricsInt(metrics)
            val top = middle(metrics) - heightPx / 2
            val bottom = top + heightPx
            fm.ascent = min(metrics.ascent, top)
            fm.top = min(metrics.top, top)
            fm.descent = max(metrics.descent, bottom)
            fm.bottom = max(metrics.bottom, bottom)
            fm.leading = metrics.leading
        }
        return widthPx
    }

    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        paint.getFontMetricsInt(metrics)
        val imageTop = y + middle(metrics) - heightPx / 2
        drawable.setBounds(0, 0, widthPx, heightPx)
        canvas.withTranslation(x, imageTop.toFloat()) { drawable.draw(this) }
    }

    /** The middle of the text relative to the baseline. */
    private fun middle(m: Paint.FontMetricsInt): Int = (m.ascent + m.descent) / 2
}

package com.payriff.android.internal.card

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.text.style.ReplacementSpan
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.graphics.withTranslation
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The Pay button of [com.payriff.android.PayriffPaymentView] (CONTRACT §16.14): full width, a filled rounded shape
 * (smoothed by the style's corner smoothing, §18.2) and a one-line title. 50% opacity while disabled or busy, or the
 * style's disabled colours at full opacity (§18.3), and 80% while pressed; while busy a spinner in the title's colour
 * replaces the title. Accessibility services read it as a button named by its title.
 */
internal class PayButton(context: Context) : FrameLayout(context) {

    private val label = TextView(context)
    private val spinner = ProgressBar(context)
    private val shape = SquircleDrawable()
    private var colors = PaymentViewPlan.ButtonColors(PaymentViewPlan.BUTTON_COLOR, PaymentViewPlan.BUTTON_TEXT_COLOR, null, null)

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
            updateLook()
        }

    init {
        background = shape
        isClickable = true
        isFocusable = true
        val h = dp(PaymentViewPlan.BUTTON_PADDING_HORIZONTAL_DP)
        val v = dp(PaymentViewPlan.BUTTON_PADDING_VERTICAL_DP)
        setPaddingRelative(h, v, h, v)

        label.gravity = Gravity.CENTER
        label.maxLines = PaymentViewPlan.BUTTON_TITLE_MAX_LINES
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
        updateLook()
    }

    /**
     * The look: [colors] for the fill and the title (and the spinner) in each state, [text] for the title's size and
     * font, the minimum height and the corner radius in pixels, and the corner [smoothing].
     */
    fun apply(colors: PaymentViewPlan.ButtonColors, text: ResolvedTextStyle, minHeightPx: Int, cornerRadiusPx: Float, smoothing: Float) {
        this.colors = colors
        shape.setShape(cornerRadiusPx, smoothing)
        label.applyTextStyle(text)
        minimumHeight = minHeightPx
        updateLook()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        updateLook()
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        updateLook()
    }

    private fun updateLook() {
        val look = PaymentViewPlan.buttonLook(colors, disabled = !isEnabled || isBusy, pressed = isPressed)
        shape.setFill(look.background)
        label.setTextColor(look.text)
        spinner.indeterminateTintList = ColorStateList.valueOf(look.text)
        alpha = look.alpha
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}

/**
 * The parts of [com.payriff.android.PayriffPaymentView] top to bottom (CONTRACT §16.14, §17.9, §20): a vertical
 * [LinearLayout] whose children [arrange] puts in the plan's order, and which measures the wallet button at exactly
 * [walletHeight] of the Pay button's height, in the same measure pass (CONTRACT §20.2).
 */
internal class PaymentViewContent(context: Context) : LinearLayout(context) {

    /** The Pay button, whose measured height the wallet button follows. */
    var payButton: View? = null

    /** The wallet button. */
    var walletButton: View? = null

    /** The wallet button's height in pixels for the Pay button's measured height in pixels. */
    var walletHeight: (payButtonHeightPx: Int) -> Int = { it }

    init {
        orientation = VERTICAL
    }

    /**
     * Puts the children in [order], which holds each of them once. They are moved without leaving the window, so the
     * form keeps its focus and what was typed.
     */
    fun arrange(order: List<View>) {
        if (order.size == childCount && order.indices.all { getChildAt(it) === order[it] }) return
        order.forEach { detachViewFromParent(it) }
        order.forEachIndexed { index, child -> attachViewToParent(child, index, child.layoutParams) }
        requestLayout()
        invalidate()
    }

    /**
     * Measures the wallet button at exactly [walletHeight] of the Pay button's height in this pass: when Pay comes after
     * it, Pay is measured first. Every other child is measured as a [LinearLayout] measures it.
     */
    override fun measureChildWithMargins(child: View, parentWidthMeasureSpec: Int, widthUsed: Int, parentHeightMeasureSpec: Int, heightUsed: Int) {
        val pay = payButton
        if (child !== walletButton || pay == null) {
            super.measureChildWithMargins(child, parentWidthMeasureSpec, widthUsed, parentHeightMeasureSpec, heightUsed)
            return
        }
        val payShown = pay.visibility != GONE
        if (payShown && indexOfChild(pay) > indexOfChild(child)) {
            super.measureChildWithMargins(pay, parentWidthMeasureSpec, 0, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), 0)
        }
        val params = child.layoutParams as MarginLayoutParams
        val widthSpec = getChildMeasureSpec(parentWidthMeasureSpec, paddingLeft + paddingRight + params.leftMargin + params.rightMargin + widthUsed, params.width)
        val height = walletHeight(if (payShown) pay.measuredHeight else 0)
        child.measure(widthSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }
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

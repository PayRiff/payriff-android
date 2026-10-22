package com.payriff.android.internal

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Colors of the sheet in the light or dark theme. */
internal class Palette(
    val surface: Int,
    val onSurface: Int,
    val onSurfaceVariant: Int,
    val outline: Int,
    val handle: Int,
    val primary: Int,
    val onPrimary: Int,
    val ripple: Int,
    val scrim: Int,
    val error: Int,
) {
    companion object {
        val LIGHT = Palette(
            surface = 0xFFFFFFFF.toInt(),
            onSurface = 0xFF1B1B1F.toInt(),
            onSurfaceVariant = 0xFF5F6168.toInt(),
            outline = 0xFFE4E5EA.toInt(),
            handle = 0xFFC9CBD1.toInt(),
            primary = 0xFF2983F2.toInt(),
            onPrimary = 0xFFFFFFFF.toInt(),
            ripple = 0x1F000000,
            scrim = 0x66000000,
            error = 0xFFD93025.toInt(),
        )

        val DARK = Palette(
            surface = 0xFF1C1C21.toInt(),
            onSurface = 0xFFF1F1F4.toInt(),
            onSurfaceVariant = 0xFFA4A6AE.toInt(),
            outline = 0xFF303036.toInt(),
            handle = 0xFF4A4C53.toInt(),
            primary = 0xFF4C9BF7.toInt(),
            onPrimary = 0xFFFFFFFF.toInt(),
            ripple = 0x33FFFFFF,
            scrim = 0x99000000.toInt(),
            error = 0xFFF0675C.toInt(),
        )
    }
}

/**
 * Root of the payment window: a full-screen scrim and the bottom sheet. Measures the sheet at about 94 % of
 * the window height (the full height below the status bar while the keyboard is open), at most 640 dp wide,
 * centred, and pads its bottom for the navigation bar or the keyboard.
 */
internal class SheetRootLayout(context: Context) : FrameLayout(context) {

    private val density = resources.displayMetrics.density
    private val maxSheetWidth = (MAX_SHEET_WIDTH_DP * density).roundToInt()
    private val minTopGap = (MIN_TOP_GAP_DP * density).roundToInt()

    lateinit var scrim: View
    lateinit var sheet: View

    private var insetLeft = 0
    private var insetTop = 0
    private var insetRight = 0
    private var insetBottom = 0
    private var imeBottom = 0

    /** Applies system-bar, display-cutout and keyboard insets. */
    fun setInsets(left: Int, top: Int, right: Int, bottom: Int, ime: Int) {
        if (left == insetLeft && top == insetTop && right == insetRight && bottom == insetBottom && ime == imeBottom) return
        insetLeft = left
        insetTop = top
        insetRight = right
        insetBottom = bottom
        imeBottom = ime
        sheet.setPadding(0, 0, 0, max(bottom, ime))
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        scrim.measure(exactly(width), exactly(height))
        val sheetWidth = max(0, min(width - insetLeft - insetRight, maxSheetWidth))
        val sheetHeight = if (imeBottom > 0) {
            height - insetTop
        } else {
            min((height * HEIGHT_FRACTION).roundToInt(), height - insetTop - minTopGap)
        }
        sheet.measure(exactly(sheetWidth), exactly(max(0, sheetHeight)))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val width = right - left
        val height = bottom - top
        scrim.layout(0, 0, width, height)
        val available = width - insetLeft - insetRight
        val sheetLeft = insetLeft + (available - sheet.measuredWidth) / 2
        sheet.layout(sheetLeft, height - sheet.measuredHeight, sheetLeft + sheet.measuredWidth, height)
    }

    private fun exactly(size: Int) = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)

    private companion object {
        const val MAX_SHEET_WIDTH_DP = 640
        const val MIN_TOP_GAP_DP = 8
        const val HEIGHT_FRACTION = 0.94f
    }
}

/**
 * Sheet header that turns vertical drags into sheet drags, so only the header and handle move the sheet.
 * Taps still reach its children (the close button).
 */
internal class DragHandleLayout(context: Context) : FrameLayout(context) {

    /** Receives drag events. */
    interface Listener {
        fun onDrag(offset: Float)
        fun onRelease(offset: Float, velocityY: Float)
    }

    var listener: Listener? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downY = 0f
    private var dragging = false
    private var velocityTracker: VelocityTracker? = null

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = event.rawY
                dragging = false
                startTracking(event)
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                if (!dragging && abs(event.rawY - downY) > touchSlop) {
                    dragging = true
                    downY = event.rawY
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stopTracking()
        }
        return dragging
    }

    @SuppressLint("ClickableViewAccessibility") // performClick is called for taps below.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = event.rawY
                dragging = false
                startTracking(event)
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                if (!dragging && abs(event.rawY - downY) > touchSlop) {
                    dragging = true
                    downY = event.rawY
                }
                if (dragging) listener?.onDrag(max(0f, event.rawY - downY))
            }
            MotionEvent.ACTION_UP -> {
                velocityTracker?.addMovement(event)
                if (dragging) {
                    val tracker = velocityTracker
                    tracker?.computeCurrentVelocity(VELOCITY_UNITS)
                    listener?.onRelease(max(0f, event.rawY - downY), tracker?.yVelocity ?: 0f)
                } else {
                    performClick()
                }
                dragging = false
                stopTracking()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) listener?.onRelease(0f, 0f)
                dragging = false
                stopTracking()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun startTracking(event: MotionEvent) {
        velocityTracker?.recycle()
        velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
    }

    private fun stopTracking() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    private companion object {
        const val VELOCITY_UNITS = 1000
    }
}

/** A [LinearLayout] that never grows wider than [maxWidthPx]. */
internal class MaxWidthLinearLayout(context: Context) : LinearLayout(context) {

    var maxWidthPx: Int = Int.MAX_VALUE
        set(value) {
            field = value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = MeasureSpec.getSize(widthMeasureSpec)
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        val clamped = if (mode == MeasureSpec.UNSPECIFIED || size > maxWidthPx) {
            MeasureSpec.makeMeasureSpec(maxWidthPx, if (mode == MeasureSpec.UNSPECIFIED) MeasureSpec.AT_MOST else mode)
        } else {
            widthMeasureSpec
        }
        super.onMeasure(clamped, heightMeasureSpec)
    }
}

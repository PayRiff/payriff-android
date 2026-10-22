package com.payriff.android.internal

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.payriff.android.R
import kotlin.math.roundToInt

/** A full-screen error message inside the sheet, such as the page-load error of the 3-D Secure page (§16.8). */
internal class SheetMessage(
    val message: String,
    val primaryLabel: String,
    val onPrimary: () -> Unit,
    val secondaryLabel: String?,
    val onSecondary: (() -> Unit)?,
)

/**
 * The 3-D Secure sheet's views, built in code with platform widgets only (no AppCompat or Material), so the SDK
 * imposes no theme on the host app.
 *
 * CONTRACT §9.1: the sheet reads as part of the app, never as a website. The header shows the handle, the localized
 * title and the close button only (no address, padlock or host). There is no browser-style progress bar: a native
 * loader covers the page until it first shows, and later page loads show a small activity indicator in the header.
 * Bank pages sit on a white backdrop, inset from the edges ([setBankPageInset]).
 */
internal class SheetView(
    private val context: Context,
    private var palette: Palette,
    title: String,
    closeLabel: String,
    private val onCloseRequested: () -> Unit,
) {
    val root = SheetRootLayout(context)
    private val scrim = View(context)
    private val sheet = LinearLayout(context)
    private val header = DragHandleLayout(context)
    private val handle = View(context)
    private val titleView = TextView(context)
    private val pageIndicator = ProgressBar(context, null, android.R.attr.progressBarStyleSmall)
    private val closeButton = ImageButton(context)
    private val divider = View(context)
    private val content = FrameLayout(context)

    /** Holds the WebViews of the 3-D Secure step. */
    val webStack = FrameLayout(context)

    private val statusOverlay = LinearLayout(context)
    private val statusSpinner = ProgressBar(context)
    private val statusText = TextView(context)
    private val messageScroll = ScrollView(context)
    private val messageColumn = MaxWidthLinearLayout(context)
    private val messageIconFrame = FrameLayout(context)
    private val messageIcon = ImageView(context)
    private val messageText = TextView(context)
    private val primaryButton = Button(context)
    private val secondaryButton = Button(context)

    private var closing = false

    /** Whether page content has been visible: from then on, page loads show the header indicator. */
    private var contentShown = false
    private var pageLoading = false

    init {
        buildScrim()
        buildSheet(title, closeLabel)
        root.scrim = scrim
        root.sheet = sheet
        root.addView(scrim)
        root.addView(sheet)
        applyPalette(palette)
    }

    /** Re-colors every view, for example after the system switched between light and dark theme. */
    fun applyPalette(newPalette: Palette) {
        palette = newPalette
        scrim.setBackgroundColor(palette.scrim)
        sheet.background = GradientDrawable().apply {
            setColor(palette.surface)
            val radius = dp(SHEET_CORNER_DP).toFloat()
            cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
        }
        handle.background = rounded(palette.handle, dp(2).toFloat())
        titleView.setTextColor(palette.onSurface)
        pageIndicator.indeterminateTintList = ColorStateList.valueOf(palette.onSurfaceVariant)
        closeButton.imageTintList = ColorStateList.valueOf(palette.onSurface)
        closeButton.background = RippleDrawable(ColorStateList.valueOf(palette.ripple), null, null)
        divider.setBackgroundColor(palette.outline)
        content.setBackgroundColor(palette.surface)
        // Bank pages are white in both themes: a white backdrop avoids a coloured flash between pages.
        webStack.setBackgroundColor(Color.WHITE)
        statusOverlay.setBackgroundColor(palette.surface)
        statusSpinner.indeterminateTintList = ColorStateList.valueOf(palette.primary)
        statusText.setTextColor(palette.onSurfaceVariant)
        messageScroll.setBackgroundColor(palette.surface)
        messageText.setTextColor(palette.onSurface)
        primaryButton.background = buttonBackground(filled = true)
        primaryButton.setTextColor(palette.onPrimary)
        secondaryButton.background = buttonBackground(filled = false)
        secondaryButton.setTextColor(palette.primary)
        messageIconFrame.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(palette.error)
        }
        messageIcon.imageTintList = ColorStateList.valueOf(palette.onPrimary)
    }

    /**
     * Enables or disables the close (✕) button. While the verifying overlay shows it is disabled and dimmed
     * (CONTRACT §9.1).
     */
    fun setCloseEnabled(enabled: Boolean) {
        closeButton.isEnabled = enabled
        closeButton.alpha = if (enabled) 1f else DISABLED_ALPHA
    }

    /**
     * Whether the visible WebView is loading a page. The header indicator shows it, but only after page content was
     * first visible and while no overlay covers the page: the first load has the native loader instead (§9.1).
     */
    fun setPageLoading(loading: Boolean) {
        pageLoading = loading
        updatePageIndicator()
    }

    /** The native loader (a spinner) with an optional text, over the content. */
    fun showStatus(text: String?) {
        statusText.text = text.orEmpty()
        statusText.visibility = if (text == null) View.GONE else View.VISIBLE
        statusOverlay.visibility = View.VISIBLE
        messageScroll.visibility = View.GONE
        if (text != null) statusOverlay.contentDescription = text
        updatePageIndicator()
    }

    /** Hides overlays so the page is visible. */
    fun showContent() {
        statusOverlay.visibility = View.GONE
        messageScroll.visibility = View.GONE
        contentShown = true
        updatePageIndicator()
    }

    fun showMessage(message: SheetMessage) {
        messageText.text = message.message
        primaryButton.text = message.primaryLabel
        primaryButton.setOnClickListener { message.onPrimary() }
        val secondary = message.onSecondary
        if (message.secondaryLabel != null && secondary != null) {
            secondaryButton.text = message.secondaryLabel
            secondaryButton.setOnClickListener { secondary() }
            secondaryButton.visibility = View.VISIBLE
        } else {
            secondaryButton.visibility = View.GONE
        }
        messageScroll.scrollTo(0, 0)
        messageScroll.visibility = View.VISIBLE
        statusOverlay.visibility = View.GONE
        updatePageIndicator()
    }

    /**
     * Insets a bank page (CONTRACT §9.1, §16.8): with [inset], [webView] sits 16 dp from the left and right edges and
     * 12 dp from the top and bottom, on the white backdrop of [webStack], because bank pages are built for a desktop
     * popup and otherwise run to the screen edges. The 3-D Secure sheet always insets its page. Main thread only.
     */
    fun setBankPageInset(webView: View, inset: Boolean) {
        val params = webView.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val horizontal = if (inset) dp(BANK_PAGE_INSET_H_DP) else 0
        val vertical = if (inset) dp(BANK_PAGE_INSET_V_DP) else 0
        val unchanged = params.leftMargin == horizontal && params.rightMargin == horizontal &&
            params.topMargin == vertical && params.bottomMargin == vertical
        if (unchanged) return
        params.setMargins(horizontal, vertical, horizontal, vertical)
        webView.layoutParams = params
    }

    private fun updatePageIndicator() {
        val pageVisible = statusOverlay.visibility != View.VISIBLE && messageScroll.visibility != View.VISIBLE
        pageIndicator.visibility = if (pageLoading && contentShown && pageVisible) View.VISIBLE else View.INVISIBLE
    }

    fun animateIn() {
        sheet.visibility = View.INVISIBLE
        scrim.alpha = 0f
        sheet.post {
            if (closing) return@post
            sheet.translationY = sheet.height.toFloat()
            sheet.visibility = View.VISIBLE
            sheet.animate().translationY(0f).setDuration(ENTER_MILLIS).setInterpolator(DecelerateInterpolator(2f)).setListener(null).start()
            scrim.animate().alpha(1f).setDuration(ENTER_MILLIS).start()
        }
    }

    /** Slides the sheet out, then runs [onEnd]. */
    fun animateOut(onEnd: () -> Unit) {
        if (closing) return
        closing = true
        sheet.visibility = View.VISIBLE
        sheet.animate().cancel()
        scrim.animate().alpha(0f).setDuration(EXIT_MILLIS).start()
        sheet.animate()
            .translationY(sheet.height.toFloat().coerceAtLeast(1f))
            .setDuration(EXIT_MILLIS)
            .setInterpolator(AccelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = onEnd()
            })
            .start()
    }

    /** Moves the sheet back to its resting position after a drag that did not close it. */
    private fun settle() {
        sheet.animate().translationY(0f).setDuration(SETTLE_MILLIS).setInterpolator(DecelerateInterpolator()).setListener(null).start()
        scrim.animate().alpha(1f).setDuration(SETTLE_MILLIS).start()
    }

    private fun buildScrim() {
        scrim.setOnClickListener { onCloseRequested() }
        scrim.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun buildSheet(title: String, closeLabel: String) {
        sheet.orientation = LinearLayout.VERTICAL
        sheet.isClickable = true
        sheet.filterTouchesWhenObscured = true
        sheet.elevation = dp(SHEET_ELEVATION_DP).toFloat()
        ViewCompat.setAccessibilityPaneTitle(sheet, title)

        // Header: handle, the title alone, the page-load indicator and the close button (§9.1). Only the header
        // drags the sheet.
        header.minimumHeight = dp(HEADER_HEIGHT_DP)
        header.addView(handle, FrameLayout.LayoutParams(dp(36), dp(4), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(8)
        })
        handle.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO

        titleView.text = title
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        titleView.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        titleView.gravity = Gravity.CENTER
        titleView.maxLines = 1
        ViewCompat.setAccessibilityHeading(titleView, true)
        header.addView(titleView, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER).apply {
            topMargin = dp(12)
            bottomMargin = dp(4)
            marginStart = dp(56)
            marginEnd = dp(56)
        })

        pageIndicator.isIndeterminate = true
        pageIndicator.visibility = View.INVISIBLE
        pageIndicator.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        header.addView(pageIndicator, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.START or Gravity.CENTER_VERTICAL).apply {
            marginStart = dp(18)
            topMargin = dp(4)
        })

        closeButton.setImageResource(R.drawable.payriff_ic_close)
        closeButton.scaleType = ImageView.ScaleType.CENTER
        closeButton.contentDescription = closeLabel
        closeButton.setOnClickListener { onCloseRequested() }
        header.addView(closeButton, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END or Gravity.CENTER_VERTICAL).apply {
            marginEnd = dp(4)
            topMargin = dp(4)
        })
        header.listener = object : DragHandleLayout.Listener {
            override fun onDrag(offset: Float) {
                if (closing) return
                sheet.animate().cancel()
                sheet.translationY = offset
                scrim.alpha = 1f - (offset / sheet.height.coerceAtLeast(1)).coerceIn(0f, 1f) * SCRIM_DRAG_FADE
            }

            override fun onRelease(offset: Float, velocityY: Float) {
                if (closing) return
                // Never an interactive dismiss (CONTRACT §9.1): the sheet springs back, and a drag past the threshold
                // runs the close path, which asks for confirmation while the page is open.
                settle()
                val flung = offset > 0f && velocityY > dp(FLING_DP_PER_SECOND)
                if (flung || offset > sheet.height * DISMISS_FRACTION) onCloseRequested()
            }
        }
        // Wraps its content (large font scales) but is never shorter than HEADER_HEIGHT_DP.
        sheet.addView(header, LinearLayout.LayoutParams(MATCH, WRAP))

        divider.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        sheet.addView(divider, LinearLayout.LayoutParams(MATCH, 1))

        content.addView(webStack, FrameLayout.LayoutParams(MATCH, MATCH))
        buildStatusOverlay()
        content.addView(statusOverlay, FrameLayout.LayoutParams(MATCH, MATCH))
        buildMessageScreen()
        content.addView(messageScroll, FrameLayout.LayoutParams(MATCH, MATCH))
        sheet.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))
    }

    private fun buildStatusOverlay() {
        statusOverlay.orientation = LinearLayout.VERTICAL
        statusOverlay.gravity = Gravity.CENTER
        statusOverlay.isClickable = true
        statusOverlay.visibility = View.GONE
        statusOverlay.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        statusOverlay.addView(statusSpinner, LinearLayout.LayoutParams(dp(40), dp(40)))
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        statusText.gravity = Gravity.CENTER
        statusOverlay.addView(statusText, LinearLayout.LayoutParams(WRAP, WRAP).apply {
            topMargin = dp(16)
            marginStart = dp(24)
            marginEnd = dp(24)
        })
    }

    private fun buildMessageScreen() {
        messageScroll.isFillViewport = true
        messageScroll.visibility = View.GONE
        messageScroll.isClickable = true
        messageColumn.orientation = LinearLayout.VERTICAL
        messageColumn.gravity = Gravity.CENTER
        messageColumn.maxWidthPx = dp(MESSAGE_MAX_WIDTH_DP)
        messageColumn.setPadding(dp(24), dp(24), dp(24), dp(24))
        messageColumn.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE

        messageIcon.setImageResource(R.drawable.payriff_ic_alert)
        messageIcon.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        messageIconFrame.addView(messageIcon, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER))
        messageColumn.addView(messageIconFrame, LinearLayout.LayoutParams(dp(72), dp(72)))

        messageText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        messageText.gravity = Gravity.CENTER
        messageText.setLineSpacing(0f, LINE_SPACING)
        messageColumn.addView(messageText, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(20) })

        styleButton(primaryButton)
        messageColumn.addView(primaryButton, LinearLayout.LayoutParams(MATCH, dp(BUTTON_HEIGHT_DP)).apply { topMargin = dp(28) })
        styleButton(secondaryButton)
        messageColumn.addView(secondaryButton, LinearLayout.LayoutParams(MATCH, dp(BUTTON_HEIGHT_DP)).apply { topMargin = dp(8) })

        val limiter = FrameLayout(context)
        limiter.addView(messageColumn, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER))
        messageScroll.addView(limiter, FrameLayout.LayoutParams(MATCH, WRAP))
    }

    private fun styleButton(button: Button) {
        button.isAllCaps = false
        button.stateListAnimator = null
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        button.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        button.minHeight = dp(BUTTON_HEIGHT_DP)
    }

    private fun buttonBackground(filled: Boolean): RippleDrawable {
        val radius = dp(BUTTON_CORNER_DP).toFloat()
        val shape = rounded(if (filled) palette.primary else palette.surface, radius)
        val mask = rounded(palette.onSurface, radius)
        return RippleDrawable(ColorStateList.valueOf(palette.ripple), shape, mask)
    }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val HEADER_HEIGHT_DP = 60
        const val SHEET_CORNER_DP = 16
        const val SHEET_ELEVATION_DP = 16
        const val BUTTON_HEIGHT_DP = 52
        const val BUTTON_CORNER_DP = 12
        const val MESSAGE_MAX_WIDTH_DP = 400
        const val LINE_SPACING = 1.15f
        const val ENTER_MILLIS = 280L
        const val EXIT_MILLIS = 220L
        const val SETTLE_MILLIS = 180L
        const val DISMISS_FRACTION = 0.25f
        const val FLING_DP_PER_SECOND = 1_000
        const val SCRIM_DRAG_FADE = 0.6f
        const val DISABLED_ALPHA = 0.38f
        const val BANK_PAGE_INSET_H_DP = 16
        const val BANK_PAGE_INSET_V_DP = 12
    }
}

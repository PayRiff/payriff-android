package com.payriff.android.internal.wallet

import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorInt
import com.google.android.gms.wallet.button.ButtonOptions
import com.google.android.gms.wallet.button.PayButton
import com.payriff.android.internal.PayriffLog
import com.payriff.android.internal.card.PaymentViewPlan
import com.payriff.android.internal.card.ResolvedTextStyle
import com.payriff.android.internal.card.applyTextStyle
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Google's official Pay button (`com.google.android.gms.wallet.button.PayButton`, CONTRACT §17.10), initialised with
 * [GooglePayButtonOptions], at the full width and the options' height. With [followsParentHeight] it takes the height
 * its parent measures it with instead: the payment view gives it the Pay button's (CONTRACT §20.2). Nothing else draws
 * the Google Pay mark. While disabled it shows at 50% opacity and ignores taps.
 */
internal class GooglePayButtonView @JvmOverloads constructor(
    context: Context,
    private val followsParentHeight: Boolean = false,
) : FrameLayout(context) {

    /** Called when the enabled button is tapped. */
    var onTap: (() -> Unit)? = null

    private var button: PayButton? = null
    private var options: GooglePayButtonOptions? = null
    private var ready = false

    init {
        updateAlpha()
    }

    /**
     * Shows Google's button with [options]; it is initialised again only when they change (another theme, for example).
     * Returns whether Google's button could be created: without Google Play services it cannot, and nothing shows.
     */
    fun configure(options: GooglePayButtonOptions): Boolean {
        if (options == this.options) return ready
        this.options = options
        val height = if (followsParentHeight) LayoutParams.MATCH_PARENT else options.heightPx
        val view = button ?: PayButton(context).also { created ->
            created.setOnClickListener { if (isEnabled) onTap?.invoke() }
            button = created
            addView(created, LayoutParams(LayoutParams.MATCH_PARENT, height))
        }
        ready = try {
            view.initialize(
                ButtonOptions.newBuilder()
                    .setButtonType(options.buttonType)
                    .setButtonTheme(options.buttonTheme)
                    .setCornerRadius(options.cornerRadiusPx)
                    .setAllowedPaymentMethods(options.allowedPaymentMethods)
                    .build(),
            )
            true
        } catch (e: RuntimeException) {
            // For example Google Play services' button module cannot be loaded.
            PayriffLog.warning { "Google Pay's button could not be created: ${e.javaClass.simpleName}" }
            false
        }
        val params = view.layoutParams
        if (params.height != height) {
            params.height = height
            view.layoutParams = params
        }
        return ready
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        updateAlpha()
    }

    /** A disabled button keeps every touch from Google's button, so it neither reacts nor pays. */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = !isEnabled || super.onInterceptTouchEvent(ev)

    private fun updateAlpha() {
        alpha = if (isEnabled) 1f else PaymentViewPlan.DISABLED_ALPHA
    }
}

/**
 * The divider under the wallet button of [com.payriff.android.PayriffPaymentView] (CONTRACT §17.9): "Or pay by card"
 * (`view.orPayByCard`), centred, with a 1 dp line in the border colour on each side, 12 dp from the text.
 */
internal class WalletDividerView(context: Context) : LinearLayout(context) {

    private val label = TextView(context)
    private val lines = listOf(View(context), View(context))

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isBaselineAligned = false
        val gap = dp(PaymentViewPlan.WALLET_DIVIDER_TEXT_GAP_DP)
        val thickness = max(1, dp(PaymentViewPlan.WALLET_DIVIDER_LINE_DP))
        lines.forEach { it.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        label.gravity = Gravity.CENTER
        addView(lines[0], LayoutParams(0, thickness, 1f))
        addView(
            label,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = gap
                marginEnd = gap
            },
        )
        addView(lines[1], LayoutParams(0, thickness, 1f))
    }

    /** The text between the lines, for example "Or pay by card". */
    var text: String
        get() = label.text.toString()
        set(value) {
            if (label.text.toString() != value) label.text = value
        }

    /** The text in [textStyle], the lines in [lineColor]. */
    fun applyStyle(textStyle: ResolvedTextStyle, @ColorInt lineColor: Int) {
        label.applyTextStyle(textStyle)
        lines.forEach { it.setBackgroundColor(lineColor) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}

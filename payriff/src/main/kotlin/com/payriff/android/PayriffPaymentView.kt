package com.payriff.android

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.text.SpannableString
import android.text.Spanned
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.payriff.android.internal.DeviceIntegrityGuard
import com.payriff.android.internal.PayriffLog
import com.payriff.android.internal.card.CardFormStyles
import com.payriff.android.internal.card.CardStrings
import com.payriff.android.internal.card.LockView
import com.payriff.android.internal.card.LogoSpan
import com.payriff.android.internal.card.PayButton
import com.payriff.android.internal.card.PaymentViewAsset
import com.payriff.android.internal.card.PaymentViewController
import com.payriff.android.internal.card.PaymentViewKeys
import com.payriff.android.internal.card.PaymentViewPlan
import com.payriff.android.internal.card.ResolvedTextStyle
import com.payriff.android.internal.card.applyTextStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The SDK's default card payment screen (CONTRACT §16.14), like the card part of Payriff's hosted payment page
 * (pay.payriff.com): a [PayriffCardForm], a Pay button with the order's amount ("Pay 1.02 AZN"), the Mastercard and
 * Visa badges, a "Proceed securely by PCI DSS" line and a "Powered by Payriff" footer, on the page's background.
 *
 * ```
 * <com.payriff.android.PayriffPaymentView
 *     android:id="@+id/paymentView"
 *     android:layout_width="match_parent"
 *     android:layout_height="match_parent" />
 * ```
 * ```
 * val paymentView = findViewById<PayriffPaymentView>(R.id.paymentView)
 * paymentView.onResultListener = PayriffCardResultCallback { result -> handle(result) }
 * paymentView.setOrder(order.orderId, order.clientSecret) // from your backend
 * ```
 *
 * It reads the order with [Payriff.retrieveOrder] when it appears, and Pay confirms the payment like
 * [PayriffCardPaymentLauncher.confirm], with a launcher of its own: you need no launcher. The Pay button is dimmed and
 * disabled while the order loads, when the order cannot be paid by card or did not load (an error line under the button
 * says why), while the form is incomplete, and while paying, when a spinner replaces its title. Every payment result
 * goes to [onResultListener], and so does a fatal error that ends the order ([PayriffError.isFatal], for example an
 * expired client secret), once. After a result other than [PayriffResult.Failed] the view does not pay the order again;
 * fulfil it on your server.
 *
 * Show it in a `ComponentActivity` (an `AppCompatActivity` or `FragmentActivity` is one), in one of its fragments, or in
 * Compose with `AndroidView(factory = { PayriffPaymentView(it) })`. The 3-D Secure sheet's result is registered with the
 * activity's `ActivityResultRegistry` under a key made from the view's id, so a result that arrives after the activity
 * was recreated during 3-D Secure goes to the view with the same id. Give the view an id, and set [onResultListener]
 * when you create it, as you would register a launcher in `onCreate`.
 *
 * Let it fill the screen: it scrolls when the keyboard is up and keeps the footer at the bottom when there is room. It
 * pads its content for the system bars, the display cutout and the keyboard from the window insets it receives, so it
 * works edge to edge; when a parent already applies and consumes them, it adds nothing.
 *
 * [style] changes the colours, sizes and parts of the view ([PayriffPaymentViewStyle.showBadges],
 * [PayriffPaymentViewStyle.showSecureNote], [PayriffPaymentViewStyle.showPoweredBy]), the Pay button's word and the
 * secure line's text ([PayriffPaymentViewStyle.payButtonLabel], [PayriffPaymentViewStyle.secureNoteText]) and the form's
 * look ([PayriffPaymentViewStyle.formStyle], for example `PayriffCardFormStyle(showLabels = false)`). [PayriffCardForm]
 * and [PayriffCardPaymentLauncher] remain available to build your own screen.
 */
public class PayriffPaymentView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    /**
     * The look of the view and of its form. The default reproduces the card part of Payriff's hosted payment page and
     * follows the system light or dark theme. Changing it keeps what the customer typed.
     */
    public var style: PayriffPaymentViewStyle = PayriffPaymentViewStyle()
        set(value) {
            if (field == value) return
            field = value
            applyStyle()
        }

    /** Language of the view and its form; `null` uses [PayriffConfig.language], then the device language. */
    public var language: PayriffLanguage? = null
        set(value) {
            field = value
            form.language = value
            applyTexts()
        }

    /**
     * Receives, on the main thread, the result of every payment and, once, a fatal error that ends the order. The
     * result is for your user interface: fulfil the order on your server after it has confirmed the payment. A result
     * that arrives while no listener is set is kept until you set one.
     */
    public var onResultListener: PayriffCardResultCallback? = null
        set(value) {
            field = value
            deliverWaitingResults()
        }

    /** The order id passed to [setOrder], or `null` before. */
    public val orderId: String?
        get() = controller.orderId

    /** `true` from Pay until its result, the 3-D Secure step included. */
    public val isProcessing: Boolean
        get() = controller.state.isPaying || form.isProcessing

    private val form = PayriffCardForm(context)
    private val payButton = PayButton(context)
    private val errorLine = TextView(context)
    private val badges = LinearLayout(context)
    private val secureLine = LinearLayout(context)
    private val secureText = TextView(context)
    private val footer = TextView(context)
    private val content = LinearLayout(context)
    private val column = LinearLayout(context)
    private val scroll = ScrollView(context)

    private val waitingResults = ArrayList<PayriffResult>()
    private var night = isNight(resources.configuration)

    /** The language and theme the footer was built for; it is built again when either changes. */
    private var footerLanguage: PayriffLanguage? = null
    private var footerNight: Boolean? = null
    private var launcher: PayriffCardPaymentLauncher? = null
    private var registry: ActivityResultRegistry? = null
    private var launcherKey: String? = null

    private val controller = PaymentViewController(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        loadOrder = { orderId, clientSecret -> Payriff.retrieveOrder(orderId, clientSecret) },
        onStateChanged = { render() },
        deliver = { result -> deliver(result) },
        // CONTRACT §16.16: a compromised device shows view.deviceNotSupported, keeps Pay off and skips the order load.
        deviceBlock = { DeviceIntegrityGuard.blockReason(context, Payriff.config) },
    )

    init {
        buildViews()
        form.onChangeListener = PayriffCardFormListener { render() }
        payButton.setOnClickListener { pay() }
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            applyInsets(insets)
            insets
        }
        applyStyle()
    }

    /**
     * Sets the order to pay: the `orderId` and the `clientSecret` (`pr_cs_…`) your server returned when it created the
     * order. The view loads the order when it is on screen. Another order resets the view; setting the same order again
     * changes nothing.
     */
    public fun setOrder(orderId: String, clientSecret: String) {
        controller.setOrder(orderId, clientSecret)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Payriff.onContextAvailable(context)
        registerLauncher()
        ViewCompat.requestApplyInsets(this)
        applyTexts()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        // Also called right after onAttachedToWindow: the view appears.
        if (visibility == VISIBLE && isAttachedToWindow) controller.appear()
    }

    override fun onDetachedFromWindow() {
        controller.disappear()
        releaseLauncher()
        super.onDetachedFromWindow()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val nowNight = isNight(newConfig)
        if (nowNight != night) {
            night = nowNight
            applyStyle()
        } else {
            applyTexts()
        }
    }

    private fun buildViews() {
        content.orientation = LinearLayout.VERTICAL
        content.addView(form, matchWidth())
        content.addView(payButton, matchWidth())

        errorLine.gravity = Gravity.CENTER_HORIZONTAL
        errorLine.accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        content.addView(errorLine, matchWidth())

        badges.orientation = LinearLayout.HORIZONTAL
        badges.gravity = Gravity.CENTER_HORIZONTAL
        badges.isBaselineAligned = false
        PaymentViewAsset.BADGES.forEachIndexed { index, asset ->
            badges.addView(
                image(asset),
                LinearLayout.LayoutParams(dp(asset.widthDp), dp(asset.heightDp)).apply {
                    if (index > 0) marginStart = dp(PaymentViewPlan.BADGE_SPACING_DP)
                },
            )
        }
        content.addView(badges, matchWidth())

        secureLine.orientation = LinearLayout.HORIZONTAL
        secureLine.gravity = Gravity.CENTER
        secureLine.isBaselineAligned = false
        val lock = dp(PaymentViewPlan.LOCK_SIZE_DP)
        secureLine.addView(LockView(context), LinearLayout.LayoutParams(lock, lock))
        secureText.gravity = Gravity.CENTER_HORIZONTAL
        secureLine.addView(
            secureText,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(PaymentViewPlan.LOCK_GAP_DP)
            },
        )
        content.addView(secureLine, matchWidth())

        // The column fills the viewport (fillViewport), so the weighted spacer pushes the footer to the bottom when
        // there is room; when the content is taller, the footer follows it and everything scrolls.
        column.orientation = LinearLayout.VERTICAL
        column.addView(content, matchWidth())
        column.addView(View(context), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        footer.gravity = Gravity.CENTER_HORIZONTAL
        column.addView(footer, matchWidth())

        scroll.isFillViewport = true
        scroll.addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** Colours, sizes and the parts the style shows; then the texts and the state. */
    private fun applyStyle() {
        val s = style
        setBackgroundColor(s.backgroundColor ?: if (night) PaymentViewPlan.BACKGROUND_DARK else PaymentViewPlan.BACKGROUND_LIGHT)
        val padding = CardFormStyles.px(s.padding, density())
        column.setPadding(padding, padding, padding, padding)
        form.style = s.formStyle
        // The error line looks like the form's error lines (CONTRACT §16.14: #EC4D3D, #EC5545 in the dark theme).
        errorLine.applyTextStyle(CardFormStyles.resolve(s.formStyle, night, density()).error)
        payButton.apply(
            color = s.buttonColor ?: PaymentViewPlan.BUTTON_COLOR,
            text = PaymentViewPlan.buttonText(s),
            minHeightPx = CardFormStyles.px(s.buttonHeight, density()),
            cornerRadiusPx = CardFormStyles.size(s.buttonCornerRadius) * density(),
        )
        val secondary = ResolvedTextStyle(
            size = PaymentViewPlan.SECONDARY_TEXT_SIZE_SP,
            weight = PayriffTextStyle.REGULAR,
            typeface = null,
            color = s.secondaryTextColor ?: PaymentViewPlan.SECONDARY_TEXT_COLOR,
        )
        secureText.applyTextStyle(secondary)
        footer.applyTextStyle(secondary)
        val footerItem = PaymentViewPlan.footer(s)
        footer.visibility = if (footerItem != null) VISIBLE else GONE
        footerItem?.let { setTopMargin(footer, dp(it.gapAboveDp)) }
        applyTexts()
    }

    /**
     * The copy in the effective language (the form's) or the style's own text, and the footer with the logo of the
     * theme; then the state.
     */
    private fun applyTexts() {
        val language = form.effectiveLanguage()
        val secure = PaymentViewPlan.secureText(language, style)
        if (secureText.text.toString() != secure) secureText.text = secure
        if (language != footerLanguage || night != footerNight) {
            footerLanguage = language
            footerNight = night
            footer.text = poweredBy(language)
        }
        render()
    }

    /** The Pay button, the error line and which parts show (CONTRACT §16.14). Runs on every change of the form. */
    private fun render() {
        val state = controller.state
        val language = form.effectiveLanguage()
        state.isFormComplete = form.isComplete
        state.isFormProcessing = form.isProcessing
        payButton.title = state.payTitle(language, style.payButtonLabel)
        payButton.isBusy = state.isBusy
        payButton.isEnabled = state.isPayEnabled
        val error = state.errorMessage(language)
        // Only a new message: setting the same text again would make TalkBack repeat it.
        if (error != null && errorLine.text.toString() != error) errorLine.text = error
        val shown = PaymentViewPlan.content(style, showsError = error != null).associate { it.part to it.gapAboveDp }
        for ((part, view) in parts()) {
            val gap = shown[part]
            val visibility = if (gap != null) VISIBLE else GONE
            if (view.visibility != visibility) view.visibility = visibility
            if (gap != null) setTopMargin(view, dp(gap))
        }
        if (error == null && errorLine.length() > 0) errorLine.text = ""
    }

    private fun parts(): List<Pair<PaymentViewPlan.Part, View>> = listOf(
        PaymentViewPlan.Part.FORM to form,
        PaymentViewPlan.Part.PAY_BUTTON to payButton,
        PaymentViewPlan.Part.ERROR to errorLine,
        PaymentViewPlan.Part.BADGES to badges,
        PaymentViewPlan.Part.SECURE_NOTE to secureLine,
    )

    private fun pay() {
        render()
        controller.pay { orderId, clientSecret, done ->
            val current = launcher
            if (current == null) {
                done(PayriffResult.Failed(PayriffError(PayriffErrorCode.PRESENTATION_FAILED, NO_ACTIVITY_MESSAGE)))
            } else {
                // The launcher locks the form, unlocks it before the callback, and clears it after a final result.
                current.confirm(form, orderId, clientSecret) { result -> done(result) }
            }
        }
    }

    /**
     * A 3-D Secure result for this view's key that arrived after the activity was recreated, or after the view was
     * attached again: the launcher that started the payment is gone, so the form is finished here.
     */
    private fun onRestoredResult(result: PayriffResult) {
        form.onPaymentFinished(final = result !is PayriffResult.Failed)
        controller.finishPayment(result)
    }

    private fun deliver(result: PayriffResult) {
        val listener = onResultListener
        if (listener == null) {
            waitingResults += result
            PayriffLog.warning { "A PayriffPaymentView result waits for onResultListener." }
            return
        }
        listener.onResult(result)
    }

    private fun deliverWaitingResults() {
        while (waitingResults.isNotEmpty()) {
            val listener = onResultListener ?: return
            listener.onResult(waitingResults.removeAt(0))
        }
    }

    /** Registers the 3-D Secure launcher with the activity's registry under the view's key. */
    private fun registerLauncher() {
        if (launcher != null) return
        val owner = registryOwner(context)
        if (owner == null) {
            PayriffLog.warning { "PayriffPaymentView is not shown in a ComponentActivity: Pay fails with PRESENTATION_FAILED." }
            return
        }
        val activityRegistry = owner.activityResultRegistry
        val key = PaymentViewKeys.acquire(activityRegistry, PaymentViewKeys.base(id))
        registry = activityRegistry
        launcherKey = key
        launcher = PayriffCardPaymentLauncher.forRegistry(activityRegistry, key) { result -> onRestoredResult(result) }
    }

    /**
     * Unregisters the launcher. A payment that has not reached the 3-D Secure sheet stops without a result; an open
     * sheet still delivers its result, to the launcher registered next under the same key.
     */
    private fun releaseLauncher() {
        val current = launcher ?: return
        launcher = null
        val awaitingSheet = current.release()
        val activityRegistry = registry
        val key = launcherKey
        if (activityRegistry != null && key != null) PaymentViewKeys.release(activityRegistry, key)
        registry = null
        launcherKey = null
        if (!awaitingSheet && controller.state.isPaying) {
            form.setProcessing(false)
            controller.paymentAbandoned()
        }
    }

    /** Pads the content for the system bars, the display cutout and the keyboard (CONTRACT §16.14). */
    private fun applyInsets(insets: WindowInsetsCompat) {
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        val bottom = max(bars.bottom, ime.bottom)
        val params = scroll.layoutParams as LayoutParams
        if (params.leftMargin == bars.left && params.topMargin == bars.top && params.rightMargin == bars.right && params.bottomMargin == bottom) {
            return
        }
        params.setMargins(bars.left, bars.top, bars.right, bottom)
        scroll.layoutParams = params
    }

    /**
     * `view.poweredBy` with the Payriff logo (82×16 dp) in place of "Payriff": `payriff_logo`, or `payriff_logo_dark`
     * with a light wordmark in the dark theme (CONTRACT §16.14).
     */
    private fun poweredBy(language: PayriffLanguage): CharSequence {
        val text = CardStrings.view(language, CardStrings.VIEW_POWERED_BY)
        val parts = PaymentViewPlan.poweredByParts(language) ?: return text
        val asset = PaymentViewAsset.logo(night)
        val logo = ContextCompat.getDrawable(context, asset.resId) ?: return text
        val start = parts.first.length
        return SpannableString(text).apply {
            setSpan(LogoSpan(logo, dp(asset.widthDp), dp(asset.heightDp)), start, start + PaymentViewPlan.WORDMARK.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun image(asset: PaymentViewAsset): ImageView = ImageView(context).apply {
        setImageResource(asset.resId)
        scaleType = ImageView.ScaleType.FIT_CENTER
        val label = asset.accessibilityLabel
        if (label != null) contentDescription = label else importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun setTopMargin(view: View, px: Int) {
        val params = view.layoutParams as MarginLayoutParams
        if (params.topMargin == px) return
        params.topMargin = px
        view.layoutParams = params
    }

    private fun matchWidth() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    private fun density(): Float = resources.displayMetrics.density

    private fun dp(value: Int): Int = (value * density()).roundToInt()

    private fun isNight(configuration: Configuration): Boolean =
        (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private companion object {
        const val NO_ACTIVITY_MESSAGE =
            "PayriffPaymentView must be shown in a ComponentActivity (for example an AppCompatActivity) to run 3-D Secure."

        /** The activity (or other owner) of the `ActivityResultRegistry` behind [context], if any. */
        fun registryOwner(context: Context): ActivityResultRegistryOwner? {
            var current: Context? = context
            while (current != null) {
                if (current is ActivityResultRegistryOwner) return current
                current = (current as? ContextWrapper)?.baseContext
            }
            return null
        }
    }
}

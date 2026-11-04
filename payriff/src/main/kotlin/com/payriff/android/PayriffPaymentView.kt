package com.payriff.android

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Bundle
import android.os.Parcelable
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
import androidx.core.os.BundleCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.payriff.android.internal.DeviceIntegrityGuard
import com.payriff.android.internal.HostApp
import com.payriff.android.internal.Languages
import com.payriff.android.internal.PayriffLog
import com.payriff.android.internal.card.CardFormStyles
import com.payriff.android.internal.card.LockView
import com.payriff.android.internal.card.LogoSpan
import com.payriff.android.internal.card.PayButton
import com.payriff.android.internal.card.PaymentViewAsset
import com.payriff.android.internal.card.PaymentViewContent
import com.payriff.android.internal.card.PaymentViewController
import com.payriff.android.internal.card.PaymentViewKeys
import com.payriff.android.internal.card.PaymentViewPlan
import com.payriff.android.internal.card.ResolvedStrings
import com.payriff.android.internal.card.ResolvedTextStyle
import com.payriff.android.internal.card.SquircleDrawable
import com.payriff.android.internal.card.StringField
import com.payriff.android.internal.card.applyTextStyle
import com.payriff.android.internal.wallet.GooglePayButtonStyles
import com.payriff.android.internal.wallet.GooglePayButtonView
import com.payriff.android.internal.wallet.GooglePayEnvironment
import com.payriff.android.internal.wallet.GooglePayLauncher
import com.payriff.android.internal.wallet.GooglePayPayment
import com.payriff.android.internal.wallet.GooglePayTransaction
import com.payriff.android.internal.wallet.GooglePayViewDecision
import com.payriff.android.internal.wallet.WalletDividerView
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
 * **Google Pay** (CONTRACT §17.9, §20). With [PayriffConfig.googlePay] set, the view shows Google's Pay button above the
 * form, then "Or pay by card" (or 12 dp below Pay, without the divider, with [PayriffPaymentViewStyle.walletPlacement]
 * [PayriffWalletPlacement.BELOW_PAY_BUTTON]) when Google Pay is offered: the order can take it (its status, amount,
 * currency and a gateway merchant id from the order or [PayriffGooglePayConfig.gatewayMerchantId]; not its
 * `paymentMethods`), the device is not compromised, and Google Pay is ready on it. The button appears together with the
 * loaded order, in its place and without animation: Google Pay's device check runs alongside the order read, and the
 * view waits for it at most a second after the order arrived; a slower check means no Google Pay for that order.
 * Without Google Pay the layout is the card screen above. Without its own height the button is exactly as high as Pay,
 * at every text size. It runs Google Pay, then the same `/pay`, 3-D Secure and status steps as a card, and its result
 * goes to [onResultListener] like a card result. When the customer closes the Google Pay sheet nothing is reported: they
 * can try again or pay by card. While any payment runs, the form is locked, Pay shows its spinner and the Google Pay
 * button is dimmed. The order whose Google Pay sheet is open is kept in the view's saved state (an order id is not
 * secret), so a Google Pay result that arrives after the activity was recreated pays that order only, in the view with
 * the same id.
 *
 * [style] changes the colours, sizes and parts of the view ([PayriffPaymentViewStyle.showBadges],
 * [PayriffPaymentViewStyle.showSecureNote], [PayriffPaymentViewStyle.showPoweredBy]), the Pay button's word and the
 * secure line's text ([PayriffPaymentViewStyle.payButtonLabel], [PayriffPaymentViewStyle.secureNoteText]), the form's
 * look ([PayriffPaymentViewStyle.formStyle], for example `PayriffCardFormStyle(showLabels = false)`) and the Google Pay
 * row ([PayriffPaymentViewStyle.googlePayButton], [PayriffPaymentViewStyle.walletDividerText],
 * [PayriffPaymentViewStyle.showWalletDivider], [PayriffPaymentViewStyle.walletPlacement]), and the design-system options
 * of CONTRACT §18: the Pay button's whole text, corner smoothing, disabled colours and badge borders. [strings] replaces
 * any visible text (CONTRACT §18.6). The view follows the layout direction and is right-to-left in Arabic too (CONTRACT
 * §18.7, §19.3). Three listeners report a payment's start, a loaded order and an order that did not load
 * ([setOnPaymentStartedListener], [setOnOrderLoadedListener], [setOnLoadFailedListener]). [PayriffCardForm],
 * [PayriffCardPaymentLauncher] and [PayriffGooglePayButton] remain available to build your own screen.
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
     * Your own texts for this view, its form and the 3-D Secure sheet it opens (CONTRACT §18.6). Each one wins over
     * [PayriffConfig.strings]; what neither sets keeps the built-in text in [language]. `null` (the default) uses
     * [PayriffConfig.strings] alone.
     */
    public var strings: PayriffStrings? = null
        set(value) {
            if (field == value) return
            field = value
            form.viewStrings = value
            applyTexts()
        }

    /** Learns, on the main thread, that a payment of the view starts (CONTRACT §18.5). */
    public fun interface OnPaymentStartedListener {
        /**
         * Called once per attempt, before anything is sent: when Pay is tapped with a valid form ([PayriffPaymentMethod.CARD]),
         * or when the Google Pay button is tapped, before its sheet opens ([PayriffPaymentMethod.GOOGLE_PAY]).
         */
        public fun onPaymentStarted(method: PayriffPaymentMethod)
    }

    /** Learns, on the main thread, that the view loaded its order (CONTRACT §18.5). */
    public fun interface OnOrderLoadedListener {
        /**
         * Called after every successful load of the order, also when it cannot be paid by card, right after the view
         * shows it, together with Google Pay when it is offered (CONTRACT §20.3).
         */
        public fun onOrderLoaded(order: PayriffClientOrder)
    }

    /** Learns, on the main thread, that the view got no order (CONTRACT §18.5). */
    public fun interface OnLoadFailedListener {
        /**
         * Called when loading the order failed, every time, with the [PayriffError] of [Payriff.retrieveOrder]; and once
         * per order with [PayriffErrorCode.DEVICE_COMPROMISED] on a compromised device, where the view does not load
         * the order. A fatal error also goes to [onResultListener], once, after this call.
         */
        public fun onLoadFailed(error: PayriffError)
    }

    /**
     * Receives, on the main thread, the result of every payment, card or Google Pay, and, once, a fatal error that ends
     * the order. The result is for your user interface: fulfil the order on your server after it has confirmed the
     * payment. A result that arrives while no listener is set is kept until you set one.
     */
    public var onResultListener: PayriffCardResultCallback? = null
        set(value) {
            field = value
            deliverWaitingResults()
        }

    /** The order id passed to [setOrder], or `null` before. */
    public val orderId: String?
        get() = controller.orderId

    /** `true` from Pay or Google Pay until its result, the Google Pay sheet and the 3-D Secure step included. */
    public val isProcessing: Boolean
        get() = controller.state.isPaying || form.isProcessing

    private val googlePayButton = GooglePayButtonView(context, followsParentHeight = true)
    private val walletDivider = WalletDividerView(context)
    private val form = PayriffCardForm(context)
    private val payButton = PayButton(context)
    private val errorLine = TextView(context)
    private val badges = LinearLayout(context)
    private val secureLine = LinearLayout(context)
    private val secureText = TextView(context)
    private val footer = TextView(context)
    private val content = PaymentViewContent(context)
    private val column = LinearLayout(context)
    private val scroll = ScrollView(context)

    private val waitingResults = ArrayList<PayriffResult>()
    private var night = isNight(resources.configuration)
    private val badgeFrames = ArrayList<SquircleDrawable>()
    private var paymentStartedListener: OnPaymentStartedListener? = null
    private var orderLoadedListener: OnOrderLoadedListener? = null
    private var loadFailedListener: OnLoadFailedListener? = null

    /** Whether the view set itself right-to-left for Arabic (CONTRACT §19.3). */
    private var arabicLayout = false

    /** The text and theme the footer was built for; it is built again when either changes. */
    private var footerText: String? = null
    private var footerNight: Boolean? = null
    private var launcher: PayriffCardPaymentLauncher? = null
    private var registry: ActivityResultRegistry? = null
    private var launcherKey: String? = null
    private var googlePay: GooglePayLauncher? = null

    /**
     * The order whose Google Pay sheet was open when the view left its window or the activity was recreated (saved
     * state): its token, if it still arrives, pays that order only.
     */
    private var googlePaySheetOrderId: String? = null

    private val controller = PaymentViewController(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        loadOrder = { orderId, clientSecret -> Payriff.retrieveOrder(orderId, clientSecret) },
        onStateChanged = { render() },
        deliver = { result -> deliver(result) },
        // CONTRACT §16.16: a compromised device shows view.deviceNotSupported, keeps Pay off and skips the order load.
        deviceBlock = { DeviceIntegrityGuard.blockReason(context, Payriff.config) },
        startGooglePay = { scope -> googlePayDecision(scope) },
        onPaymentStarted = { method -> paymentStartedListener?.onPaymentStarted(method) },
        onOrderLoaded = { order -> orderLoadedListener?.onOrderLoaded(order) },
        onLoadFailed = { error -> loadFailedListener?.onLoadFailed(error) },
    )

    /** What a Google Pay payment of the view reports (CONTRACT §17.5, §17.8, §17.9). */
    private val googlePayHost = object : GooglePayPayment.Host {
        override fun resumableOrder(orderId: String): GooglePayPayment.Order? {
            val secret = controller.clientSecret ?: return null
            return if (orderId == controller.orderId?.trim()) googlePayOrder(orderId, secret) else null
        }

        override fun onResumed(orderId: String) {
            form.setProcessing(true)
            controller.paymentResumed()
        }

        override fun onClosed(orderId: String) {
            // Nothing is reported: the customer can try Google Pay again or pay by card.
            form.setProcessing(false)
            controller.paymentAbandoned()
        }

        override fun onFinished(result: PayriffResult) {
            form.onPaymentFinished(final = result !is PayriffResult.Failed)
            controller.finishPayment(result)
        }
    }

    init {
        buildViews()
        form.onChangeListener = PayriffCardFormListener { render() }
        payButton.setOnClickListener { pay() }
        googlePayButton.onTap = { payWithGooglePay() }
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

    /** Sets the listener of the payments' starts (CONTRACT §18.5); `null` removes it. */
    public fun setOnPaymentStartedListener(listener: OnPaymentStartedListener?) {
        paymentStartedListener = listener
    }

    /** Sets the listener of the loaded orders (CONTRACT §18.5); `null` removes it. Set it before [setOrder]. */
    public fun setOnOrderLoadedListener(listener: OnOrderLoadedListener?) {
        orderLoadedListener = listener
    }

    /** Sets the listener of the orders that did not load (CONTRACT §18.5); `null` removes it. Set it before [setOrder]. */
    public fun setOnLoadFailedListener(listener: OnLoadFailedListener?) {
        loadFailedListener = listener
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

    override fun onSaveInstanceState(): Parcelable? {
        val superState = super.onSaveInstanceState()
        val sheetOrder = googlePay?.payment?.sheetOrderId ?: googlePaySheetOrderId ?: return superState
        return Bundle().apply {
            putParcelable(STATE_SUPER, superState)
            putString(STATE_GOOGLE_PAY_SHEET_ORDER, sheetOrder)
        }
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state !is Bundle || !state.containsKey(STATE_GOOGLE_PAY_SHEET_ORDER)) {
            super.onRestoreInstanceState(state)
            return
        }
        googlePaySheetOrderId = state.getString(STATE_GOOGLE_PAY_SHEET_ORDER)
        super.onRestoreInstanceState(BundleCompat.getParcelable(state, STATE_SUPER, Parcelable::class.java))
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
        // The wallet row (CONTRACT §17.9) shows only once Google Pay is offered, at Pay's height unless its style sets
        // one (CONTRACT §20.2): the column measures Pay first, then the wallet button at that height.
        googlePayButton.visibility = GONE
        walletDivider.visibility = GONE
        content.payButton = payButton
        content.walletButton = googlePayButton
        content.walletHeight = { payHeight -> PaymentViewPlan.walletHeightPx(style.googlePayButton.height, payHeight, density()) }
        content.addView(googlePayButton, matchWidth())
        content.addView(walletDivider, matchWidth())
        content.addView(form, matchWidth())
        content.addView(payButton, matchWidth())

        errorLine.gravity = Gravity.CENTER_HORIZONTAL
        errorLine.accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        content.addView(errorLine, matchWidth())

        badges.orientation = LinearLayout.HORIZONTAL
        badges.gravity = Gravity.CENTER_HORIZONTAL
        badges.isBaselineAligned = false
        PaymentViewAsset.BADGES.forEachIndexed { index, asset ->
            // CONTRACT §18.4: the frameless mark on its frame, drawn in code at the badge's size.
            val frame = SquircleDrawable()
            badgeFrames += frame
            badges.addView(
                image(asset).apply { background = frame },
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
        // CONTRACT §18.2, §18.4: the form takes the view's smoothing and badge border colour unless its style sets them.
        form.defaults = PaymentViewPlan.formDefaults(s)
        form.style = s.formStyle
        val formLook = CardFormStyles.resolve(s.formStyle, night, density())
        // The error line looks like the form's error lines (CONTRACT §16.14: #EC4D3D, #EC5545 in the dark theme).
        errorLine.applyTextStyle(formLook.error)
        val smoothing = PaymentViewPlan.cornerSmoothing(s)
        payButton.apply(
            colors = PaymentViewPlan.buttonColors(s),
            text = PaymentViewPlan.buttonText(s),
            minHeightPx = CardFormStyles.px(s.buttonHeight, density()),
            cornerRadiusPx = CardFormStyles.size(s.buttonCornerRadius) * density(),
            smoothing = smoothing,
        )
        for (frame in badgeFrames) {
            frame.setShape(PaymentViewPlan.BADGE_CORNER_RADIUS_DP * density(), smoothing)
            frame.setStroke(PaymentViewPlan.BADGE_BORDER_DP * density(), PaymentViewPlan.badgeBorderColor(s))
        }
        val secondary = ResolvedTextStyle(
            size = PaymentViewPlan.SECONDARY_TEXT_SIZE_SP,
            weight = PayriffTextStyle.REGULAR,
            typeface = null,
            color = s.secondaryTextColor ?: PaymentViewPlan.SECONDARY_TEXT_COLOR,
        )
        secureText.applyTextStyle(secondary)
        footer.applyTextStyle(secondary)
        // CONTRACT §17.9: the divider's text in the secondary colour, its lines in the border colour.
        walletDivider.applyStyle(secondary, formLook.borderColor)
        val footerItem = PaymentViewPlan.footer(s)
        footer.visibility = if (footerItem != null) VISIBLE else GONE
        footerItem?.let { setTopMargin(footer, dp(it.gapAboveDp)) }
        applyTexts()
    }

    /**
     * The copy in the effective language (the form's), the app's texts or the style's own text, and the footer with the
     * logo of the theme; the layout direction of Arabic; then the state.
     */
    private fun applyTexts() {
        val texts = texts()
        updateLayoutDirection(texts.language)
        val secure = PaymentViewPlan.secureText(texts, style)
        if (secureText.text.toString() != secure) secureText.text = secure
        walletDivider.text = PaymentViewPlan.walletDividerText(texts, style)
        val poweredBy = texts.text(StringField.POWERED_BY)
        if (poweredBy != footerText || night != footerNight) {
            footerText = poweredBy
            footerNight = night
            footer.text = poweredBy(poweredBy)
        }
        render()
    }

    /** The view's texts (CONTRACT §18.6): [strings], then [PayriffConfig.strings], then the built-in ones. */
    private fun texts(): ResolvedStrings = ResolvedStrings(form.effectiveLanguage(), strings, Payriff.config?.strings)

    /** Right-to-left in Arabic (CONTRACT §19.3); otherwise the view follows its parent's layout direction. */
    private fun updateLayoutDirection(language: PayriffLanguage) {
        val arabic = Languages.isRightToLeft(language)
        if (arabic == arabicLayout) return
        arabicLayout = arabic
        layoutDirection = if (arabic) LAYOUT_DIRECTION_RTL else LAYOUT_DIRECTION_INHERIT
    }

    /**
     * The Pay button, the error line, the Google Pay button, which parts show and their order (CONTRACT §16.14, §17.9,
     * §20.1). Runs on every change of the form.
     */
    private fun render() {
        val state = controller.state
        val texts = texts()
        state.isFormComplete = form.isComplete
        state.isFormProcessing = form.isProcessing
        payButton.title = state.payTitle(texts, style)
        payButton.isBusy = state.isBusy
        payButton.isEnabled = state.isPayEnabled
        val error = state.errorMessage(texts)
        // Only a new message: setting the same text again would make TalkBack repeat it.
        if (error != null && errorLine.text.toString() != error) errorLine.text = error
        val showsWallet = state.isGooglePayShown && showGooglePayButton(state.googlePayOffer)
        googlePayButton.isEnabled = state.isGooglePayEnabled
        val shown = PaymentViewPlan.content(style, showsError = error != null, showsWallet = showsWallet).associate { it.part to it.gapAboveDp }
        val parts = parts()
        // The children in the plan's order: the wallet button moves when the style changes its placement.
        content.arrange(parts.map { it.second })
        for ((part, view) in parts) {
            val gap = shown[part]
            val visibility = if (gap != null) VISIBLE else GONE
            if (view.visibility != visibility) view.visibility = visibility
            if (gap != null) setTopMargin(view, dp(gap))
        }
        if (error == null && errorLine.length() > 0) errorLine.text = ""
    }

    /**
     * Google's button for [offer], in the style and theme of the view: the style's corner radius, else the Pay button's
     * (CONTRACT §17.9). Its height comes from [content], which measures it at Pay's height unless the style sets one
     * (CONTRACT §20.2). `false` when there is no offer or Google's button cannot be created.
     */
    private fun showGooglePayButton(offer: GooglePayTransaction?): Boolean {
        offer ?: return false
        val options = GooglePayButtonStyles.options(
            style = style.googlePayButton,
            night = night,
            density = density(),
            fallbackHeight = style.buttonHeight,
            fallbackCornerRadius = style.buttonCornerRadius,
            transaction = offer,
        )
        return googlePayButton.configure(options)
    }

    /** The parts of [content] with their views, in the order of the style's wallet placement (CONTRACT §20.1). */
    private fun parts(): List<Pair<PaymentViewPlan.Part, View>> {
        val order = PaymentViewPlan.parts(style.walletPlacement)
        return listOf(
            PaymentViewPlan.Part.WALLET_BUTTON to googlePayButton,
            PaymentViewPlan.Part.WALLET_DIVIDER to walletDivider,
            PaymentViewPlan.Part.FORM to form,
            PaymentViewPlan.Part.PAY_BUTTON to payButton,
            PaymentViewPlan.Part.ERROR to errorLine,
            PaymentViewPlan.Part.BADGES to badges,
            PaymentViewPlan.Part.SECURE_NOTE to secureLine,
        ).sortedBy { (part, _) -> order.indexOf(part) }
    }

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
     * The Google Pay button was tapped (CONTRACT §17.5): its payment locks the form like Pay, and reports to
     * [googlePayHost].
     */
    private fun payWithGooglePay() {
        render()
        controller.payWithGooglePay { orderId, clientSecret, transaction ->
            val current = googlePay
            if (current == null) {
                googlePayHost.onFinished(PayriffResult.Failed(PayriffError(PayriffErrorCode.PRESENTATION_FAILED, NO_ACTIVITY_MESSAGE)))
            } else {
                form.setProcessing(true)
                current.payment.start(googlePayOrder(orderId, clientSecret), transaction)
            }
        }
    }

    /** The order of a Google Pay payment, with the language, texts and layout direction of its 3-D Secure sheet. */
    private fun googlePayOrder(orderId: String, clientSecret: String): GooglePayPayment.Order =
        GooglePayPayment.Order(orderId, clientSecret, form.effectiveLanguage(), strings, layoutDirection == LAYOUT_DIRECTION_RTL)

    /**
     * CONTRACT §17.3 and §20.3 for a load of the order that starts now, with this view's Google Pay launcher, if it has
     * one: the device check starts in [scope] at once, alongside the order read. Returns the decision for the order.
     */
    private fun googlePayDecision(scope: CoroutineScope): suspend (PayriffClientOrder) -> GooglePayTransaction? {
        val current = googlePay
        return GooglePayViewDecision(
            scope = scope,
            config = Payriff.config,
            canPresent = current != null,
            appName = HostApp.label(context),
            isReadyToPay = { request -> current != null && current.isReadyToPay(request) },
        )::decide
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
        registerGooglePay(activityRegistry, key)
    }

    /**
     * Google Pay's launcher (CONTRACT §17.5), when [PayriffConfig.googlePay] is set: the Google Pay sheet and its 3-D
     * Secure sheet, under keys made from [key], so their results reach the view with the same id after a recreation.
     */
    private fun registerGooglePay(activityRegistry: ActivityResultRegistry, key: String) {
        val resumeOrderId = googlePaySheetOrderId
        googlePaySheetOrderId = null
        val config = Payriff.config
        if (config?.googlePay == null) return
        val activity = GooglePayLauncher.activityOf(context) ?: return
        googlePay = try {
            GooglePayLauncher(activity, activityRegistry, key, GooglePayEnvironment.of(config.apiBaseUrl), googlePayHost, resumeOrderId)
        } catch (e: RuntimeException) {
            PayriffLog.warning { "Google Pay is not available: ${e.javaClass.simpleName}" }
            null
        }
    }

    /**
     * Unregisters the launcher. A payment that has not reached the 3-D Secure sheet stops without a result; an open
     * sheet still delivers its result, to the launcher registered next under the same key.
     */
    private fun releaseLauncher() {
        val current = launcher ?: return
        launcher = null
        val awaitingCardSheet = current.release()
        // One payment runs at a time: the card's 3-D Secure sheet, or Google Pay's sheets.
        val awaitingSheet = releaseGooglePay() || awaitingCardSheet
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

    /**
     * Unregisters Google Pay's launcher. Returns `true` when its Google Pay or 3-D Secure sheet will still deliver a
     * result; the order of an open Google Pay sheet is kept for the next launcher and the saved state.
     */
    private fun releaseGooglePay(): Boolean {
        val current = googlePay ?: return false
        googlePay = null
        val awaiting = current.release()
        googlePaySheetOrderId = current.payment.sheetOrderId
        return awaiting
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
     * The footer, `view.poweredBy` or [PayriffStrings.poweredBy], with the Payriff logo (82×16 dp) in place of its first
     * "Payriff", or after it and a space when it has none (CONTRACT §18.6): `payriff_logo`, or `payriff_logo_dark` with a
     * light wordmark in the dark theme (CONTRACT §16.14). The text keeps "Payriff" for accessibility services.
     */
    private fun poweredBy(text: String): CharSequence {
        val (before, after) = PaymentViewPlan.poweredByParts(text)
        val asset = PaymentViewAsset.logo(night)
        val logo = ContextCompat.getDrawable(context, asset.resId) ?: return text
        val start = before.length
        return SpannableString(before + PaymentViewPlan.WORDMARK + after).apply {
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
        const val STATE_SUPER = "payriff.paymentView.super"
        const val STATE_GOOGLE_PAY_SHEET_ORDER = "payriff.paymentView.googlePaySheetOrderId"

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

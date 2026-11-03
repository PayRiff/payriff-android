package com.payriff.android

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Bundle
import android.os.Parcelable
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.core.os.BundleCompat
import com.payriff.android.internal.DeviceIntegrityGuard
import com.payriff.android.internal.HostApp
import com.payriff.android.internal.PayriffLog
import com.payriff.android.internal.card.PaymentViewKeys
import com.payriff.android.internal.wallet.GooglePayAvailability
import com.payriff.android.internal.wallet.GooglePayButtonController
import com.payriff.android.internal.wallet.GooglePayButtonStyles
import com.payriff.android.internal.wallet.GooglePayButtonView
import com.payriff.android.internal.wallet.GooglePayEnvironment
import com.payriff.android.internal.wallet.GooglePayLauncher
import com.payriff.android.internal.wallet.GooglePayPayment
import com.payriff.android.internal.wallet.GooglePayTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * A standalone Google Pay button (CONTRACT §17.10), for a payment screen of your own, as [PayriffCardForm] and
 * [PayriffCardPaymentLauncher] are for cards. [PayriffPaymentView] already shows Google Pay; with it you need none.
 *
 * ```
 * <com.payriff.android.PayriffGooglePayButton
 *     android:id="@+id/googlePayButton"
 *     android:layout_width="match_parent"
 *     android:layout_height="wrap_content" />
 * ```
 * ```
 * val googlePay = findViewById<PayriffGooglePayButton>(R.id.googlePayButton)
 * googlePay.setOnAvailabilityChangeListener { available -> orSeparator.isVisible = available }
 * googlePay.setOnResultListener { result -> handle(result) }
 * googlePay.setOrder(order.orderId, order.clientSecret) // from your backend
 * ```
 *
 * It needs [PayriffConfig.googlePay]. When it appears it reads the order with [Payriff.retrieveOrder] and shows
 * Google's official Pay button only when Google Pay is offered (CONTRACT §17.3): the order lists `GOOGLE_PAY` with its
 * gateway, the device is not compromised ([Payriff.isDeviceCompromised]), and Google Pay is ready on the device. Until
 * then, and when it is not offered, it shows nothing and has zero size; the availability listener says when that
 * changes. An order that does not load is not reported: the button just does not show (a non-fatal error loads again
 * the next time it appears).
 *
 * A tap runs Google Pay, then the same `/pay`, 3-D Secure and status steps as a card, and the result goes to the result
 * listener on the main thread. When the customer closes the Google Pay sheet, the result is
 * [PayriffResult.Cancelled] with [PayriffCancelReason.USER_CLOSED] and the order's id with status
 * [PayriffPaymentStatus.UNKNOWN] (CONTRACT §17.8). While a payment runs, and after a result that ends the order, the
 * button is dimmed and does not pay again. Treat only [PayriffResult.Success] as paid, and fulfil on your server.
 *
 * Show it in a `ComponentActivity` (an `AppCompatActivity` or `FragmentActivity` is one), in one of its fragments, or in
 * Compose with `AndroidView`, like [PayriffPaymentView]: the Google Pay and 3-D Secure sheets' results are registered
 * with the activity's `ActivityResultRegistry` under a key made from the button's id, and the order whose Google Pay
 * sheet is open is kept in its saved state, so a result that arrives after the activity was recreated goes to the
 * button with the same id, for the same order only. Give the button an id, and set the order and the listeners when you
 * create it.
 */
public class PayriffGooglePayButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    /** Receives, on the main thread, whether the button shows: Google Pay is offered for the order (CONTRACT §17.3). */
    public fun interface OnAvailabilityChangeListener {
        /** [available] is `true` when the button appears, and `false` while it shows nothing. */
        public fun onAvailabilityChange(available: Boolean)
    }

    /**
     * The look of Google's button: its type, theme, corner radius (16 by default) and height (44 by default, at least
     * 40). Changing it keeps the order.
     */
    public var style: PayriffGooglePayButtonStyle = PayriffGooglePayButtonStyle()
        set(value) {
            if (field == value) return
            field = value
            render()
        }

    /** The order id passed to [setOrder], or `null` before. */
    public val orderId: String?
        get() = controller.orderId

    /** Whether the button shows: Google Pay is offered for the order. */
    public val isAvailable: Boolean
        get() = showsButton

    /** `true` from the tap until the result, the Google Pay sheet and the 3-D Secure step included. */
    public val isProcessing: Boolean
        get() = controller.state.isPaying

    private val button = GooglePayButtonView(context)
    private val waitingResults = ArrayList<PayriffResult>()
    private var availabilityListener: OnAvailabilityChangeListener? = null
    private var resultListener: PayriffCardResultCallback? = null
    private var reportedAvailability: Boolean? = null
    private var showsButton = false
    private var night = isNight(resources.configuration)
    private var launcher: GooglePayLauncher? = null
    private var registry: ActivityResultRegistry? = null
    private var launcherKey: String? = null

    /** The order whose Google Pay sheet was open when the button left its window or the activity was recreated. */
    private var sheetOrderId: String? = null

    private val controller = GooglePayButtonController(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        loadOrder = { orderId, clientSecret -> Payriff.retrieveOrder(orderId, clientSecret) },
        decide = { order -> decide(order) },
        onStateChanged = { render() },
        deliver = { result -> deliver(result) },
        // CONTRACT §16.16, §17.11: when the button appears.
        deviceBlock = { DeviceIntegrityGuard.blockReason(context, Payriff.config) },
        // CONTRACT §17.3 item 1: without the configuration or an activity host, no order is loaded.
        unavailable = {
            when {
                Payriff.config?.googlePay == null -> GooglePayAvailability.NOT_CONFIGURED
                launcher == null -> GooglePayAvailability.NO_HOST
                else -> null
            }
        },
    )

    /** What the button's Google Pay payment reports (CONTRACT §17.5, §17.8). */
    private val host = object : GooglePayPayment.Host {
        override fun resumableOrder(orderId: String): GooglePayPayment.Order? {
            val secret = controller.clientSecret ?: return null
            return if (orderId == controller.orderId?.trim()) googlePayOrder(orderId, secret) else null
        }

        override fun onResumed(orderId: String) = controller.paymentResumed()

        override fun onClosed(orderId: String) = controller.paymentClosed(orderId)

        override fun onFinished(result: PayriffResult) = controller.finishPayment(result)
    }

    init {
        button.onTap = { pay() }
        button.visibility = GONE
        addView(button, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    /**
     * Sets the order to pay: the `orderId` and the `clientSecret` (`pr_cs_…`) your server returned when it created the
     * order. The button loads the order when it is on screen. Another order resets the button; setting the same order
     * again changes nothing.
     */
    public fun setOrder(orderId: String, clientSecret: String) {
        controller.setOrder(orderId, clientSecret)
    }

    /**
     * Sets the listener that learns, on the main thread, whether the button shows. When Google Pay was already decided,
     * it is called at once with the current value.
     */
    public fun setOnAvailabilityChangeListener(listener: OnAvailabilityChangeListener?) {
        availabilityListener = listener
        reportedAvailability?.let { listener?.onAvailabilityChange(it) }
    }

    /**
     * Sets the listener of the payment results, called on the main thread once per payment, a closed Google Pay sheet
     * included. A result that arrives while no listener is set is kept until you set one.
     */
    public fun setOnResultListener(listener: PayriffCardResultCallback?) {
        resultListener = listener
        deliverWaitingResults()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Payriff.onContextAvailable(context)
        registerLauncher()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        // Also called right after onAttachedToWindow: the button appears.
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
            render()
        }
    }

    /** Zero size while Google Pay is not offered (CONTRACT §17.10). */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (!showsButton) {
            setMeasuredDimension(0, 0)
            return
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onSaveInstanceState(): Parcelable? {
        val superState = super.onSaveInstanceState()
        val sheetOrder = launcher?.payment?.sheetOrderId ?: sheetOrderId ?: return superState
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
        sheetOrderId = state.getString(STATE_GOOGLE_PAY_SHEET_ORDER)
        super.onRestoreInstanceState(BundleCompat.getParcelable(state, STATE_SUPER, Parcelable::class.java))
    }

    /** Google's button while Google Pay is offered, dimmed while it cannot be tapped; then the availability listener. */
    private fun render() {
        val state = controller.state
        val offer = state.offer
        val shows = offer != null && button.configure(
            GooglePayButtonStyles.options(
                style = style,
                night = night,
                density = resources.displayMetrics.density,
                fallbackHeight = GooglePayButtonStyles.DEFAULT_HEIGHT_DP,
                fallbackCornerRadius = GooglePayButtonStyles.DEFAULT_CORNER_RADIUS_DP,
                transaction = offer,
            ),
        )
        button.isEnabled = state.isEnabled
        if (shows != showsButton) {
            showsButton = shows
            button.visibility = if (shows) VISIBLE else GONE
            requestLayout()
        }
        // Unknown while undecided: nothing is reported, except that a button that showed is gone.
        if (!state.isDecided && reportedAvailability != true) return
        if (reportedAvailability == shows) return
        reportedAvailability = shows
        availabilityListener?.onAvailabilityChange(shows)
    }

    private fun pay() {
        controller.pay { orderId, clientSecret, transaction ->
            val current = launcher
            if (current == null) {
                controller.finishPayment(PayriffResult.Failed(PayriffError(PayriffErrorCode.PRESENTATION_FAILED, NO_ACTIVITY_MESSAGE)))
            } else {
                current.payment.start(googlePayOrder(orderId, clientSecret), transaction)
            }
        }
    }

    /** The order of a Google Pay payment; its 3-D Secure sheet takes the button's layout direction (CONTRACT §18.7). */
    private fun googlePayOrder(orderId: String, clientSecret: String): GooglePayPayment.Order =
        GooglePayPayment.Order(orderId, clientSecret, null, rtl = layoutDirection == LAYOUT_DIRECTION_RTL)

    /** CONTRACT §17.3 for the loaded [order], with this button's Google Pay launcher, if it has one. */
    private suspend fun decide(order: PayriffClientOrder): GooglePayTransaction? {
        val current = launcher
        return GooglePayAvailability.decide(
            config = Payriff.config,
            canPresent = current != null,
            order = order,
            appName = HostApp.label(context),
            isReadyToPay = { request -> current != null && current.isReadyToPay(request) },
        )
    }

    private fun deliver(result: PayriffResult) {
        val listener = resultListener
        if (listener == null) {
            waitingResults += result
            PayriffLog.warning { "A PayriffGooglePayButton result waits for its result listener." }
            return
        }
        listener.onResult(result)
    }

    private fun deliverWaitingResults() {
        while (waitingResults.isNotEmpty()) {
            val listener = resultListener ?: return
            listener.onResult(waitingResults.removeAt(0))
        }
    }

    /** Registers Google Pay's launcher with the activity's registry under the button's key (CONTRACT §17.5). */
    private fun registerLauncher() {
        if (launcher != null) return
        val resumeOrderId = sheetOrderId
        sheetOrderId = null
        val config = Payriff.config
        // Without Google Pay in the configuration the button never shows; the decision says why.
        if (config?.googlePay == null) return
        val owner = registryOwner(context)
        val activity = GooglePayLauncher.activityOf(context)
        if (owner == null || activity == null) {
            PayriffLog.warning { "PayriffGooglePayButton is not shown in a ComponentActivity: Google Pay is not offered." }
            return
        }
        val activityRegistry = owner.activityResultRegistry
        val key = PaymentViewKeys.acquire(activityRegistry, PaymentViewKeys.base(id, PaymentViewKeys.GOOGLE_PAY_BUTTON))
        launcher = try {
            GooglePayLauncher(activity, activityRegistry, key, GooglePayEnvironment.of(config.apiBaseUrl), host, resumeOrderId)
        } catch (e: RuntimeException) {
            PayriffLog.warning { "Google Pay is not available: ${e.javaClass.simpleName}" }
            PaymentViewKeys.release(activityRegistry, key)
            return
        }
        registry = activityRegistry
        launcherKey = key
    }

    /**
     * Unregisters the launcher. A payment whose `/pay` is still running stops without a result; an open Google Pay or
     * 3-D Secure sheet still delivers its result, to the launcher registered next under the same key.
     */
    private fun releaseLauncher() {
        val current = launcher ?: return
        launcher = null
        val awaiting = current.release()
        sheetOrderId = current.payment.sheetOrderId
        val activityRegistry = registry
        val key = launcherKey
        if (activityRegistry != null && key != null) PaymentViewKeys.release(activityRegistry, key)
        registry = null
        launcherKey = null
        if (!awaiting) controller.paymentAbandoned()
    }

    private fun isNight(configuration: Configuration): Boolean =
        (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private companion object {
        const val NO_ACTIVITY_MESSAGE =
            "PayriffGooglePayButton must be shown in a ComponentActivity (for example an AppCompatActivity) to run Google Pay."
        const val STATE_SUPER = "payriff.googlePayButton.super"
        const val STATE_GOOGLE_PAY_SHEET_ORDER = "payriff.googlePayButton.googlePaySheetOrderId"

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

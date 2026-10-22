package com.payriff.android.internal

import android.app.Activity
import android.app.AlertDialog
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Window
import android.view.WindowManager
import android.webkit.CookieManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.os.ConfigurationCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.payriff.android.Payriff
import com.payriff.android.PayriffConfig
import com.payriff.android.PayriffError
import com.payriff.android.PayriffErrorCode
import com.payriff.android.PayriffLanguage
import com.payriff.android.PayriffResult
import com.payriff.android.internal.card.CardSetup
import com.payriff.android.internal.card.CardSheetController
import com.payriff.android.internal.card.CardSheetIntents
import com.payriff.android.internal.card.CardSheetRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Translucent activity that draws the 3-D Secure sheet of the card flow (CONTRACT §9.1, §16.8), driven by
 * [CardSheetController]. It survives rotation and theme changes without reloading (manifest `configChanges`); after
 * process death it resumes with a single status check.
 *
 * Its window is protected with `FLAG_SECURE` (CONTRACT §16.0 item 4): screenshots, screen recordings, screen
 * sharing and the recents thumbnail show nothing of the bank page. Debuggable apps may turn this off with
 * [PayriffConfig.allowScreenCaptureInDebug].
 */
internal class PayriffActivity : ComponentActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sheet: SheetView? = null
    private var closeDialog: AlertDialog? = null
    private var flowId: String? = null
    private var config = PayriffConfig()
    private var language = PayriffLanguage.EN
    private var nightMode = false
    private var resultDelivered = false
    private var screenProtected = true
    private var card: CardSheetController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Payriff.onContextAvailable(this)
        disableWindowTransitions()
        val request = CardSheetIntents.read(intent)
        if (request == null) {
            finishImmediately(PayriffError(PayriffErrorCode.UNKNOWN, "The 3-D Secure sheet was started without a card payment."))
            return
        }
        start(request, savedInstanceState)
    }

    override fun onPause() {
        super.onPause()
        // Persist cookies before the customer may leave for a bank app (the process can be killed there).
        try {
            CookieManager.getInstance().flush()
        } catch (e: RuntimeException) {
            PayriffLog.warning { "Could not flush cookies: ${e.javaClass.simpleName}" }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Nothing but this marker: after process death the sheet resumes with a status check.
        if (card != null) outState.putBoolean(CARD_STARTED, true)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val night = isNightMode(newConfig)
        if (night != nightMode) {
            nightMode = night
            applySystemBars()
            // The WebViews keep their white backdrop in both themes (§9.1).
            sheet?.applyPalette(palette())
        }
        sheet?.root?.let(ViewCompat::requestApplyInsets)
    }

    override fun onDestroy() {
        closeDialog?.dismiss()
        closeDialog = null
        card?.dispose()
        scope.cancel()
        if (isFinishing) releaseGuard()
        super.onDestroy()
    }

    override fun finish() {
        super.finish()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    /**
     * The card was already submitted with `/pay`, and the sheet runs the next action (CONTRACT §16.8). The card-flow
     * setup (publishable key, `clientSecret`) is checked again, because the configuration may have changed after
     * process death.
     */
    private fun start(request: CardSheetRequest, savedInstanceState: Bundle?) {
        if (!PaymentGuard.confirm(request.flowId)) {
            finishImmediately(PaymentGuard.inProgressError())
            return
        }
        flowId = request.flowId
        val currentConfig = Payriff.config
        val credentials = try {
            CardSetup.credentials(currentConfig, request.clientSecret)
        } catch (error: PayriffError) {
            finishImmediately(error)
            return
        }
        config = currentConfig ?: PayriffConfig()
        applyScreenProtection()
        val localeTag = ConfigurationCompat.getLocales(resources.configuration)[0]?.toLanguageTag()
        language = Languages.effective(request.language, config.language, localeTag)
        nightMode = isNightMode(resources.configuration)
        applySystemBars()
        val sheetView = installSheet { card?.onUserClose() }
        val controller = CardSheetController(
            activity = this,
            sheet = sheetView,
            request = request,
            credentials = credentials,
            language = language,
            screenProtected = screenProtected,
            transport = Payriff.transport(),
            scope = scope,
        )
        card = controller
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                card?.onUserClose()
            }
        })
        controller.start(restored = savedInstanceState?.getBoolean(CARD_STARTED) == true)
        if (savedInstanceState == null) sheetView.animateIn()
    }

    /** Creates the sheet (CONTRACT §9.1) as the content view and keeps it above the system bars and the keyboard. */
    private fun installSheet(onCloseRequested: () -> Unit): SheetView {
        val sheetView = SheetView(
            context = this,
            palette = palette(),
            title = text(StringKey.TITLE),
            closeLabel = text(StringKey.CLOSE),
            onCloseRequested = onCloseRequested,
        )
        sheet = sheetView
        setContentView(sheetView.root)
        ViewCompat.setOnApplyWindowInsetsListener(sheetView.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            sheetView.root.setInsets(bars.left, bars.top, bars.right, bars.bottom, ime.bottom)
            WindowInsetsCompat.CONSUMED
        }
        return sheetView
    }

    /** The close confirmation (§16.8); [onConfirm] runs the single status check. */
    fun confirmClose(onConfirm: () -> Unit) {
        if (closeDialog?.isShowing == true || isFinishing) return
        val theme = if (nightMode) {
            android.R.style.Theme_DeviceDefault_Dialog_Alert
        } else {
            android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
        }
        val dialog = AlertDialog.Builder(this, theme)
            .setTitle(text(StringKey.CANCEL_CONFIRM_TITLE))
            .setMessage(text(StringKey.CANCEL_CONFIRM_MESSAGE))
            .setPositiveButton(text(StringKey.CANCEL_CONFIRM_YES)) { _, _ -> onConfirm() }
            .setNegativeButton(text(StringKey.CANCEL_CONFIRM_NO), null)
            .create()
        dialog.setOnDismissListener { if (closeDialog === dialog) closeDialog = null }
        if (screenProtected) dialog.window?.let(::secure)
        closeDialog = dialog
        dialog.show()
    }

    fun dismissCloseConfirmation() {
        closeDialog?.dismiss()
        closeDialog = null
    }

    fun setCloseEnabled(enabled: Boolean) {
        sheet?.setCloseEnabled(enabled)
    }

    /** The result delivered if the sheet ends without [finishWith], for example when the system destroys it. */
    fun setProvisionalResult(result: PayriffResult) {
        if (!resultDelivered) setResult(Activity.RESULT_OK, PaymentIntents.resultIntent(result))
    }

    /** Delivers [result] and slides the sheet out. Only the first call counts. */
    fun finishWith(result: PayriffResult) {
        if (resultDelivered) return
        resultDelivered = true
        setResult(Activity.RESULT_OK, PaymentIntents.resultIntent(result))
        // Free the guard now: the caller may start another payment as soon as it receives the result.
        releaseGuard()
        dismissCloseConfirmation()
        val view = sheet
        if (view == null) {
            finish()
            return
        }
        view.animateOut(::finishNow)
        view.root.postDelayed(::finishNow, FINISH_FALLBACK_MILLIS)
    }

    private fun finishImmediately(error: PayriffError) {
        PayriffLog.error { "The 3-D Secure sheet could not start: ${error.code} ${error.message}" }
        resultDelivered = true
        setResult(Activity.RESULT_OK, PaymentIntents.resultIntent(PayriffResult.Failed(error)))
        releaseGuard()
        finish()
    }

    private fun finishNow() {
        if (!isFinishing) finish()
    }

    private fun releaseGuard() {
        flowId?.let(PaymentGuard::release)
    }

    /**
     * CONTRACT §16.0 item 4: the window is secure unless a debuggable app opted out with
     * [PayriffConfig.allowScreenCaptureInDebug]. Release builds always protect it.
     */
    private fun applyScreenProtection() {
        screenProtected = screenProtectionOn(config)
        if (screenProtected) {
            secure(window)
        } else {
            PayriffLog.warning { "Screen protection is off (allowScreenCaptureInDebug in a debuggable app)." }
        }
    }

    private fun secure(window: Window) = window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

    private fun applySystemBars() {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = if (nightMode) {
                SystemBarStyle.dark(Color.TRANSPARENT)
            } else {
                SystemBarStyle.light(Color.TRANSPARENT, DARK_SCRIM)
            },
        )
    }

    private fun disableWindowTransitions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
    }

    private fun palette(): Palette = if (nightMode) Palette.DARK else Palette.LIGHT

    private fun text(key: StringKey): String = PayriffStrings.get(key, language)

    private fun isNightMode(configuration: Configuration): Boolean =
        (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private companion object {
        const val FINISH_FALLBACK_MILLIS = 500L
        const val DARK_SCRIM = 0x801B1B1B.toInt()
        const val CARD_STARTED = "payriff.card.started"
    }
}

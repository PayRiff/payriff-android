package com.payriff.android.internal.card

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.net.http.SslError
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import com.payriff.android.PayriffLanguage
import com.payriff.android.PayriffResult
import com.payriff.android.internal.AndroidExternalApps
import com.payriff.android.internal.ExternalLauncher
import com.payriff.android.internal.ExternalOutcome
import com.payriff.android.internal.HttpTransport
import com.payriff.android.internal.PayriffActivity
import com.payriff.android.internal.PayriffLog
import com.payriff.android.internal.PayriffStrings
import com.payriff.android.internal.SheetMessage
import com.payriff.android.internal.SheetView
import com.payriff.android.internal.StringKey
import com.payriff.android.internal.Urls
import kotlinx.coroutines.CoroutineScope

/**
 * The 3-D Secure sheet of [PayriffActivity] (CONTRACT §16.8): the native sheet of §9.1, with a visible WebView for the
 * challenge or redirect page and up to two hidden WebViews for the 3DS Method, under the native loader. Implements the
 * WebView side of [ThreeDsFlow]: the navigation policy runs in `shouldOverrideUrlLoading`, again when a main-frame page
 * starts (POST navigations and their redirects skip `shouldOverrideUrlLoading`), and a main-frame request to Payriff's
 * gateway callback is noticed in `shouldInterceptRequest`, which also sees POSTs.
 *
 * The WebViews' page and frame requests are recorded in [ThreeDsWebsiteData]; when the sheet goes away, [dispose]
 * removes their website data before it destroys the WebViews (CONTRACT §9.1a).
 */
internal class CardSheetController(
    private val activity: PayriffActivity,
    private val sheet: SheetView,
    private val request: CardSheetRequest,
    credentials: CardCredentials,
    private val language: PayriffLanguage,
    private val screenProtected: Boolean,
    transport: HttpTransport,
    scope: CoroutineScope,
) : ThreeDsUi {

    private val apiBase = credentials.host
    private val flow = ThreeDsFlow(
        scope = scope,
        ui = this,
        api = CardApiClient(credentials, transport),
        orderId = request.orderId,
        apiBase = apiBase,
        clock = SystemClock::elapsedRealtime,
    )
    private val mainHandler = Handler(Looper.getMainLooper())
    // An intent's browser_fallback_url loads in the 3DS WebView only over https (§16.8): never a plain-http request.
    private val externalLauncher by lazy { ExternalLauncher(AndroidExternalApps(activity)) }
    private var mainWebView: WebView? = null
    private val hiddenWebViews = ArrayList<WebView>()
    private val websiteData = ThreeDsWebsiteData()
    private var mainFrameUrl: String? = null
    private var disposed = false

    /** Runs the handed-over next action; after process death (or without one) a single status check. */
    fun start(restored: Boolean) {
        // If the sheet dies before the flow finishes, the card was still submitted: never cancelled(userClosed).
        activity.setProvisionalResult(CardResults.notFinal(request.orderId, null))
        val action = if (restored) null else CardHandoffs.take(request.flowId)
        flow.start(action)
    }

    fun onUserClose() = flow.onUserClose()

    /**
     * The sheet goes away: the payment ended, or the system destroyed the sheet, which then resumes without WebViews.
     * The payment's website data is removed first, then the WebViews are destroyed. The result is already set, so this
     * never blocks or changes it.
     */
    fun dispose() {
        if (disposed) return
        disposed = true
        flow.dispose()
        val views = hiddenWebViews + listOfNotNull(mainWebView)
        hiddenWebViews.clear()
        mainWebView = null
        websiteData.clear(AndroidWebsiteData)
        destroyWebViews(views)
    }

    // region ThreeDsUi

    override fun showLoader(verifying: Boolean) {
        sheet.showStatus(if (verifying) text(StringKey.VERIFYING) else null)
    }

    override fun showPage() = sheet.showContent()

    override fun postChallenge(url: String, body: ByteArray) {
        val webView = ensureMainWebView() ?: return
        PayriffLog.debug { "3DS challenge POST to ${Urls.redact(url)}" }
        webView.postUrl(url, body)
    }

    override fun loadRedirect(url: String) {
        val webView = ensureMainWebView() ?: return
        PayriffLog.debug { "3DS redirect to ${Urls.redact(url)}" }
        webView.loadUrl(url)
    }

    override fun runMethod(methodUrl: String?, methodBody: ByteArray?, methodUrlServer: String?) {
        destroyHiddenWebViews()
        if (methodUrl != null) createHiddenWebView()?.postUrl(methodUrl, methodBody ?: ByteArray(0))
        if (methodUrlServer != null) createHiddenWebView()?.postUrl(methodUrlServer, ByteArray(0))
    }

    override fun destroyHiddenWebViews() {
        if (hiddenWebViews.isEmpty()) return
        val doomed = hiddenWebViews.toList()
        hiddenWebViews.clear()
        // Never destroy a WebView inside a WebView callback: post it.
        mainHandler.post { destroyWebViews(doomed) }
    }

    override fun stopWebViews() {
        val views = listOfNotNull(mainWebView) + hiddenWebViews
        mainHandler.post { views.forEach(WebView::stopLoading) }
    }

    override fun showPageLoadError() {
        sheet.showMessage(
            SheetMessage(
                message = text(StringKey.PAGE_LOAD_ERROR),
                primaryLabel = text(StringKey.RETRY),
                onPrimary = { flow.onRetry() },
                secondaryLabel = text(StringKey.CLOSE),
                onSecondary = { flow.onUserClose() },
            ),
        )
    }

    override fun confirmClose(onConfirm: () -> Unit) = activity.confirmClose(onConfirm)

    override fun dismissCloseConfirmation() = activity.dismissCloseConfirmation()

    override fun setCloseEnabled(enabled: Boolean) = activity.setCloseEnabled(enabled)

    override fun finishWith(result: PayriffResult) = activity.finishWith(result)

    // endregion

    // region WebViews

    private fun ensureMainWebView(): WebView? {
        mainWebView?.let { return it }
        val webView = createWebView(ThreeDsWebViewClient())
        if (webView == null) {
            mainHandler.post { flow.onWebViewUnavailable() }
            return null
        }
        webView.webChromeClient = SheetChromeClient()
        mainWebView = webView
        sheet.webStack.addView(webView, FrameLayout.LayoutParams(MATCH, MATCH))
        // Every page of the 3DS sheet is the bank's, so it is always inset (CONTRACT §9.1, §16.8).
        sheet.setBankPageInset(webView, inset = true)
        return webView
    }

    private fun createHiddenWebView(): WebView? {
        val webView = createWebView(HiddenWebViewClient()) ?: return null
        webView.webChromeClient = HiddenChromeClient()
        // Invisible, behind the native loader, but attached so the page runs normally.
        webView.visibility = View.INVISIBLE
        webView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        hiddenWebViews += webView
        sheet.webStack.addView(webView, 0, ViewGroup.LayoutParams(1, 1))
        return webView
    }

    @SuppressLint("SetJavaScriptEnabled") // Bank ACS pages are JavaScript applications.
    private fun createWebView(client: WebViewClient): WebView? {
        val webView = try {
            WebView(activity)
        } catch (e: Exception) {
            PayriffLog.error { "Could not create a WebView: ${e.javaClass.simpleName}" }
            return null
        }
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            // A 3DS page opens no windows: target=_blank links load in place.
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setGeolocationEnabled(false)
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.setBackgroundColor(Color.WHITE)
        if (screenProtected && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            webView.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
        }
        webView.webViewClient = client
        return webView
    }

    /**
     * Destroys [views], which the sheet no longer tracks. When they are the payment's last WebViews and a page was
     * loaded, the HTTP cache is cleared through one of them first (CONTRACT §9.1a); Android has one cache per app.
     */
    private fun destroyWebViews(views: List<WebView>) {
        if (views.isEmpty()) return
        val lastOfThePayment = mainWebView == null && hiddenWebViews.isEmpty()
        if (lastOfThePayment && !websiteData.isEmpty) AndroidWebsiteData.clearCache(views.first())
        views.forEach(::destroyWebView)
    }

    private fun destroyWebView(webView: WebView) {
        webView.stopLoading()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    private fun openExternal(url: String, source: WebView) {
        when (externalLauncher.open(url) { fallback -> source.loadUrl(fallback) }) {
            ExternalOutcome.OPENED_APP -> PayriffLog.info { "Opened an external app for ${Urls.redact(url)}" }
            ExternalOutcome.LOADED_FALLBACK -> Unit
            ExternalOutcome.FAILED -> PayriffLog.warning { "No app can open ${Urls.redact(url)}" }
        }
    }

    /** Applies a verdict of the policy to a page that already started loading (the fallback path). */
    private fun applyStarted(view: WebView, url: String, nav: ThreeDsNav) {
        when (nav) {
            ThreeDsNav.ALLOW, ThreeDsNav.ALLOW_CALLBACK -> Unit
            ThreeDsNav.FINISH, ThreeDsNav.BLOCK -> view.stopLoading()
            ThreeDsNav.EXTERNAL -> {
                view.stopLoading()
                openExternal(url, view)
            }
        }
    }

    private inner class ThreeDsWebViewClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            return when (flow.onNavigation(url, request.isForMainFrame)) {
                ThreeDsNav.ALLOW, ThreeDsNav.ALLOW_CALLBACK -> {
                    websiteData.record(url)
                    false
                }
                ThreeDsNav.FINISH, ThreeDsNav.BLOCK -> true
                ThreeDsNav.EXTERNAL -> {
                    openExternal(url, view)
                    true
                }
            }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            mainFrameUrl = url
            // The page was requested, even if the policy stops it now.
            websiteData.record(url)
            applyStarted(view, url, flow.onNavigation(url, isMainFrame = true))
        }

        /** Runs on a WebView network thread. Observes only: returning `null` lets every request load unchanged. */
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url.toString()
            // Every main-frame request, POSTs included (CONTRACT §9.1a).
            if (request.isForMainFrame) websiteData.record(url)
            if (flow.navigator.isCallback(url)) {
                if (request.isForMainFrame) {
                    flow.navigator.markCallback()
                    mainHandler.post { flow.onCallbackObserved() }
                } else {
                    mainHandler.post { flow.onSubframeCallbackObserved() }
                }
            }
            return null
        }

        override fun onPageCommitVisible(view: WebView, url: String) = flow.onPageVisible()

        override fun onPageFinished(view: WebView, url: String) = flow.onPageVisible()

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            val url = request.url.toString()
            if (error.description?.contains(ERR_ABORTED) == true) return
            if (Urls.parse(url)?.isHttp == false) {
                // An app-scheme redirect that bypassed shouldOverrideUrlLoading: a hand-off, not a load failure.
                applyStarted(view, url, flow.onNavigation(url, isMainFrame = true))
                return
            }
            PayriffLog.warning { "3DS main-frame load error ${error.errorCode} for ${Urls.redact(url)}" }
            flow.onMainFrameError()
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            PayriffLog.warning { "TLS error ${error.primaryError} for ${Urls.redact(error.url)}" }
            if (error.url == mainFrameUrl) flow.onMainFrameError()
        }

        @RequiresApi(Build.VERSION_CODES.O)
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            PayriffLog.error { "The 3DS WebView renderer went away (crashed: ${detail.didCrash()})." }
            if (view === mainWebView) {
                mainWebView = null
                destroyWebView(view)
                flow.onMainFrameError()
            } else {
                hiddenWebViews.remove(view)
                destroyWebView(view)
            }
            return true
        }
    }

    /** The hidden 3DS Method WebViews: the same policy, but they never open apps or end the flow. */
    private inner class HiddenWebViewClient : WebViewClient() {
        private val policy = ThreeDsNavigator(apiBase)

        private fun allowed(url: String, isMainFrame: Boolean): Boolean =
            when (policy.classify(url, isMainFrame)) {
                ThreeDsNav.ALLOW, ThreeDsNav.ALLOW_CALLBACK -> true
                ThreeDsNav.FINISH, ThreeDsNav.BLOCK, ThreeDsNav.EXTERNAL -> false
            }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            if (!allowed(url, request.isForMainFrame)) return true
            websiteData.record(url)
            return false
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            websiteData.record(url)
            if (!allowed(url, isMainFrame = true)) view.stopLoading()
        }

        /** Runs on a WebView network thread. Records every main-frame request (CONTRACT §9.1a); nothing else changes. */
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (request.isForMainFrame) websiteData.record(request.url.toString())
            return null
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
        }

        @RequiresApi(Build.VERSION_CODES.O)
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            hiddenWebViews.remove(view)
            destroyWebView(view)
            return true
        }
    }

    private open inner class DenyingChromeClient : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) = request.deny()

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) =
            callback.invoke(origin, false, false)
    }

    /**
     * The invisible 3DS Method pages only fingerprint the device for the bank: they never talk to the customer. Their
     * `alert`, `confirm`, `prompt` and `beforeunload` dialogs are dismissed unseen, so a page nobody can see cannot put
     * a dialog (or a text prompt) over the payment sheet.
     */
    private inner class HiddenChromeClient : DenyingChromeClient() {
        override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            result.cancel()
            return true
        }

        override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            result.cancel()
            return true
        }

        override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean {
            result.cancel()
            return true
        }

        override fun onJsBeforeUnload(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            result.confirm()
            return true
        }
    }

    private inner class SheetChromeClient : DenyingChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            sheet.setPageLoading(newProgress < PROGRESS_COMPLETE)
        }
    }

    // endregion

    private fun text(key: StringKey): String = PayriffStrings.get(key, language)

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val ERR_ABORTED = "ERR_ABORTED"
        const val PROGRESS_COMPLETE = 100
    }
}

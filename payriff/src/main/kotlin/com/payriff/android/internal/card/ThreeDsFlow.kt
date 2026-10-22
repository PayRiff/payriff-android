package com.payriff.android.internal.card

import com.payriff.android.PayriffError
import com.payriff.android.PayriffErrorCode
import com.payriff.android.PayriffResult
import com.payriff.android.internal.PayriffLog
import com.payriff.android.internal.Urls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.URLEncoder

/** Timings of CONTRACT §16.7. */
internal class CardTimings(
    val threeDsIntervalMillis: Long = 1_000L,
    val threeDsTries: Int = 30,
    val statusIntervalMillis: Long = 2_500L,
    val statusTimeoutMillis: Long = 120_000L,
)

/** Effects of [ThreeDsFlow] on the 3DS sheet (CONTRACT §16.8). Called on the main thread. */
internal interface ThreeDsUi {
    /** The native loader over the page; [verifying] adds the "Checking payment…" text. */
    fun showLoader(verifying: Boolean)

    /** The 3DS page is visible. */
    fun showPage()

    /** Loads [url] in the visible WebView with a native form POST of [body] (`application/x-www-form-urlencoded`). */
    fun postChallenge(url: String, body: ByteArray)

    /** Loads [url] in the visible WebView (GET). */
    fun loadRedirect(url: String)

    /**
     * 3DS Method: POSTs [methodBody] to [methodUrl] in hidden WebView 1 and an empty body to [methodUrlServer] in hidden
     * WebView 2, each when set. The hidden WebViews stay invisible under the native loader.
     */
    fun runMethod(methodUrl: String?, methodBody: ByteArray?, methodUrlServer: String?)
    fun destroyHiddenWebViews()
    fun stopWebViews()
    fun showPageLoadError()
    fun confirmClose(onConfirm: () -> Unit)
    fun dismissCloseConfirmation()
    fun setCloseEnabled(enabled: Boolean)

    /** Delivers [result] and closes the sheet. Called exactly once. */
    fun finishWith(result: PayriffResult)
}

/**
 * Step 4–5 of CONTRACT §16.7 and the 3DS sheet of §16.8, after `/pay` answered: runs the next action, the `/three-ds`
 * loop, the 3DS WebView navigation, status polling and the close path. Main thread only. The card flow never ends as
 * `cancelled(userClosed)`: the card was already submitted.
 */
internal class ThreeDsFlow(
    private val scope: CoroutineScope,
    private val ui: ThreeDsUi,
    private val api: CardApi,
    private val orderId: String,
    apiBase: String,
    private val clock: () -> Long,
    private val timings: CardTimings = CardTimings(),
) {
    private enum class Phase { IDLE, LOADER, PAGE, VERIFYING, DONE }

    /** The policy of the visible WebView. */
    val navigator = ThreeDsNavigator(apiBase)

    private var phase = Phase.IDLE
    private var loopJob: Job? = null
    private var pollJob: Job? = null
    private var checkJob: Job? = null
    private var reload: (() -> Unit)? = null
    private var challengeBody: ByteArray? = null
    private var pageShown = false
    private var errorShown = false

    var isFinished: Boolean = false
        private set

    /** Runs [action], or resumes with a single status check when it is gone (after process death). */
    fun start(action: NextAction?) {
        if (action == null) {
            PayriffLog.info { "3DS sheet restored for orderId=$orderId: checking the status once." }
            checkOnce { CardResults.notFinal(orderId, it) }
            return
        }
        run(action)
    }

    private fun run(action: NextAction) {
        PayriffLog.info { "3DS next action: $action" }
        when (action) {
            is NextAction.Challenge -> {
                val body = formBody(action.form)
                challengeBody = body
                openPage { ui.postChallenge(action.url, body) }
            }
            is NextAction.Redirect -> openPage { ui.loadRedirect(action.url) }
            is NextAction.Method -> startThreeDsLoop(action)
            NextAction.Wait -> startThreeDsLoop(null)
            NextAction.CheckStatus -> verify()
            NextAction.Invalid -> checkOnce {
                PayriffError(PayriffErrorCode.UNEXPECTED_RESPONSE, "Payriff answered with a next step the SDK cannot run.").let(PayriffResult::Failed)
            }
        }
    }

    // region WebView events (main WebView only)

    /** The policy verdict for a navigation of the visible WebView; also updates the flow. */
    fun onNavigation(url: String, isMainFrame: Boolean): ThreeDsNav {
        if (phase != Phase.PAGE) return ThreeDsNav.BLOCK
        val nav = navigator.classify(url, isMainFrame)
        when (nav) {
            ThreeDsNav.ALLOW_CALLBACK -> onCallbackObserved()
            ThreeDsNav.FINISH -> {
                PayriffLog.info { "3DS finished at ${Urls.redact(url)}" }
                verify()
            }
            ThreeDsNav.BLOCK -> PayriffLog.debug { "3DS blocked ${Urls.redact(url)}" }
            ThreeDsNav.ALLOW, ThreeDsNav.EXTERNAL -> Unit
        }
        return nav
    }

    /** The gateway callback was requested or loaded: start status polling in parallel (the page stays visible). */
    fun onCallbackObserved() {
        if (phase != Phase.PAGE) return
        navigator.markCallback()
        startPolling()
    }

    /**
     * A subframe requested the gateway callback (some bank pages post the result from an iframe). It neither arms nor
     * ends the navigation policy (`card_3ds_navigation.json`), but status polling starts, so a final status still
     * closes the sheet.
     */
    fun onSubframeCallbackObserved() {
        if (phase == Phase.PAGE) startPolling()
    }

    /** Page content of the visible WebView is showing. */
    fun onPageVisible() {
        if (phase != Phase.PAGE || pageShown || errorShown) return
        pageShown = true
        ui.showPage()
    }

    /** A main-frame load of the visible WebView failed. */
    fun onMainFrameError() {
        if (phase != Phase.PAGE || errorShown) return
        if (navigator.callbackSeen) {
            // The bank already answered Payriff's callback; the status decides.
            verify()
            return
        }
        errorShown = true
        ui.showPageLoadError()
    }

    /** "Try again" on the page-load error: repeats the original load. */
    fun onRetry() {
        if (phase != Phase.PAGE || !errorShown) return
        errorShown = false
        pageShown = false
        ui.showLoader(verifying = false)
        reload?.invoke()
    }

    /** No WebView could be created, so the bank page cannot be shown. */
    fun onWebViewUnavailable() {
        checkOnce {
            PayriffResult.Failed(
                PayriffError(PayriffErrorCode.PRESENTATION_FAILED, "The 3-D Secure page cannot be shown: Android System WebView is unavailable."),
            )
        }
    }

    // endregion

    /** ✕ or back: the confirmation dialog, then a single status check (CONTRACT §16.8). Ignored while verifying. */
    fun onUserClose() {
        if (isFinished || phase == Phase.VERIFYING) return
        ui.confirmClose {
            if (!isFinished && phase != Phase.VERIFYING) {
                PayriffLog.info { "The customer closed the 3DS sheet: checking the status once." }
                checkOnce { CardResults.notFinal(orderId, it) }
            }
        }
    }

    fun dispose() {
        loopJob?.cancel()
        pollJob?.cancel()
        checkJob?.cancel()
        challengeBody?.fill(0)
    }

    private fun openPage(load: () -> Unit) {
        phase = Phase.PAGE
        reload = load
        pageShown = false
        errorShown = false
        ui.setCloseEnabled(true)
        ui.showLoader(verifying = false)
        load()
    }

    /** 3DS Method (when [method] has work), then `GET /three-ds` every second, at most 30 times. */
    private fun startThreeDsLoop(method: NextAction.Method?) {
        phase = Phase.LOADER
        ui.setCloseEnabled(true)
        ui.showLoader(verifying = false)
        if (method != null && method.hasWork) runMethod(method)
        loopJob = scope.launch {
            repeat(timings.threeDsTries) {
                delay(timings.threeDsIntervalMillis)
                val next = try {
                    api.threeDs(orderId)
                } catch (e: PayriffError) {
                    if (e.code == PayriffErrorCode.NETWORK_ERROR) return@repeat
                    ui.destroyHiddenWebViews()
                    checkOnce { PayriffResult.Failed(e) }
                    return@launch
                }
                when {
                    next is NextAction.Wait -> Unit
                    next is NextAction.Method && !next.hasWork -> Unit
                    next is NextAction.Method -> runMethod(next)
                    else -> {
                        ui.destroyHiddenWebViews()
                        run(next)
                        return@launch
                    }
                }
            }
            PayriffLog.info { "No 3DS step after ${timings.threeDsTries} tries: polling the status." }
            verify()
        }
    }

    private fun runMethod(method: NextAction.Method) {
        PayriffLog.info { "3DS Method: $method" }
        val body = method.methodUrl?.let { formBody(listOf(METHOD_DATA to method.methodData.orEmpty())) }
        ui.runMethod(method.methodUrl, body, method.methodUrlServer)
    }

    /** Status polling behind the "Checking payment…" loader (CONTRACT §16.7 step 5). */
    private fun verify() {
        if (isFinished || phase == Phase.VERIFYING) return
        phase = Phase.VERIFYING
        loopJob?.cancel()
        ui.dismissCloseConfirmation()
        ui.stopWebViews()
        ui.destroyHiddenWebViews()
        ui.setCloseEnabled(false)
        ui.showLoader(verifying = true)
        startPolling()
    }

    private fun startPolling() {
        if (isFinished || pollJob?.isActive == true) return
        PayriffLog.debug { "Status polling started for orderId=$orderId" }
        pollJob = scope.launch { finish(poll()) }
    }

    /** `GET /status` every 2.5 s with a 120 s timeout; network errors keep polling, other errors end it. */
    private suspend fun poll(): PayriffResult {
        val started = clock()
        var last: CardStatus? = null
        while (true) {
            try {
                val status = api.status(orderId)
                last = status
                CardResults.finalResult(orderId, status)?.let { return it }
            } catch (e: PayriffError) {
                if (e.code != PayriffErrorCode.NETWORK_ERROR) return PayriffResult.Failed(e)
            }
            if (clock() - started + timings.statusIntervalMillis > timings.statusTimeoutMillis) {
                PayriffLog.info { "Status still not final after ${timings.statusTimeoutMillis / MILLIS_PER_SECOND} s." }
                return CardResults.notFinal(orderId, last)
            }
            delay(timings.statusIntervalMillis)
        }
    }

    /** One status check: a final status maps (§8.2), anything else gives [onNotFinal] of the last status seen. */
    private fun checkOnce(onNotFinal: (CardStatus?) -> PayriffResult) {
        if (isFinished) return
        phase = Phase.VERIFYING
        loopJob?.cancel()
        pollJob?.cancel()
        ui.dismissCloseConfirmation()
        ui.stopWebViews()
        ui.destroyHiddenWebViews()
        ui.setCloseEnabled(false)
        ui.showLoader(verifying = true)
        checkJob = scope.launch {
            val status = try {
                api.status(orderId)
            } catch (e: PayriffError) {
                PayriffLog.warning { "Status check failed: ${e.code}" }
                null
            }
            finish(status?.let { CardResults.finalResult(orderId, it) } ?: onNotFinal(status))
        }
    }

    private fun finish(result: PayriffResult) {
        if (isFinished) return
        isFinished = true
        phase = Phase.DONE
        PayriffLog.info { "Card payment finished: ${result.javaClass.simpleName} for orderId=$orderId" }
        loopJob?.cancel()
        challengeBody?.fill(0)
        ui.dismissCloseConfirmation()
        ui.stopWebViews()
        ui.destroyHiddenWebViews()
        ui.finishWith(result)
        pollJob?.cancel()
        checkJob?.cancel()
    }

    private companion object {
        const val METHOD_DATA = "threeDSMethodData"
        const val MILLIS_PER_SECOND = 1_000L

        /** `application/x-www-form-urlencoded` bytes of [pairs], as a browser form submits them. */
        fun formBody(pairs: List<Pair<String, String>>): ByteArray =
            pairs.joinToString("&") { (key, value) -> encode(key) + "=" + encode(value) }.toByteArray(Charsets.US_ASCII)

        fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
    }
}

package com.payriff.android.internal.card

import com.payriff.android.internal.Urls

/** What the 3DS WebView does with a navigation (CONTRACT §16.8, `card_3ds_navigation.json`). */
internal enum class ThreeDsNav {
    /** Load it. */
    ALLOW,

    /** Payriff's bank callback: load it, never cancel it, and arm the flow. */
    ALLOW_CALLBACK,

    /** Cancel it, close the WebView and resolve the status. */
    FINISH,

    /** Hand it to the OS and keep the WebView. */
    EXTERNAL,

    /** Cancel it silently. */
    BLOCK,
}

/**
 * The 3DS navigation policy of one WebView. A gateway callback is an https main-frame URL on the host of
 * [com.payriff.android.PayriffConfig.apiBaseUrl], which follows the configured region and environment
 * (case-insensitive, same port), whose path starts with `/api/v3/gateway/` and has a `callback` segment. After a
 * callback, the next main-frame navigation that is neither another callback nor `about:blank` ends the flow. Before
 * it, https is allowed, app schemes go to the OS, and every other main-frame scheme is blocked.
 *
 * Thread-safe: [markCallback] may run on the WebView's network thread (`shouldInterceptRequest`), where a POST to the
 * callback is visible even though `shouldOverrideUrlLoading` never sees it.
 */
internal class ThreeDsNavigator(apiBase: String) {

    private val apiHost: String? = Urls.parse(apiBase)?.host
    private val apiPort: Int = Urls.parse(apiBase)?.effectivePort ?: -1

    @Volatile
    var callbackSeen: Boolean = false
        private set

    fun isCallback(url: String?): Boolean {
        val parsed = Urls.parse(url) ?: return false
        if (parsed.scheme != "https" || parsed.host == null || parsed.host != apiHost || parsed.effectivePort != apiPort) return false
        if (!parsed.path.startsWith(GATEWAY_PREFIX)) return false
        return parsed.path.split('/').any { it == CALLBACK_SEGMENT }
    }

    fun markCallback() {
        callbackSeen = true
    }

    @Synchronized
    fun classify(url: String, isMainFrame: Boolean): ThreeDsNav {
        val parsed = Urls.parse(url)
        if (!isMainFrame) {
            // Subframes neither arm nor end the flow; only web content may load in them, never an app hand-off.
            return if (parsed != null && (parsed.isHttp || parsed.scheme in WEB_INTERNAL_SCHEMES)) ThreeDsNav.ALLOW else ThreeDsNav.BLOCK
        }
        if (parsed == null) return ThreeDsNav.BLOCK
        if (isCallback(url)) {
            markCallback()
            return ThreeDsNav.ALLOW_CALLBACK
        }
        if (parsed.scheme == "about" && parsed.path == "blank") return ThreeDsNav.ALLOW
        if (callbackSeen) return ThreeDsNav.FINISH
        if (parsed.scheme == "https") return ThreeDsNav.ALLOW
        if (parsed.scheme in NON_APP_SCHEMES) return ThreeDsNav.BLOCK
        return ThreeDsNav.EXTERNAL
    }

    private companion object {
        const val GATEWAY_PREFIX = "/api/v3/gateway/"
        const val CALLBACK_SEGMENT = "callback"
        val WEB_INTERNAL_SCHEMES = setOf("about", "data", "blob", "javascript")

        /** Main-frame schemes that are neither https nor an app hand-off: blocked. */
        val NON_APP_SCHEMES = setOf("http", "about", "data", "blob", "javascript", "file", "content", "filesystem", "ws", "wss")
    }
}

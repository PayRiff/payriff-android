package com.payriff.android.internal.card

import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import com.payriff.android.internal.ParsedUrl
import com.payriff.android.internal.PayriffLog
import com.payriff.android.internal.Urls

/**
 * The website data that the WebViews of one 3-D Secure payment leave behind (CONTRACT §9.1a). Android keeps WebView
 * data per app, so a payment cannot get a store of its own. Instead, the 3-D Secure sheet passes every http(s) page and
 * frame its WebViews request to [record], and [clear] removes the data of their origins when the payment ends:
 * - the storage that `WebStorage.deleteOrigin` removes for each origin;
 * - every cookie that `CookieManager` returns for the origin, expired with `Path=/`, once host-only and once for the
 *   host and each parent domain of at least two labels ([expiryCookies]); then `flush`.
 *
 * The sheet clears the HTTP cache itself, through its last WebView ([AndroidWebsiteData.clearCache]). The app's other
 * website data is not touched on purpose, but data of the same hosts goes with it.
 *
 * Thread-safe: [record] also runs on WebView network threads (`shouldInterceptRequest`). The Android calls sit behind
 * [Platform], so these rules are unit-tested on the JVM.
 */
internal class ThreeDsWebsiteData {

    /** The Android side, implemented by [AndroidWebsiteData]. Every method may throw. */
    interface Platform {
        /** `WebStorage.deleteOrigin`: removes the storage that WebView keeps for [origin]. */
        fun deleteOrigin(origin: String)

        /** `CookieManager.getCookie`: the cookies sent to [url] (`a=1; b=2`), or `null`. */
        fun cookies(url: String): String?

        /** `CookieManager.setCookie` for [url]. */
        fun setCookie(url: String, cookie: String)

        /** `CookieManager.flush`: writes the cookies to disk. */
        fun flush()
    }

    /** Origin -> host, in the order first seen. Guarded by `this`. */
    private val origins = LinkedHashMap<String, String>()

    /** `true` while nothing was recorded: the payment never loaded a page in a WebView. */
    val isEmpty: Boolean
        @Synchronized get() = origins.isEmpty()

    /** Records the origin of [url] when it is an http(s) URL with a host; anything else is ignored. */
    fun record(url: String?) {
        val parsed = Urls.parse(url) ?: return
        val origin = originOf(parsed) ?: return
        val host = parsed.host ?: return
        synchronized(this) {
            if (origin !in origins && origins.size < MAX_ORIGINS) origins[origin] = host
        }
    }

    /**
     * Removes the website data of the recorded origins through [platform], on the main thread: their storage, then
     * their cookies, then `flush`. Does nothing when nothing was recorded. Never throws: a failing call is skipped, and
     * the failures are logged.
     */
    fun clear(platform: Platform) {
        val recorded = synchronized(this) { origins.toList() }
        if (recorded.isEmpty()) return
        var failures = 0
        fun attempt(call: () -> Unit) {
            try {
                call()
            } catch (e: RuntimeException) {
                failures++
            }
        }
        for ((origin, _) in recorded) attempt { platform.deleteOrigin(origin) }
        for ((origin, host) in recorded) {
            val url = cookieUrl(origin)
            var names = emptyList<String>()
            attempt { names = cookieNames(platform.cookies(url)) }
            for (name in names) {
                for (cookie in expiryCookies(name, host)) attempt { platform.setCookie(url, cookie) }
            }
        }
        attempt { platform.flush() }
        if (failures > 0) {
            PayriffLog.warning { "3-D Secure website data: $failures call(s) failed for ${recorded.size} origin(s)." }
        } else {
            PayriffLog.debug { "3-D Secure website data removed for ${recorded.size} origin(s)." }
        }
    }

    companion object {
        /** A payment requests a handful of origins. The cap bounds the work at its end, on the main thread. */
        const val MAX_ORIGINS = 64

        /**
         * The origin of an http(s) URL with a host, for example `https://acs.bank.az`: lower case, with the port only
         * when it is not the scheme's default. `null` for any other URL.
         */
        fun originOf(url: String?): String? = Urls.parse(url)?.let(::originOf)

        private fun originOf(url: ParsedUrl): String? {
            val host = url.host
            if (!url.isHttp || host.isNullOrEmpty()) return null
            val defaultPort = if (url.scheme == "https") HTTPS_PORT else HTTP_PORT
            val port = if (url.port >= 0 && url.port != defaultPort) ":${url.port}" else ""
            return "${url.scheme}://$host$port"
        }

        /**
         * The URL whose cookies are expired for [origin]: its root. `CookieManager` returns every cookie there that a
         * `Path=/` expiry can remove.
         */
        fun cookieUrl(origin: String): String = "$origin/"

        /** The cookie names in a `getCookie` value (`a=1; b=2` -> `a`, `b`), without blanks or repeats. */
        fun cookieNames(cookies: String?): List<String> = cookies.orEmpty().split(';')
            .filter { '=' in it }
            .map { it.substringBefore('=').trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        /**
         * The `Domain=` values for a cookie of [host]: the host, then each parent domain of at least two labels
         * (`acs.bank.az` -> `acs.bank.az`, `bank.az`). An IP address has no parent domains.
         */
        fun cookieDomains(host: String): List<String> {
            val labels = host.split('.')
            val isIpAddress = host.startsWith("[") || host.all { it.isDigit() || it == '.' }
            if (isIpAddress || labels.any { it.isEmpty() }) return listOf(host)
            return listOf(host) + (1..labels.size - 2).map { labels.drop(it).joinToString(".") }
        }

        /**
         * The `setCookie` values that expire the cookie [name] of [host]: `name=; Max-Age=0; Path=/` host-only, then
         * with `Domain=` set to each of [cookieDomains].
         */
        fun expiryCookies(name: String, host: String): List<String> {
            val expired = "$name=; Max-Age=0; Path=/"
            return listOf(expired) + cookieDomains(host).map { "$expired; Domain=$it" }
        }

        private const val HTTP_PORT = 80
        private const val HTTPS_PORT = 443
    }
}

/**
 * [ThreeDsWebsiteData.Platform] on Android's WebView storage, which every WebView of the app shares. Main thread; every
 * method may throw, for example without a WebView provider.
 */
internal object AndroidWebsiteData : ThreeDsWebsiteData.Platform {

    override fun deleteOrigin(origin: String) = WebStorage.getInstance().deleteOrigin(origin)

    override fun cookies(url: String): String? = CookieManager.getInstance().getCookie(url)

    /** Asynchronous, so the main thread does not wait for each cookie; [flush] runs after them. */
    override fun setCookie(url: String, cookie: String) = CookieManager.getInstance().setCookie(url, cookie, null)

    override fun flush() = CookieManager.getInstance().flush()

    /**
     * Clears the WebView HTTP cache through [webView], before it is destroyed. Android has one cache per app, so this
     * clears the whole app's WebView cache (CONTRACT §9.1a). Never throws.
     */
    fun clearCache(webView: WebView) {
        try {
            webView.clearCache(true)
        } catch (e: RuntimeException) {
            PayriffLog.warning { "Could not clear the WebView cache: ${e.javaClass.simpleName}" }
        }
    }
}

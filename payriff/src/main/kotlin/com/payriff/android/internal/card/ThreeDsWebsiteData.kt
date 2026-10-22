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
 * frame its WebViews request to [record], and [clear] removes the data of those URLs when the payment ends:
 * - the storage that `WebStorage.deleteOrigin` removes for each origin;
 * - every cookie that `CookieManager` returns for a recorded URL, expired for that URL: for `Path=/` and for every path
 *   prefix of the URL's path (`/acs`, `/acs/challenge`, …) and its trailing-slash form where the path goes on with it
 *   (`/acs/`, …) ([pathPrefixes]), once host-only and once for the host and each parent domain of at least two labels
 *   ([expiryCookies]), with `; Secure` added for https URLs so `__Secure-` cookies go too (a `__Host-` cookie only
 *   host-only, `Path=/`, `Secure`); then `flush`.
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

    /** A recorded page: the URL to expire cookies at, its host, the path prefixes to clear, and whether it is https. */
    private class Page(val cookieUrl: String, val host: String, val paths: List<String>, val secure: Boolean)

    /** Origin -> host, in the order first seen; drives `deleteOrigin`. Guarded by `this`. */
    private val origins = LinkedHashMap<String, String>()

    /** Cookie URL -> page, in the order first seen; drives the cookie expiry. Guarded by `this`. */
    private val pages = LinkedHashMap<String, Page>()

    /** `true` while nothing was recorded: the payment never loaded a page in a WebView. */
    val isEmpty: Boolean
        @Synchronized get() = origins.isEmpty()

    /** Records the origin and path of [url] when it is an http(s) URL with a host; anything else is ignored. */
    fun record(url: String?) {
        val parsed = Urls.parse(url) ?: return
        val origin = originOf(parsed) ?: return
        val host = parsed.host ?: return
        val path = normalizePath(parsed.path)
        val cookieUrl = origin + path
        synchronized(this) {
            if (origin !in origins && origins.size < MAX_ORIGINS) origins[origin] = host
            if (cookieUrl !in pages && pages.size < MAX_PAGES) {
                pages[cookieUrl] = Page(cookieUrl, host, pathPrefixes(path), parsed.scheme == "https")
            }
        }
    }

    /**
     * Removes the website data of the recorded pages through [platform], on the main thread: their storage, then their
     * cookies, then `flush`. Does nothing when nothing was recorded. Never throws: a failing call is skipped, and the
     * failures are logged.
     */
    fun clear(platform: Platform) {
        val recordedOrigins: List<Map.Entry<String, String>>
        val recordedPages: List<Page>
        synchronized(this) {
            recordedOrigins = origins.entries.toList()
            recordedPages = pages.values.toList()
        }
        if (recordedOrigins.isEmpty()) return
        var failures = 0
        fun attempt(call: () -> Unit) {
            try {
                call()
            } catch (e: RuntimeException) {
                failures++
            }
        }
        for (entry in recordedOrigins) attempt { platform.deleteOrigin(entry.key) }
        for (page in recordedPages) {
            var names = emptyList<String>()
            attempt { names = cookieNames(platform.cookies(page.cookieUrl)) }
            for (name in names) {
                for (cookie in expiryCookies(name, page.host, page.paths, page.secure)) {
                    attempt { platform.setCookie(page.cookieUrl, cookie) }
                }
            }
        }
        attempt { platform.flush() }
        if (failures > 0) {
            PayriffLog.warning { "3-D Secure website data: $failures call(s) failed for ${recordedOrigins.size} origin(s)." }
        } else {
            PayriffLog.debug { "3-D Secure website data removed for ${recordedOrigins.size} origin(s)." }
        }
    }

    companion object {
        /** A payment requests a handful of origins. The cap bounds the work at its end, on the main thread. */
        const val MAX_ORIGINS = 64

        /** The same cap for distinct page URLs (one origin can have several paths). */
        const val MAX_PAGES = 64

        private const val HOST_COOKIE_PREFIX = "__Host-"

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

        /** The URL's path for cookie matching: the raw path, or `/` when it is empty. */
        fun normalizePath(path: String): String = if (path.isEmpty()) "/" else path

        /**
         * `Path=/` and every prefix of [path] that ends at a `/` or at its end, with and without that `/`:
         * `/acs/challenge` gives `/`, `/acs`, `/acs/`, `/acs/challenge`, and `/acs/challenge/` adds `/acs/challenge/`.
         * These are all the paths of the cookies that are sent to a URL with [path] (path-match, RFC 6265 5.1.4), so
         * each is expired; the Flutter SDK expires the same paths. [path] is the raw (encoded) path, as cookies store
         * it. The list has no repeats and always starts with `/`.
         */
        fun pathPrefixes(path: String): List<String> {
            val prefixes = linkedSetOf("/")
            if (!path.startsWith('/')) return prefixes.toList()
            for (i in 1 until path.length) {
                if (path[i] == '/') {
                    prefixes += path.substring(0, i)
                    prefixes += path.substring(0, i + 1)
                }
            }
            prefixes += path
            return prefixes.toList()
        }

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
         * The `setCookie` values that expire the cookie [name] of [host] for each of [paths]: host-only, then with
         * `Domain=` set to each of [cookieDomains], adding `; Secure` for an https URL so `__Secure-` cookies are
         * removed too. A `__Host-` cookie is host-only with `Path=/` and `Secure` only, as its prefix requires.
         */
        fun expiryCookies(name: String, host: String, paths: List<String>, secure: Boolean): List<String> {
            if (name.startsWith(HOST_COOKIE_PREFIX)) return listOf("$name=; Max-Age=0; Path=/; Secure")
            val suffix = if (secure) "; Secure" else ""
            val out = ArrayList<String>(paths.size * (cookieDomains(host).size + 1))
            for (path in paths) {
                out += "$name=; Max-Age=0; Path=$path$suffix"
                for (domain in cookieDomains(host)) out += "$name=; Max-Age=0; Path=$path; Domain=$domain$suffix"
            }
            return out
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

package com.payriff.android.internal

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import java.util.Locale

/** What became of a URL the 3-D Secure page handed to another app (CONTRACT §16.0 item 5, §16.8). */
internal enum class ExternalOutcome {
    /** Another app (or the app store) was started. */
    OPENED_APP,

    /** No app could take an `intent:` URL, so its https `browser_fallback_url` was loaded in the WebView instead. */
    LOADED_FALLBACK,

    /** Nothing was opened. The sheet stays as it is. */
    FAILED,
}

/**
 * Hands a non-web URL of the 3-D Secure page, such as a bank app's link, to the OS (CONTRACT §16.0 item 5, §16.8)
 * without ever crashing the host app:
 * - URLs with `file:` or `content:` data, and oversized URLs, are refused;
 * - `intent:` URLs are parsed with `Intent.parseUri` and hardened: BROWSABLE only, no explicit component or
 *   selector, no URI-grant flags;
 * - when no app takes an `intent:` URL, its `browser_fallback_url` is loaded if it is https (the 3-D Secure sheet
 *   never loads plain http), otherwise the store page of its package is opened;
 * - every platform call that can throw (`parseUri` throws `NumberFormatException` for a malformed `launchFlags`,
 *   `startActivity` throws `FileUriExposedException` or "Failure from system") is caught and logged, and the
 *   sheet stays open.
 *
 * The Android calls sit behind [Platform], so these decisions are unit-tested on the JVM.
 */
internal class ExternalLauncher<I : Any>(private val platform: Platform<I>) {

    /** The Android side, implemented by [AndroidExternalApps]. Every method may throw. */
    interface Platform<I : Any> {
        /** `Intent.parseUri(url, Intent.URI_INTENT_SCHEME)`. */
        fun parseIntentUrl(url: String): I

        /** An `ACTION_VIEW` intent for [url]. */
        fun viewIntent(url: String): I

        /** Adds `CATEGORY_BROWSABLE`, clears the component and the selector, and drops every URI-grant flag. */
        fun harden(intent: I)

        /** The scheme of the intent's data URI, or `null`. */
        fun dataScheme(intent: I): String?

        /** The `browser_fallback_url` extra of a parsed `intent:` URL, or `null`. */
        fun browserFallbackUrl(intent: I): String?

        /** The target package of a parsed `intent:` URL, or `null`. */
        fun targetPackage(intent: I): String?

        /** An `ACTION_VIEW` intent for `market://details?id=<packageName>`. */
        fun storeIntent(packageName: String): I

        /** `startActivity`. Throws when no app can take the intent or the system refuses it. */
        fun start(intent: I)
    }

    /**
     * Opens [url] in another app. [loadFallback] loads an `intent:` URL's https `browser_fallback_url` in the WebView
     * that asked.
     */
    fun open(url: String, loadFallback: (String) -> Unit): ExternalOutcome {
        if (url.length > MAX_URL_LENGTH) {
            PayriffLog.warning { "Not opening an external URL of ${url.length} characters (limit $MAX_URL_LENGTH)." }
            return ExternalOutcome.FAILED
        }
        val scheme = Urls.parse(url)?.scheme ?: return ExternalOutcome.FAILED
        if (scheme in REFUSED_SCHEMES) {
            PayriffLog.warning { "Not opening ${Urls.redact(url)}: $scheme URLs are never handed to other apps." }
            return ExternalOutcome.FAILED
        }
        return if (scheme == INTENT_SCHEME) openIntentUrl(url, loadFallback) else openView(url)
    }

    private fun openView(url: String): ExternalOutcome {
        val intent = prepare(url) { platform.viewIntent(url) } ?: return ExternalOutcome.FAILED
        return if (start(intent, url)) ExternalOutcome.OPENED_APP else ExternalOutcome.FAILED
    }

    private fun openIntentUrl(url: String, loadFallback: (String) -> Unit): ExternalOutcome {
        val intent = prepare(url) { platform.parseIntentUrl(url) } ?: return ExternalOutcome.FAILED
        val dataScheme = safely { platform.dataScheme(intent)?.lowercase(Locale.ROOT) }
        if (dataScheme in REFUSED_SCHEMES) {
            PayriffLog.warning { "Not opening ${Urls.redact(url)}: it carries $dataScheme data." }
            return ExternalOutcome.FAILED
        }
        if (start(intent, url)) return ExternalOutcome.OPENED_APP
        val fallback = safely { platform.browserFallbackUrl(intent) }
        if (fallback != null && Urls.isAbsoluteHttpsUrl(fallback)) {
            PayriffLog.info { "No app took the intent; loading its browser_fallback_url ${Urls.redact(fallback)}" }
            loadFallback(fallback)
            return ExternalOutcome.LOADED_FALLBACK
        }
        val packageName = safely { platform.targetPackage(intent) }
        if (!packageName.isNullOrEmpty()) {
            val store = safely { platform.storeIntent(packageName) }
            if (store != null && start(store, url)) return ExternalOutcome.OPENED_APP
        }
        return ExternalOutcome.FAILED
    }

    /** Builds and hardens an intent; `null` (logged) when the platform refuses the URL. */
    private fun prepare(url: String, build: () -> I): I? = try {
        build().also(platform::harden)
    } catch (e: java.net.URISyntaxException) {
        PayriffLog.warning { "Malformed external URL ${Urls.redact(url)} (${e.javaClass.simpleName})." }
        null
    } catch (e: RuntimeException) {
        // For example NumberFormatException for a non-numeric launchFlags. Never let it crash the host app.
        PayriffLog.warning { "Malformed external URL ${Urls.redact(url)} (${e.javaClass.simpleName})." }
        null
    }

    private fun start(intent: I, url: String): Boolean = try {
        platform.start(intent)
        true
    } catch (e: RuntimeException) {
        // ActivityNotFoundException, SecurityException, FileUriExposedException, "Failure from system" …
        PayriffLog.debug { "Could not open ${Urls.redact(url)} (${e.javaClass.simpleName})." }
        false
    }

    private fun <T> safely(block: () -> T): T? = try {
        block()
    } catch (e: RuntimeException) {
        null
    }

    companion object {
        /** Longer URLs are refused: they could exceed the system's transaction limit. */
        const val MAX_URL_LENGTH = 64 * 1024

        private const val INTENT_SCHEME = "intent"

        /** Local data must never leave the app through a link on a web page. */
        private val REFUSED_SCHEMES = setOf("file", "content")
    }
}

/** [ExternalLauncher.Platform] on Android intents, started from [activity]. */
internal class AndroidExternalApps(private val activity: Activity) : ExternalLauncher.Platform<Intent> {

    override fun parseIntentUrl(url: String): Intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)

    override fun viewIntent(url: String): Intent = Intent(Intent.ACTION_VIEW, url.toUri())

    override fun harden(intent: Intent) {
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null
        intent.flags = intent.flags and URI_GRANT_FLAGS.inv()
    }

    override fun dataScheme(intent: Intent): String? = intent.data?.scheme

    override fun browserFallbackUrl(intent: Intent): String? = intent.getStringExtra(BROWSER_FALLBACK_URL)

    override fun targetPackage(intent: Intent): String? = intent.`package`

    override fun storeIntent(packageName: String): Intent =
        Intent(Intent.ACTION_VIEW, "market://details?id=${Uri.encode(packageName)}".toUri())

    override fun start(intent: Intent) = activity.startActivity(intent)

    private companion object {
        const val BROWSER_FALLBACK_URL = "browser_fallback_url"
        const val URI_GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
    }
}

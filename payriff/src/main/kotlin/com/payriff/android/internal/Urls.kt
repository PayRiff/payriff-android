package com.payriff.android.internal

import java.util.Locale

/**
 * The parts of a URL the SDK needs, parsed leniently.
 *
 * `java.net.URI` rejects characters that browsers keep in URLs (`|`, `{`, `^` …), and `android.net.Uri` is
 * not available in JVM unit tests, so the SDK uses this small parser. It never builds regular expressions
 * from URLs.
 *
 * @property scheme Lower-cased scheme, for example `https`.
 * @property host Lower-cased host, or `null` for URLs without an authority (`mailto:`, `about:blank`).
 * @property port Explicit port, or `-1` when absent.
 * @property path Raw path, possibly empty. For URLs without an authority this is the opaque part.
 */
internal class ParsedUrl(
    val scheme: String,
    val host: String?,
    val port: Int,
    val path: String,
) {
    /** Port with the scheme default applied: 80 for http, 443 for https, -1 otherwise. */
    val effectivePort: Int
        get() = when {
            port >= 0 -> port
            scheme == "http" -> 80
            scheme == "https" -> 443
            else -> -1
        }

    val isHttp: Boolean get() = scheme == "http" || scheme == "https"
}

internal object Urls {

    private const val MAX_PORT = 65_535
    private const val MAX_LOGGED_LENGTH = 200

    /** What [redact] returns for a URL that [parse] refuses: no valid scheme, an invalid port or IPv6 host. */
    private const val UNPARSEABLE = "<unparseable url>"

    /** Payriff's API hosts (CONTRACT §2): the only hosts whose URL paths are logged (§11). */
    private val LOGGED_PATH_HOSTS = setOf("api.payriff.com", "sb-api.payriff.com")

    /** A logged path segment of this many characters or more is cut to its first and last [SEGMENT_END_LENGTH]. */
    private const val LONG_SEGMENT_LENGTH = 16
    private const val SEGMENT_END_LENGTH = 4

    /** Parses [url], or returns `null` when it has no valid scheme or an invalid port. */
    fun parse(url: String?): ParsedUrl? {
        if (url.isNullOrEmpty()) return null
        val colon = url.indexOf(':')
        if (colon <= 0) return null
        val scheme = url.substring(0, colon)
        if (!scheme[0].isAsciiLetter() || !scheme.all { it.isAsciiLetter() || it.isAsciiDigit() || it == '+' || it == '-' || it == '.' }) {
            return null
        }
        val rest = url.substring(colon + 1)
        val lowerScheme = scheme.lowercase(Locale.ROOT)
        if (!rest.startsWith("//")) {
            return ParsedUrl(lowerScheme, host = null, port = -1, path = cutQueryAndFragment(rest))
        }
        val afterSlashes = rest.substring(2)
        val authorityEnd = afterSlashes.indexOfFirst { it == '/' || it == '?' || it == '#' }
            .let { if (it < 0) afterSlashes.length else it }
        val authority = afterSlashes.substring(0, authorityEnd)
        val path = cutQueryAndFragment(afterSlashes.substring(authorityEnd))
        val hostAndPort = authority.substringAfterLast('@')
        val host: String
        val portText: String?
        if (hostAndPort.startsWith("[")) {
            val close = hostAndPort.indexOf(']')
            if (close < 0) return null
            host = hostAndPort.substring(0, close + 1)
            val after = hostAndPort.substring(close + 1)
            portText = when {
                after.isEmpty() -> null
                after.startsWith(":") -> after.substring(1)
                else -> return null
            }
        } else {
            val portColon = hostAndPort.lastIndexOf(':')
            if (portColon >= 0) {
                host = hostAndPort.substring(0, portColon)
                portText = hostAndPort.substring(portColon + 1)
            } else {
                host = hostAndPort
                portText = null
            }
        }
        val port = when {
            portText.isNullOrEmpty() -> -1
            portText.length <= 5 && portText.all { it.isAsciiDigit() } -> portText.toInt().takeIf { it <= MAX_PORT } ?: return null
            else -> return null
        }
        return ParsedUrl(lowerScheme, host.lowercase(Locale.ROOT), port, path)
    }

    /** True for an absolute https URL with a host. */
    fun isAbsoluteHttpsUrl(url: String?): Boolean = parse(url)?.let { it.scheme == "https" && !it.host.isNullOrEmpty() } == true

    /**
     * [url] as the logs show it (CONTRACT §11). This one function redacts every logged URL: in the general log, in the
     * HTTP details and inside logged bodies.
     *
     * - An http(s) URL never shows its user info (`user:password@`), query or fragment. Its path is kept only on
     *   Payriff's API hosts (`api.payriff.com`, `sb-api.payriff.com`), with every segment of 16 or more characters
     *   shortened to its first 4 and last 4 (`/api/v3/client/orders/7c9e…0ae7/pay`). Any other host, such as a bank's
     *   3-D Secure page, shows as `scheme://host[:port]` only.
     * - Any other scheme shows only as `<scheme>:…`, so `tel:` numbers, `mailto:` addresses and `data:` or
     *   `javascript:` content never reach the logs.
     *
     * The scheme and the host are lower-cased, and a result longer than [MAX_LOGGED_LENGTH] characters is cut there and
     * ends with `…`. [url] may be a view of a char array that is wiped later ([HttpLog]): its query and fragment never
     * become a `String`.
     */
    fun redact(url: CharSequence?): String {
        if (url == null) return "null"
        val end = url.indexOfFirst { it == '?' || it == '#' }.let { if (it < 0) url.length else it }
        val parsed = parse(url.subSequence(0, end).toString()) ?: return UNPARSEABLE
        if (!parsed.isHttp || parsed.host.isNullOrEmpty()) return "${parsed.scheme}:…"
        val shown = StringBuilder(parsed.scheme).append("://").append(parsed.host)
        if (parsed.port >= 0) shown.append(':').append(parsed.port)
        if (parsed.host in LOGGED_PATH_HOSTS) shown.append(shortenLongSegments(parsed.path))
        return if (shown.length > MAX_LOGGED_LENGTH) shown.substring(0, MAX_LOGGED_LENGTH) + "…" else shown.toString()
    }

    /**
     * [path] with every segment of [LONG_SEGMENT_LENGTH] or more characters cut to its first and last
     * [SEGMENT_END_LENGTH], joined by `…` (CONTRACT §11).
     */
    private fun shortenLongSegments(path: String): String = path.split('/').joinToString("/") { segment ->
        // Characters are counted as code points, so a surrogate pair is never split.
        if (segment.codePointCount(0, segment.length) < LONG_SEGMENT_LENGTH) {
            segment
        } else {
            segment.substring(0, segment.offsetByCodePoints(0, SEGMENT_END_LENGTH)) + "…" +
                segment.substring(segment.offsetByCodePoints(segment.length, -SEGMENT_END_LENGTH))
        }
    }

    /**
     * Percent-encodes the UTF-8 bytes of [value] as one URL path segment (CONTRACT §16.3). Only ALPHA, DIGIT, `-`, `_`
     * and `~` stay as they are; everything else, including `.`, is encoded with upper-case hex, so `.` and `..`
     * can never become dot-segments.
     */
    fun encodePathSegment(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val out = StringBuilder(bytes.size)
        for (byte in bytes) {
            val c = byte.toInt() and 0xFF
            val ch = c.toChar()
            if (c < 0x80 && (ch.isAsciiLetter() || ch.isAsciiDigit() || ch == '-' || ch == '_' || ch == '~')) {
                out.append(ch)
            } else {
                out.append('%').append(HEX[c shr 4]).append(HEX[c and 0x0F])
            }
        }
        return out.toString()
    }

    private val HEX = "0123456789ABCDEF".toCharArray()

    private fun cutQueryAndFragment(value: String): String {
        val end = value.indexOfFirst { it == '?' || it == '#' }
        return if (end < 0) value else value.substring(0, end)
    }

    private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'
}

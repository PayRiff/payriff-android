package com.payriff.android.internal

import androidx.annotation.VisibleForTesting
import com.payriff.android.internal.card.CardKeys
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale

/**
 * The HTTP details of CONTRACT §11. Every Client API call of the SDK also logs its request and its response as one
 * pretty-printed JSON object each, with a 2-space indent, at debug level:
 *
 * - `HTTP request` followed by `{"method", "url", "headers", "body"}`;
 * - `HTTP response` followed by `{"status", "method", "url", "headers", "body"}`.
 *
 * Only debuggable host apps log them ([isOn]), and only while logging is on. Headers are sorted by name. The body is
 * the parsed JSON body, with its members in the order received and its numbers as written, or `null` when it is
 * empty. Secrets are redacted with the rules of the other Payriff SDKs ([redactHeaders], [redactBody]). The URL of the
 * request and the URLs in a body are redacted with [Urls.redact], like every URL in the log.
 *
 * String values are decoded into char arrays that are zeroed once the entry is written, so a redacted value, such as
 * the encrypted card of `/pay`, never becomes a `String` (CONTRACT §16.5).
 */
internal object HttpLog {

    /** A body whose redacted JSON is longer than this many characters is described instead of logged. */
    const val MAX_LOGGED_BODY_CHARS = 16_000

    /** Deeper JSON is described as not JSON: the reader is recursive and must never overflow the stack. */
    const val MAX_DEPTH = 512

    /** Headers whose values never reach the logs (compared in lower case). */
    private val SECRET_HEADERS = setOf("authorization", "x-client-secret", "x-secret-key")

    private const val PUBLISHABLE_KEY_HEADER = "x-public-key"

    /** JSON fields whose values never reach the logs, at any depth (compared in lower case). */
    private val SECRET_FIELDS = setOf(
        "appkey", "app_key", "cardholder", "card_holder", "clientsecret", "client_secret", "creq", "cvc", "cvv",
        "encryptedmessage", "expirymonth", "expiryyear", "password", "secretkey", "secret_key", "threedsmethoddata",
        "threedssessiondata",
    )

    /** Card-number fields: only the last four digits are logged (compared in lower case). */
    private val PAN_FIELDS = setOf("pan", "cardnumber", "card_number")

    private const val PAN_DIGITS_SHOWN = 4
    private const val INDENT = "  "
    private const val NULL_TEXT = "null"

    /** `, ` between the items of an array or object written as text, and `: ` after a name. */
    private const val SEPARATOR_LENGTH = 2
    private const val HEX_DIGITS = "0123456789abcdef"
    private const val HEX_RADIX = 16
    private const val DECIMAL_DIGITS = 10

    /** `\uXXXX`: the backslash, `u` and four hex digits. */
    private const val UNICODE_ESCAPE_LENGTH = 6
    private const val FIRST_PRINTABLE = 0x20
    private const val FORM_FEED = '\u000C'
    private const val NUL = '\u0000'

    /**
     * Whether HTTP details are logged: only when the host app is debuggable. This is the app's `FLAG_DEBUGGABLE`
     * ([HostApp]), never the SDK's own build type, and an app whose debuggability is not known yet counts as a release
     * build.
     */
    fun isOn(debuggable: Boolean? = HostApp.debuggable): Boolean = debuggable == true

    /** Logs [request] before it is sent (debuggable apps only, logging on). */
    fun logRequest(request: HttpRequest) {
        if (isOn()) {
            PayriffLog.debug {
                "HTTP request\n" + safely { describeRequest(request.method, request.url, request.headers, request.body) }
            }
        }
    }

    /** Logs [response], the answer to [request] (debuggable apps only, logging on). */
    fun logResponse(request: HttpRequest, response: HttpResponse) {
        if (isOn()) {
            PayriffLog.debug {
                "HTTP response\n" +
                    safely { describeResponse(response.status, request.method, request.url, response.headers, response.body) }
            }
        }
    }

    /** The request as pretty-printed JSON: `method`, `url`, `headers` and `body` ([body]: the bytes sent, UTF-8). */
    fun describeRequest(method: String, url: String, headers: Map<String, String>, body: ByteArray?): String {
        val head = listOf("method" to Json.Str(method), "url" to Json.Str(Urls.redact(url)), "headers" to headersJson(headers))
        if (body == null || body.isEmpty()) return entry(head, null)
        // UTF-8 never decodes to more chars than it has bytes, so this one array holds the whole text and is wiped.
        val chars = CharArray(body.size)
        try {
            val out = CharBuffer.wrap(chars)
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .apply {
                    decode(ByteBuffer.wrap(body), out, true)
                    flush(out)
                }
            return entry(head, CharBuffer.wrap(chars, 0, out.position()))
        } finally {
            chars.fill(NUL)
        }
    }

    /** The response as pretty-printed JSON: `status`, `method`, `url`, `headers` and `body`. */
    fun describeResponse(status: Int, method: String, url: String, headers: Map<String, String>, body: String?): String =
        entry(
            listOf(
                "status" to Json.Literal(status.toString()),
                "method" to Json.Str(method),
                "url" to Json.Str(Urls.redact(url)),
                "headers" to headersJson(headers),
            ),
            body,
        )

    /**
     * [headers] sorted by name (ignoring case). `Authorization`, `X-Client-Secret` and `x-secret-key` show as
     * `***(<length>)`, and `X-Public-Key` as `pr_pk_live_***(<length>)`.
     */
    fun redactHeaders(headers: Map<String, String>): Map<String, String> {
        val shown = LinkedHashMap<String, String>()
        for (name in headers.keys.sortedBy { it.lowercase(Locale.ROOT) }) {
            val value = headers.getValue(name)
            val lower = name.lowercase(Locale.ROOT)
            shown[name] = when {
                lower in SECRET_HEADERS -> secret(value.length)
                lower == PUBLISHABLE_KEY_HEADER && value.startsWith(CardKeys.PUBLISHABLE_PREFIX) ->
                    "${CardKeys.PUBLISHABLE_PREFIX}***(${value.length})"
                lower == PUBLISHABLE_KEY_HEADER -> secret(value.length)
                else -> value
            }
        }
        return shown
    }

    /**
     * The logged form of [body], as compact JSON: secret fields as `***(<length>)` at any depth, the values of
     * `{key, value}` form fields hidden, card numbers as `****<last 4>`, http(s) URLs redacted by [Urls.redact]; a
     * description (a JSON string) when the body is not JSON or its redacted JSON is too long; `null` when it is empty.
     */
    @VisibleForTesting
    fun redactBody(body: String?): String? {
        if (body.isNullOrEmpty()) return null
        val reader = JsonReader(body)
        try {
            val shown = bodyJson(reader)
            return if (shown === Json.Null) null else StringBuilder().also { write(shown, it, pretty = false, level = 0) }.toString()
        } finally {
            reader.wipe()
        }
    }

    /** One log entry: the [head] fields, then `body` parsed from [body] and redacted. Wipes what it decoded. */
    private fun entry(head: List<Pair<String, Json>>, body: CharSequence?): String {
        val reader = if (body.isNullOrEmpty()) null else JsonReader(body)
        try {
            val members = LinkedHashMap<String, Json>()
            for ((name, value) in head) members[name] = value
            members["body"] = if (reader == null) Json.Null else bodyJson(reader)
            return StringBuilder().also { write(Json.Obj(members), it, pretty = true, level = 0) }.toString()
        } finally {
            reader?.wipe()
        }
    }

    private fun headersJson(headers: Map<String, String>): Json =
        Json.Obj(redactHeaders(headers).mapValuesTo(LinkedHashMap()) { (_, value) -> Json.Str(value) })

    /** The redacted body, or its description when it is not JSON or its redacted JSON is too long. */
    private fun bodyJson(reader: JsonReader): Json {
        val json = try {
            reader.document()
        } catch (e: NotJsonException) {
            return Json.Str("(${utf8Length(reader.text)} bytes, not JSON)")
        }
        val redacted = redact(json, null)
        val length = StringBuilder().also { write(redacted, it, pretty = false, level = 0) }.length
        return if (length <= MAX_LOGGED_BODY_CHARS) redacted else Json.Str("($length characters of JSON, too long to log)")
    }

    /**
     * [value] redacted; [key] is the name of the member that holds it (array items inherit the name of their array).
     * The checks run in the order of the other Payriff SDKs.
     */
    private fun redact(value: Json, key: String?): Json {
        when (value) {
            is Json.Obj -> {
                // A form field for the bank (`clientRequest`: creq, session data …): the name is shown, never the value.
                val formField = value.members["key"] is Json.Str && value.members.containsKey("value")
                return Json.Obj(
                    value.members.mapValuesTo(LinkedHashMap()) { (name, member) ->
                        when {
                            !formField || name != "value" -> redact(member, name)
                            member === Json.Null -> member
                            else -> Json.Str(secret(plainLength(member)))
                        }
                    },
                )
            }
            is Json.Arr -> return Json.Arr(value.items.map { redact(it, key) })
            Json.Null -> return value
            is Json.Str, is Json.Literal -> Unit
        }
        val lower = key?.lowercase(Locale.ROOT)
        return when {
            lower != null && lower in SECRET_FIELDS -> Json.Str(secret(plainLength(value)))
            lower != null && lower in PAN_FIELDS -> maskPan(value)
            value is Json.Str && isHttpUrl(value.text) -> Json.Str(Urls.redact(value.text))
            else -> value
        }
    }

    /**
     * A card number as `****<last 4 digits>`. A value that is already masked (contains `*`) stays as it is, and one
     * with 4 digits or fewer shows as `***(<length>)`.
     */
    private fun maskPan(value: Json): Json {
        val text = when (value) {
            is Json.Str -> value.text
            is Json.Literal -> value.text
            else -> return value
        }
        if (text.contains('*')) return if (value is Json.Str) value else Json.Str(text)
        val last = CharArray(PAN_DIGITS_SHOWN)
        var digits = 0
        for (i in text.length - 1 downTo 0) {
            val c = text[i]
            if (c in '0'..'9') {
                if (digits < PAN_DIGITS_SHOWN) last[PAN_DIGITS_SHOWN - 1 - digits] = c
                digits++
            }
        }
        return Json.Str(if (digits <= PAN_DIGITS_SHOWN) secret(text.length) else "****" + String(last))
    }

    /**
     * The length of [value] as text: a string's characters, a number as written, `true`, `false` or `null`; an array
     * as `[a, b]` and an object as `{name: value}`, as the Flutter SDK counts them.
     */
    private fun plainLength(value: Json): Int = when (value) {
        is Json.Str -> value.text.length
        is Json.Literal -> value.text.length
        Json.Null -> NULL_TEXT.length
        is Json.Arr -> 2 + value.items.sumOf(::plainLength) + SEPARATOR_LENGTH * maxOf(0, value.items.size - 1)
        is Json.Obj ->
            2 + value.members.entries.sumOf { (name, member) -> name.length + SEPARATOR_LENGTH + plainLength(member) } +
                SEPARATOR_LENGTH * maxOf(0, value.members.size - 1)
    }

    private fun secret(length: Int): String = "***($length)"

    /** Whether a body string is an http(s) URL, which the log shows redacted ([Urls.redact]). */
    private fun isHttpUrl(text: CharSequence): Boolean =
        text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true)

    /** The UTF-8 length of [text]; a lone surrogate counts as the 3 bytes of U+FFFD, which encoders write for it. */
    private fun utf8Length(text: CharSequence): Int {
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            bytes += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1]) -> {
                    i++
                    4
                }
                else -> 3
            }
            i++
        }
        return bytes
    }

    /** Writes [value] as JSON: compact, or with a 2-space indent per [level]. */
    private fun write(value: Json, out: StringBuilder, pretty: Boolean, level: Int) {
        when (value) {
            is Json.Obj -> {
                if (value.members.isEmpty()) {
                    out.append("{}")
                    return
                }
                out.append('{')
                var first = true
                for ((name, member) in value.members) {
                    if (!first) out.append(',')
                    first = false
                    if (pretty) newline(out, level + 1)
                    quote(name, out)
                    out.append(if (pretty) ": " else ":")
                    write(member, out, pretty, level + 1)
                }
                if (pretty) newline(out, level)
                out.append('}')
            }
            is Json.Arr -> {
                if (value.items.isEmpty()) {
                    out.append("[]")
                    return
                }
                out.append('[')
                var first = true
                for (item in value.items) {
                    if (!first) out.append(',')
                    first = false
                    if (pretty) newline(out, level + 1)
                    write(item, out, pretty, level + 1)
                }
                if (pretty) newline(out, level)
                out.append(']')
            }
            is Json.Str -> quote(value.text, out)
            is Json.Literal -> out.append(value.text)
            Json.Null -> out.append(NULL_TEXT)
        }
    }

    private fun newline(out: StringBuilder, level: Int) {
        out.append('\n')
        repeat(level) { out.append(INDENT) }
    }

    /**
     * [text] as a JSON string, escaped like Dart's `jsonEncode`: `"` and `\` with a backslash, control characters as
     * `\b`, `\t`, `\n`, `\f`, `\r` or `\u00xx`, a lone surrogate as `\uxxxx`; everything else, `/` and non-ASCII
     * included, as it is.
     */
    private fun quote(text: CharSequence, out: StringBuilder) {
        out.append('"')
        for (i in 0 until text.length) {
            val c = text[i]
            when {
                c == '"' || c == '\\' -> out.append('\\').append(c)
                c == '\b' -> out.append("\\b")
                c == '\t' -> out.append("\\t")
                c == '\n' -> out.append("\\n")
                c == FORM_FEED -> out.append("\\f")
                c == '\r' -> out.append("\\r")
                c.code < FIRST_PRINTABLE || (Character.isSurrogate(c) && !isPairedSurrogate(text, i)) -> {
                    out.append("\\u")
                    for (shift in 12 downTo 0 step 4) out.append(HEX_DIGITS[(c.code shr shift) and 0xF])
                }
                else -> out.append(c)
            }
        }
        out.append('"')
    }

    private fun isPairedSurrogate(text: CharSequence, i: Int): Boolean = if (Character.isHighSurrogate(text[i])) {
        i + 1 < text.length && Character.isLowSurrogate(text[i + 1])
    } else {
        i > 0 && Character.isHighSurrogate(text[i - 1])
    }

    /** Logging must never break a payment: an unexpected failure is logged as its class name only. */
    private inline fun safely(describe: () -> String): String = try {
        describe()
    } catch (e: RuntimeException) {
        "(not logged: ${e.javaClass.simpleName})"
    }

    /** A parsed JSON value. */
    private sealed class Json {
        /** An object; its members keep their order. */
        class Obj(val members: Map<String, Json>) : Json()

        class Arr(val items: List<Json>) : Json()

        /** A string. A parsed one is a view of a char array that [JsonReader.wipe] zeroes. */
        class Str(val text: CharSequence) : Json()

        /** A number as written, `true` or `false`. */
        class Literal(val text: String) : Json()

        object Null : Json()
    }

    private class NotJsonException : Exception()

    /**
     * A strict JSON reader (RFC 8259, like `jsonDecode` in the Flutter SDK). It refuses comments, single quotes,
     * unquoted names, trailing commas and leading zeros, which `org.json` accepts. Every string is decoded into a char
     * array of its own, which [wipe] zeroes.
     */
    private class JsonReader(val text: CharSequence) {
        private var pos = 0
        private val decoded = ArrayList<CharArray>()

        /** The whole text as one JSON value. Throws [NotJsonException]. */
        fun document(): Json {
            val value = value(0)
            skipWhitespace()
            if (pos != text.length) throw NotJsonException()
            return value
        }

        fun wipe() {
            for (chars in decoded) chars.fill(NUL)
        }

        private fun value(depth: Int): Json {
            skipWhitespace()
            return when (peek()) {
                '{' -> obj(depth + 1)
                '[' -> arr(depth + 1)
                '"' -> Json.Str(CharBuffer.wrap(string()))
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> {
                    literal(NULL_TEXT)
                    Json.Null
                }
                else -> number()
            }
        }

        private fun obj(depth: Int): Json {
            if (depth > MAX_DEPTH) throw NotJsonException()
            pos++
            val members = LinkedHashMap<String, Json>()
            skipWhitespace()
            if (peek() == '}') {
                pos++
                return Json.Obj(members)
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') throw NotJsonException()
                val name = String(string())
                skipWhitespace()
                if (peek() != ':') throw NotJsonException()
                pos++
                // A repeated name keeps its first position and its last value.
                members[name] = value(depth)
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return Json.Obj(members)
                    }
                    else -> throw NotJsonException()
                }
            }
        }

        private fun arr(depth: Int): Json {
            if (depth > MAX_DEPTH) throw NotJsonException()
            pos++
            val items = ArrayList<Json>()
            skipWhitespace()
            if (peek() == ']') {
                pos++
                return Json.Arr(items)
            }
            while (true) {
                items += value(depth)
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return Json.Arr(items)
                    }
                    else -> throw NotJsonException()
                }
            }
        }

        /** The string that starts at the current quote, decoded. It is validated first, so the array has the exact size. */
        private fun string(): CharArray {
            pos++
            val start = pos
            var length = 0
            while (true) {
                if (pos >= text.length) throw NotJsonException()
                val c = text[pos]
                if (c == '"') break
                if (c.code < FIRST_PRINTABLE) throw NotJsonException()
                if (c == '\\') {
                    when (if (pos + 1 < text.length) text[pos + 1] else NUL) {
                        '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> pos += 2
                        'u' -> {
                            if (pos + UNICODE_ESCAPE_LENGTH > text.length) throw NotJsonException()
                            for (k in 2 until UNICODE_ESCAPE_LENGTH) if (hexValue(text[pos + k]) < 0) throw NotJsonException()
                            pos += UNICODE_ESCAPE_LENGTH
                        }
                        else -> throw NotJsonException()
                    }
                } else {
                    pos++
                }
                length++
            }
            val end = pos
            pos++
            val chars = CharArray(length)
            decoded += chars
            var i = start
            var o = 0
            while (i < end) {
                val c = text[i]
                if (c != '\\') {
                    chars[o++] = c
                    i++
                    continue
                }
                chars[o++] = when (val escaped = text[i + 1]) {
                    'b' -> '\b'
                    'f' -> FORM_FEED
                    'n' -> '\n'
                    'r' -> '\r'
                    't' -> '\t'
                    'u' -> {
                        var code = 0
                        for (k in 2 until UNICODE_ESCAPE_LENGTH) code = code * HEX_RADIX + hexValue(text[i + k])
                        i += UNICODE_ESCAPE_LENGTH - 2
                        code.toChar()
                    }
                    else -> escaped
                }
                i += 2
            }
            return chars
        }

        private fun number(): Json {
            val start = pos
            if (peek() == '-') pos++
            when (peek()) {
                '0' -> pos++
                in '1'..'9' -> skipDigits()
                else -> throw NotJsonException()
            }
            if (peek() == '.') {
                pos++
                if (peek() !in '0'..'9') throw NotJsonException()
                skipDigits()
            }
            if (peek() == 'e' || peek() == 'E') {
                pos++
                if (peek() == '+' || peek() == '-') pos++
                if (peek() !in '0'..'9') throw NotJsonException()
                skipDigits()
            }
            return Json.Literal(text.subSequence(start, pos).toString())
        }

        private fun literal(word: String): Json {
            for (c in word) {
                if (peek() != c) throw NotJsonException()
                pos++
            }
            return Json.Literal(word)
        }

        private fun skipDigits() {
            while (peek() in '0'..'9') pos++
        }

        private fun skipWhitespace() {
            while (pos < text.length) {
                val c = text[pos]
                if (c != ' ' && c != '\t' && c != '\n' && c != '\r') return
                pos++
            }
        }

        /** The character at the current position, or NUL at the end (NUL is never valid outside a string). */
        private fun peek(): Char = if (pos < text.length) text[pos] else NUL

        private fun hexValue(c: Char): Int = when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + DECIMAL_DIGITS
            in 'A'..'F' -> c - 'A' + DECIMAL_DIGITS
            else -> -1
        }
    }
}

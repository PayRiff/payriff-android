package com.payriff.android.internal

import android.util.Log
import com.payriff.android.Payriff
import com.payriff.android.PayriffLogEntry
import com.payriff.android.PayriffLogLevel
import com.payriff.android.PayriffLogger

/**
 * Internal logger (CONTRACT §11). Entries are emitted only while [com.payriff.android.PayriffConfig.enableLogging]
 * is on. Callers must never pass a key, a client secret or card data, and must pass URLs through [Urls.redact].
 * Requests and responses are logged only through [HttpLog], which redacts them.
 */
internal object PayriffLog {

    private const val TAG = "Payriff"

    /** Logcat keeps about 4068 bytes per entry, tag included. */
    private const val LOGCAT_MAX_BYTES = 4000
    private const val MAX_UTF8_BYTES_PER_CHAR = 3

    /** Where formatted lines go. Replaced in JVM unit tests, where `android.util.Log` is unavailable. */
    @Volatile
    var lineSink: (PayriffLogLevel, String) -> Unit = ::logcat

    private val enabled: Boolean
        get() = Payriff.config?.enableLogging == true

    inline fun debug(message: () -> String) {
        if (isEnabled()) emit(PayriffLogLevel.DEBUG, message())
    }

    inline fun info(message: () -> String) {
        if (isEnabled()) emit(PayriffLogLevel.INFO, message())
    }

    inline fun warning(message: () -> String) {
        if (isEnabled()) emit(PayriffLogLevel.WARNING, message())
    }

    inline fun error(message: () -> String) {
        if (isEnabled()) emit(PayriffLogLevel.ERROR, message())
    }

    fun isEnabled(): Boolean = enabled

    fun emit(level: PayriffLogLevel, message: String) {
        val entry = PayriffLogEntry(level, message, System.currentTimeMillis())
        try {
            lineSink(level, entry.formatted)
        } catch (ignored: RuntimeException) {
            // Logging must never break a payment.
        }
        val listener = PayriffLogger.listener ?: return
        try {
            listener.onLog(entry)
        } catch (ignored: RuntimeException) {
            // A failing listener must never break a payment.
        }
    }

    private fun logcat(level: PayriffLogLevel, line: String) {
        val priority = when (level) {
            PayriffLogLevel.DEBUG -> Log.DEBUG
            PayriffLogLevel.INFO -> Log.INFO
            PayriffLogLevel.WARNING -> Log.WARN
            PayriffLogLevel.ERROR -> Log.ERROR
        }
        for (piece in logcatPieces(line)) Log.println(priority, TAG, piece)
    }

    /**
     * [line] in pieces that Logcat keeps whole: Logcat cuts one entry at about 4 KB, and the HTTP details (CONTRACT
     * §11, [HttpLog]) can be longer. A piece holds at most [maxBytes] UTF-8 bytes and ends at a line break when one is
     * in reach (that line break is not repeated); a short line is one piece.
     */
    fun logcatPieces(line: String, maxBytes: Int = LOGCAT_MAX_BYTES): List<String> {
        if (line.length * MAX_UTF8_BYTES_PER_CHAR <= maxBytes) return listOf(line)
        val pieces = ArrayList<String>()
        var start = 0
        while (start < line.length) {
            var end = start
            var bytes = 0
            var lastBreak = -1
            while (end < line.length) {
                val c = line[end]
                val size = when {
                    c.code < 0x80 -> 1
                    c.code < 0x800 || Character.isSurrogate(c) -> 2
                    else -> MAX_UTF8_BYTES_PER_CHAR
                }
                if (bytes + size > maxBytes) break
                bytes += size
                if (c == '\n') lastBreak = end
                end++
            }
            if (end == start) end++ // a character larger than maxBytes: always make progress
            if (end < line.length) {
                when {
                    lastBreak > start -> end = lastBreak
                    // Never split a surrogate pair.
                    end > start + 1 && Character.isHighSurrogate(line[end - 1]) -> end--
                }
            }
            pieces += line.substring(start, end)
            start = if (end < line.length && line[end] == '\n') end + 1 else end
        }
        return pieces
    }
}

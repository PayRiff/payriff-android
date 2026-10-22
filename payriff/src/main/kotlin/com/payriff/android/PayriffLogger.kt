package com.payriff.android

/** Severity of a [PayriffLogEntry]. */
public enum class PayriffLogLevel {
    /** Diagnostics. */
    DEBUG,

    /** Normal flow events. */
    INFO,

    /** Something unexpected that the SDK recovered from, or a configuration problem. */
    WARNING,

    /** A failure. */
    ERROR,
}

/**
 * One SDK log line. Secrets are never logged: keys and client secrets appear as `***(<length>)`, card data never
 * appears, http(s) URLs are logged without their query and fragment, and other URLs only as `<scheme>:…`.
 *
 * @property level Severity.
 * @property message The message, without the `[Payriff] [LEVEL]` prefix.
 * @property timestampMillis Wall-clock time in milliseconds since the Unix epoch.
 */
public class PayriffLogEntry(
    public val level: PayriffLogLevel,
    public val message: String,
    public val timestampMillis: Long,
) {
    /** The line as written to Logcat: `[Payriff] [LEVEL] message`, for example `[Payriff] [INFO] Loading …`. */
    public val formatted: String
        get() = "[Payriff] [${level.name}] $message"

    override fun equals(other: Any?): Boolean =
        other is PayriffLogEntry &&
            level == other.level &&
            message == other.message &&
            timestampMillis == other.timestampMillis

    override fun hashCode(): Int = 31 * (31 * level.hashCode() + message.hashCode()) + timestampMillis.hashCode()

    /** Same as [formatted]. */
    override fun toString(): String = formatted
}

/** Receives SDK log entries. */
public fun interface PayriffLogListener {
    /** Called for every entry while [PayriffConfig.enableLogging] is on. May be called on any thread. */
    public fun onLog(entry: PayriffLogEntry)
}

/**
 * SDK logging. Logging is off by default; turn it on with [PayriffConfig.enableLogging]. Enabled entries
 * go to Logcat (tag `Payriff`) and to [listener].
 */
public object PayriffLogger {
    /** Receives every enabled log entry, for example to show [PayriffLogEntry.formatted] in a debug console. */
    @JvmStatic
    @Volatile
    public var listener: PayriffLogListener? = null
}

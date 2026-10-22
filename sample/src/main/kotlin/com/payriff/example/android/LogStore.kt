package com.payriff.example.android

import android.os.Handler
import android.os.Looper
import com.payriff.android.PayriffLogEntry
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Keeps the latest SDK log lines for the in-app console, each rendered as [PayriffLogEntry.formatted]
 * (`[Payriff] [LEVEL] message`, the same line on every Payriff SDK). Thread-safe; listeners run on the main thread.
 */
object LogStore {

    private const val MAX_LINES = 300

    private val lines = ArrayDeque<String>()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val mainThread = Handler(Looper.getMainLooper())

    fun add(entry: PayriffLogEntry) {
        val line = entry.formatted
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        mainThread.post { listeners.forEach { it() } }
    }

    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }

    fun clear() {
        synchronized(lines) { lines.clear() }
        listeners.forEach { it() }
    }

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }
}

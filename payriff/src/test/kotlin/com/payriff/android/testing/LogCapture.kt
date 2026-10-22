package com.payriff.android.testing

import com.payriff.android.PayriffLogLevel
import com.payriff.android.internal.PayriffLog
import org.junit.rules.ExternalResource
import java.util.concurrent.CopyOnWriteArrayList

/** Replaces Logcat with an in-memory sink, because `android.util.Log` is not available in JVM tests. */
class LogCapture : ExternalResource() {

    val lines: MutableList<String> = CopyOnWriteArrayList()
    private var previous: ((PayriffLogLevel, String) -> Unit)? = null

    override fun before() {
        previous = PayriffLog.lineSink
        PayriffLog.lineSink = { _, line -> lines += line }
    }

    override fun after() {
        previous?.let { PayriffLog.lineSink = it }
    }
}

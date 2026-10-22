package com.payriff.android.testing

import com.payriff.android.Payriff
import com.payriff.android.internal.HostApp
import org.junit.rules.ExternalResource

/**
 * Sets what the SDK knows about the host app ([HostApp]) for one test, because JVM tests have no `Context`, and
 * removes the test transport of [Payriff] afterwards.
 *
 * `debuggable = true` simulates a debug build: the HTTP details of the logs (CONTRACT §11) and the debug-only
 * screen-capture opt-out (§16.0 item 4) apply there.
 */
class HostAppRule(private val debuggable: Boolean? = true) : ExternalResource() {

    override fun before() {
        HostApp.setForTests(debuggable = debuggable, packageName = PACKAGE_NAME)
    }

    override fun after() {
        HostApp.setForTests(debuggable = null, packageName = null)
        Payriff.transportForTests = null
    }

    companion object {
        const val PACKAGE_NAME = "com.payriff.example.android"
    }
}

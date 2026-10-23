package com.payriff.android.card

import com.payriff.android.Payriff
import com.payriff.android.PayriffConfig
import com.payriff.android.PayriffError
import com.payriff.android.PayriffErrorCode
import com.payriff.android.PayriffResult
import com.payriff.android.internal.DeviceIntegrity
import com.payriff.android.internal.DeviceIntegrityGuard
import com.payriff.android.internal.HostApp
import com.payriff.android.internal.IntegrityProbes
import com.payriff.android.internal.IntegritySignal
import com.payriff.android.internal.MapKeyValues
import com.payriff.android.internal.ResultCodec
import com.payriff.android.internal.card.CardApiClient
import com.payriff.android.internal.card.CardConfirmation
import com.payriff.android.internal.card.PaymentViewController
import com.payriff.android.testing.LogCapture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Device integrity (CONTRACT §16.16): the decision logic over injectable probes (each signal on its own, none, the
 * debug-build rule for the debugger, file checks cached while runtime checks stay fresh, and a check that cannot
 * complete failing closed), the block/warn policy with its debug-build-only opt-out, and the payment view's behaviour
 * when the device is blocked. No real device is touched.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceIntegrityTest {

    @get:Rule
    val logs = LogCapture()

    // The warning log is gated on the global configuration; turn logging on so the policy's warnings are captured.
    @Before
    fun setUp() = Payriff.configure(PayriffConfig(enableLogging = true))

    @After
    fun tearDown() = Payriff.reset()

    /**
     * A probe whose every signal is settable, counting the file and runtime scans to show the caching. [suFailure] and
     * [fridaFailure], while set, are thrown by a file probe and a runtime probe: checks that cannot complete.
     */
    private class FakeProbes : IntegrityProbes {
        var su = false
        var magisk = false
        var kernelSu = false
        var apatch = false
        var rootManager = false
        var hooking = false
        var frida = false
        var debugger = false
        var suFailure: Throwable? = null
        var fridaFailure: Throwable? = null
        var suScans = 0
        var fridaScans = 0

        override fun suBinaryPresent(): Boolean {
            suScans++
            suFailure?.let { throw it }
            return su
        }

        override fun magiskPresent(): Boolean = magisk
        override fun kernelSuPresent(): Boolean = kernelSu
        override fun apatchPresent(): Boolean = apatch
        override fun rootManagerAppInstalled(): Boolean = rootManager
        override fun hookingFrameworkPresent(): Boolean = hooking

        override fun fridaPresent(): Boolean {
            fridaScans++
            fridaFailure?.let { throw it }
            return frida
        }

        override fun debuggerAttached(): Boolean = debugger
    }

    @Test
    fun eachSignalOnItsOwnMakesTheDeviceCompromised() {
        val cases = listOf(
            FakeProbes().apply { su = true } to IntegritySignal.SU_BINARY,
            FakeProbes().apply { magisk = true } to IntegritySignal.MAGISK,
            FakeProbes().apply { kernelSu = true } to IntegritySignal.KERNEL_SU,
            FakeProbes().apply { apatch = true } to IntegritySignal.APATCH,
            FakeProbes().apply { rootManager = true } to IntegritySignal.ROOT_MANAGER_APP,
            FakeProbes().apply { hooking = true } to IntegritySignal.XPOSED,
            FakeProbes().apply { frida = true } to IntegritySignal.FRIDA,
            FakeProbes().apply { debugger = true } to IntegritySignal.DEBUGGER,
        )
        for ((probes, signal) in cases) {
            // A release app (not debuggable), so the debugger case counts.
            val integrity = DeviceIntegrity(probes, isDebuggableBuild = { false })
            assertTrue(signal.name, integrity.isCompromised)
            assertEquals(setOf(signal), integrity.matchedSignals())
        }
    }

    @Test
    fun aCleanDeviceIncludingAnEmulatorIsNotCompromised() {
        // Nothing matches: a stock device or emulator. Emulators, test-keys and ro.debuggable are not signals.
        val integrity = DeviceIntegrity(FakeProbes(), isDebuggableBuild = { false })
        assertFalse(integrity.isCompromised)
        assertTrue(integrity.matchedSignals().isEmpty())
    }

    @Test
    fun aDebuggerCountsOnlyInAReleaseApp() {
        val probes = FakeProbes().apply { debugger = true }
        assertFalse("a debugger is expected in a debuggable build", DeviceIntegrity(probes) { true }.isCompromised)
        assertTrue("a debugger in a release app is a signal", DeviceIntegrity(probes) { false }.isCompromised)
    }

    @Test
    fun fileChecksAreCachedWhileRuntimeChecksStayFresh() {
        val probes = FakeProbes().apply { su = true }
        val integrity = DeviceIntegrity(probes, isDebuggableBuild = { false })
        assertEquals(setOf(IntegritySignal.SU_BINARY), integrity.matchedSignals())

        // A hooking tool attaches after the first check: the fresh runtime scan sees it.
        probes.frida = true
        assertEquals(setOf(IntegritySignal.SU_BINARY, IntegritySignal.FRIDA), integrity.matchedSignals())

        // Removing an su binary mid-process does not clear the cached file signal.
        probes.su = false
        assertTrue(IntegritySignal.SU_BINARY in integrity.matchedSignals())
        assertEquals("the file scan ran once", 1, probes.suScans)
        assertEquals("the runtime scan ran every time", 3, probes.fridaScans)
    }

    @Test
    fun aCheckThatCannotCompleteFailsClosed() {
        val failing = listOf(
            FakeProbes().apply { suFailure = IllegalStateException("/data/adb/magisk: EACCES") },
            FakeProbes().apply { fridaFailure = SecurityException("/proc/self/maps") },
            // A missing class or method (an Error, not an Exception) counts too.
            FakeProbes().apply { fridaFailure = NoClassDefFoundError("android/os/Debug") },
            // A signal found before the failure is not reported apart: the check failed as a whole.
            FakeProbes().apply {
                su = true
                fridaFailure = IllegalStateException("hooked")
            },
        )
        for (probes in failing) {
            val integrity = DeviceIntegrity(probes, isDebuggableBuild = { false })
            assertEquals(setOf(IntegritySignal.CHECK_FAILED), integrity.matchedSignals())
            assertTrue(integrity.isCompromised)
        }
        // The check itself: the debug-build lookup throws.
        val lookupFails = DeviceIntegrity(FakeProbes().apply { debugger = true }) { throw IllegalStateException("no app") }
        assertEquals(setOf(IntegritySignal.CHECK_FAILED), lookupFails.matchedSignals())

        // The warning names the exception's type, never its message, which may quote a path.
        assertTrue(logs.lines.any { "could not complete (IllegalStateException)" in it })
        assertTrue(logs.lines.any { "could not complete (NoClassDefFoundError)" in it })
        assertFalse(logs.lines.any { "/data/adb" in it || "/proc/" in it || "android/os" in it })
    }

    @Test
    fun aFileScanThatFailedIsNotCachedAndRunsAgain() {
        val probes = FakeProbes().apply { suFailure = IllegalStateException("I/O error") }
        val integrity = DeviceIntegrity(probes, isDebuggableBuild = { false })
        assertEquals(setOf(IntegritySignal.CHECK_FAILED), integrity.matchedSignals())
        assertEquals(setOf(IntegritySignal.CHECK_FAILED), integrity.matchedSignals())

        // Once the probe works, its answer is used and then cached like any other.
        probes.suFailure = null
        probes.su = true
        assertEquals(setOf(IntegritySignal.SU_BINARY), integrity.matchedSignals())
        assertEquals(setOf(IntegritySignal.SU_BINARY), integrity.matchedSignals())
        assertEquals("two failed scans, then one that is cached", 3, probes.suScans)
    }

    @Test
    fun theGuardFailsClosedAndNeverThrows() {
        assertEquals(
            "the check cannot even be created",
            setOf(IntegritySignal.CHECK_FAILED),
            DeviceIntegrityGuard.signals { throw IllegalStateException("no context") },
        )
        assertEquals(
            setOf(IntegritySignal.CHECK_FAILED),
            DeviceIntegrityGuard.signals { DeviceIntegrity(FakeProbes().apply { fridaFailure = UnsatisfiedLinkError() }) { false } },
        )
        assertEquals(emptySet<IntegritySignal>(), DeviceIntegrityGuard.signals { DeviceIntegrity(FakeProbes()) { false } })
    }

    @Test
    fun aFailedCheckIsRefusedLikeAnySignalUnlessAllowed() {
        val signals = setOf(IntegritySignal.CHECK_FAILED)
        val blocked = DeviceIntegrityGuard.decide(signals, PayriffConfig(enableLogging = true))
        assertEquals(PayriffErrorCode.DEVICE_COMPROMISED, blocked?.code)
        assertEquals("Card payments are not available on this device.", blocked?.message)
        assertTrue(logs.lines.any { "refused" in it && "Signals: check failed." in it })

        logs.lines.clear()
        val allowed = DeviceIntegrityGuard.decide(signals, PayriffConfig(enableLogging = true, allowCompromisedDevices = true), debuggable = true)
        assertNull("in a debuggable app allowCompromisedDevices only warns, as for any signal", allowed)
        assertTrue(logs.lines.any { "proceeding" in it && "Signals: check failed." in it })
    }

    @Test
    fun aCheckThatFailsRightBeforeEncryptionStopsThePayment() = runTest {
        // The launcher's last check (CONTRACT §16.16) with a probe that throws: the card is never encrypted or sent.
        val transport = FakeTransport { testScheduler.currentTime }
        transport.on("GET", "/api/v3/client/orders/o1", http(200, ok(orderPayload())))
        val probes = FakeProbes().apply { fridaFailure = IllegalStateException("hooked") }
        val confirmation = CardConfirmation(
            api = CardApiClient(credentials(), transport),
            encrypt = { throw AssertionError("a failed check must not encrypt the card") },
            recheckDevice = {
                val signals = DeviceIntegrityGuard.signals { DeviceIntegrity(probes) { false } }
                DeviceIntegrityGuard.decide(signals, PayriffConfig())?.let { throw it }
            },
        )
        val card = testCard()
        val error = try {
            confirmation.run("o1", card)
            null
        } catch (e: PayriffError) {
            e
        }
        assertEquals(PayriffErrorCode.DEVICE_COMPROMISED, error?.code)
        assertEquals(listOf("GET /api/v3/client/orders/o1"), transport.requests.map { "${it.method} ${it.path}" })
        assertTrue(card.pan.all { it == '\u0000' } && card.cvv.all { it == '\u0000' })
    }

    @Test
    fun theBlockPolicyRefusesByDefaultAndOnlyWarnsWhenAllowed() {
        val signals = setOf(IntegritySignal.MAGISK, IntegritySignal.FRIDA)

        val blocked = DeviceIntegrityGuard.decide(signals, PayriffConfig(enableLogging = true))
        assertEquals(PayriffErrorCode.DEVICE_COMPROMISED, blocked?.code)
        assertEquals("Card payments are not available on this device.", blocked?.message)
        assertFalse(blocked!!.isRetryable)
        assertFalse(blocked.isFatal)
        assertTrue(logs.lines.any { "refused" in it && "Magisk, Frida" in it })

        logs.lines.clear()
        val allowed = DeviceIntegrityGuard.decide(signals, PayriffConfig(enableLogging = true, allowCompromisedDevices = true), debuggable = true)
        assertNull("in a debuggable app allowCompromisedDevices only warns", allowed)
        assertTrue(logs.lines.any { "proceeding" in it && "Magisk, Frida" in it })

        assertNull("a clean device is never blocked", DeviceIntegrityGuard.decide(emptySet(), PayriffConfig()))
        assertFalse("the warning never names file contents", logs.lines.any { "/data/adb" in it || "/system/" in it })
    }

    /** CONTRACT §16.16: the opt-out works only in debuggable apps, so leaving it on cannot reach production. */
    @Test
    fun theOptOutWorksOnlyInDebuggableApps() {
        val signals = setOf(IntegritySignal.MAGISK)
        val optedIn = PayriffConfig(enableLogging = true, allowCompromisedDevices = true)

        assertNull("a debuggable app may opt in", DeviceIntegrityGuard.decide(signals, optedIn, debuggable = true))
        assertTrue(logs.lines.any { "proceeding" in it && "Magisk" in it })
        assertFalse(logs.lines.any { "ignored" in it })

        // A release build, and an app whose debuggability is not known yet, ignore the flag: refused as if it were off.
        for (debuggable in listOf(false, null)) {
            logs.lines.clear()
            val refused = DeviceIntegrityGuard.decide(signals, optedIn, debuggable = debuggable)
            assertEquals("$debuggable", PayriffErrorCode.DEVICE_COMPROMISED, refused?.code)
            assertEquals(DeviceIntegrityGuard.BLOCK_MESSAGE, refused?.message)
            assertEquals(
                listOf(
                    "[Payriff] [WARNING] Device integrity: allowCompromisedDevices is ignored outside debug builds (the app is not debuggable).",
                    "[Payriff] [WARNING] Device integrity: card payment refused on this device. Signals: Magisk.",
                ),
                logs.lines,
            )
        }

        logs.lines.clear()
        assertNull("a clean device needs no opt-out", DeviceIntegrityGuard.decide(emptySet(), optedIn, debuggable = false))
        val withoutFlag = DeviceIntegrityGuard.decide(signals, PayriffConfig(enableLogging = true), debuggable = true)
        assertEquals("a debuggable app without the flag is refused too", PayriffErrorCode.DEVICE_COMPROMISED, withoutFlag?.code)
        assertFalse("nothing is ignored without the flag", logs.lines.any { "ignored" in it })
    }

    /** By default the policy reads the host app's `FLAG_DEBUGGABLE`, which [HostApp] records from a `Context`. */
    @Test
    fun theOptOutFollowsTheHostAppsDebuggableFlag() {
        val signals = setOf(IntegritySignal.FRIDA)
        val optedIn = PayriffConfig(allowCompromisedDevices = true)
        try {
            HostApp.setForTests(debuggable = true)
            assertNull(DeviceIntegrityGuard.decide(signals, optedIn))
            HostApp.setForTests(debuggable = false)
            assertEquals(PayriffErrorCode.DEVICE_COMPROMISED, DeviceIntegrityGuard.decide(signals, optedIn)?.code)
            HostApp.setForTests(debuggable = null)
            assertEquals("not known yet: a release build", PayriffErrorCode.DEVICE_COMPROMISED, DeviceIntegrityGuard.decide(signals, optedIn)?.code)
        } finally {
            HostApp.setForTests(debuggable = null)
        }
    }

    @Test
    fun aReleaseBuildWithTheOptOutStillStopsThePaymentBeforeEncryption() = runTest {
        // The launcher's last check (CONTRACT §16.16) in a release build that left allowCompromisedDevices on.
        val transport = FakeTransport { testScheduler.currentTime }
        transport.on("GET", "/api/v3/client/orders/o1", http(200, ok(orderPayload())))
        val probes = FakeProbes().apply { frida = true }
        val confirmation = CardConfirmation(
            api = CardApiClient(credentials(), transport),
            encrypt = { throw AssertionError("a release build must not encrypt the card on a compromised device") },
            recheckDevice = {
                val signals = DeviceIntegrityGuard.signals { DeviceIntegrity(probes) { false } }
                DeviceIntegrityGuard.decide(signals, PayriffConfig(allowCompromisedDevices = true), debuggable = false)?.let { throw it }
            },
        )
        val card = testCard()
        val error = try {
            confirmation.run("o1", card)
            null
        } catch (e: PayriffError) {
            e
        }
        assertEquals(PayriffErrorCode.DEVICE_COMPROMISED, error?.code)
        assertEquals("nothing but the order load is sent", listOf("GET /api/v3/client/orders/o1"), transport.requests.map { "${it.method} ${it.path}" })
        assertTrue(card.pan.all { it == '\u0000' } && card.cvv.all { it == '\u0000' })
    }

    @Test
    fun theCompromisedResultCrossesTheIntentAndSerialises() {
        val error = DeviceIntegrityGuard.decide(setOf(IntegritySignal.SU_BINARY), PayriffConfig())!!
        val result = PayriffResult.Failed(error)
        val store = MapKeyValues()
        ResultCodec.write(result, store)
        assertEquals(result, ResultCodec.read(store))
        assertTrue(result.toJson().contains("\"code\":\"DEVICE_COMPROMISED\""))
    }

    @Test
    fun theViewBlocksShowsTheMessageAndReportsOnce() = runTest {
        val delivered = mutableListOf<PayriffResult>()
        val controller = PaymentViewController(
            scope = CoroutineScope(coroutineContext),
            loadOrder = { _, _ -> throw AssertionError("a blocked device must not load the order") },
            onStateChanged = {},
            deliver = { delivered += it },
            deviceBlock = { DeviceIntegrityGuard.decide(setOf(IntegritySignal.FRIDA), PayriffConfig()) },
        )
        controller.setOrder("o1", CLIENT_SECRET)
        controller.appear()
        advanceUntilIdle()

        assertFalse(controller.state.isPayEnabled)
        assertEquals(
            "Card payments aren't available on this device for security reasons",
            controller.state.errorMessage(com.payriff.android.PayriffLanguage.EN),
        )
        val failure = delivered.single() as PayriffResult.Failed
        assertEquals(PayriffErrorCode.DEVICE_COMPROMISED, failure.error.code)

        // Appearing again keeps the block but reports no second result.
        controller.disappear()
        controller.appear()
        advanceUntilIdle()
        assertEquals("reported once", 1, delivered.size)
    }

    @Test
    fun aViewOnACleanDeviceLoadsNormally() = runTest {
        val loaded = mutableListOf<String>()
        val controller = PaymentViewController(
            scope = CoroutineScope(coroutineContext),
            loadOrder = { orderId, _ ->
                loaded += orderId
                throw com.payriff.android.PayriffError(PayriffErrorCode.NETWORK_ERROR, "offline", isRetryable = true)
            },
            onStateChanged = {},
            deliver = {},
            deviceBlock = { null },
        )
        controller.setOrder("o1", CLIENT_SECRET)
        controller.appear()
        advanceUntilIdle()
        assertEquals("a clean device loads the order", listOf("o1"), loaded)
        assertNull(controller.state.deviceBlock)
    }
}

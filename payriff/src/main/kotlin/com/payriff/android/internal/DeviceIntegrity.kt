package com.payriff.android.internal

import android.content.Context
import android.content.pm.PackageManager
import android.os.Debug
import com.payriff.android.PayriffConfig
import com.payriff.android.PayriffError
import com.payriff.android.PayriffErrorCode
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * A signal that the device is rooted, tampered with, or running a hooking framework (CONTRACT §16.16). Any one is
 * enough to refuse a card payment. [label] names the signal in the warning log; it never carries file contents.
 *
 * [CHECK_FAILED] is not something a probe found: the check could not complete, and it fails closed, so breaking the
 * check does not skip it. [PayriffConfig.allowCompromisedDevices] treats it like any other signal.
 */
internal enum class IntegritySignal(val label: String) {
    SU_BINARY("su binary"),
    MAGISK("Magisk"),
    KERNEL_SU("KernelSU"),
    APATCH("APatch"),
    ROOT_MANAGER_APP("root manager app"),
    XPOSED("Xposed"),
    FRIDA("Frida"),
    DEBUGGER("debugger"),
    CHECK_FAILED("check failed"),
}

/**
 * The device-integrity probes (CONTRACT §16.16), one group per signal source. Injected into [DeviceIntegrity] so the
 * decision logic is unit-tested without a device. [AndroidIntegrityProbes] is the real implementation.
 *
 * The first five are file-system or package checks, which [DeviceIntegrity] caches for the process. The last three are
 * runtime checks, which it runs fresh at every point, because a hooking tool can attach after the first check. A probe
 * that throws makes the whole check [IntegritySignal.CHECK_FAILED].
 */
internal interface IntegrityProbes {
    /** An `su` binary in one of the known locations. */
    fun suBinaryPresent(): Boolean

    /** Magisk files. */
    fun magiskPresent(): Boolean

    /** KernelSU files. */
    fun kernelSuPresent(): Boolean

    /** APatch files. */
    fun apatchPresent(): Boolean

    /** An installed root-manager app (needs the `<queries>` of the SDK manifest). */
    fun rootManagerAppInstalled(): Boolean

    /** Xposed or LSPosed: the bridge class is loadable, or a manager app is installed. */
    fun hookingFrameworkPresent(): Boolean

    /** Frida: its agent mapped in, a `gum-js-loop` thread, or a frida-server accepting a local connection. */
    fun fridaPresent(): Boolean

    /** A debugger is attached (`TracerPid` or `Debug.isDebuggerConnected()`); only a signal in a release app. */
    fun debuggerAttached(): Boolean
}

/**
 * Decides whether the device is compromised from the [IntegrityProbes] (CONTRACT §16.16). The file and package checks
 * are cached for the life of this instance; the runtime checks run on every call. A debugger counts only in a release
 * app, because a debuggable (development) build expects one. A check that cannot complete fails closed.
 *
 * Pure Kotlin, so the JVM unit tests drive it with injectable probes: each signal on its own, none, the debug-build
 * rule, that the file checks are cached while the runtime checks stay fresh, and that a probe that throws fails closed.
 */
internal class DeviceIntegrity(
    private val probes: IntegrityProbes,
    private val isDebuggableBuild: () -> Boolean = { HostApp.debuggable == true },
) {
    @Volatile
    private var cachedFileSignals: Set<IntegritySignal>? = null

    /**
     * The signals that match now: the cached file/package signals plus a fresh scan of the runtime signals. When a
     * probe or the check throws, the result is [IntegritySignal.CHECK_FAILED] alone, and a file scan that did not
     * complete is not cached, so the next call runs it again. Never throws.
     */
    fun matchedSignals(): Set<IntegritySignal> = failClosed {
        val fileSignals = cachedFileSignals ?: scanFileSignals().also { cachedFileSignals = it }
        fileSignals + scanRuntimeSignals()
    }

    /** Whether any signal matches, [IntegritySignal.CHECK_FAILED] included. */
    val isCompromised: Boolean
        get() = matchedSignals().isNotEmpty()

    private fun scanFileSignals(): Set<IntegritySignal> {
        val signals = linkedSetOf<IntegritySignal>()
        if (probes.suBinaryPresent()) signals += IntegritySignal.SU_BINARY
        if (probes.magiskPresent()) signals += IntegritySignal.MAGISK
        if (probes.kernelSuPresent()) signals += IntegritySignal.KERNEL_SU
        if (probes.apatchPresent()) signals += IntegritySignal.APATCH
        if (probes.rootManagerAppInstalled()) signals += IntegritySignal.ROOT_MANAGER_APP
        return signals
    }

    private fun scanRuntimeSignals(): Set<IntegritySignal> {
        val signals = linkedSetOf<IntegritySignal>()
        if (probes.hookingFrameworkPresent()) signals += IntegritySignal.XPOSED
        if (probes.fridaPresent()) signals += IntegritySignal.FRIDA
        // A debugger is expected in a debuggable build, so it is a signal only in a release app.
        if (probes.debuggerAttached() && !isDebuggableBuild()) signals += IntegritySignal.DEBUGGER
        return signals
    }
}

/**
 * Runs [check] and fails closed (CONTRACT §16.16): when it throws anything, a missing class or method included, the
 * check could not complete and the device counts as compromised with [IntegritySignal.CHECK_FAILED]. Logs only the
 * exception's type: its message may quote a path. Never throws.
 */
private fun failClosed(check: () -> Set<IntegritySignal>): Set<IntegritySignal> = try {
    check()
} catch (e: Throwable) {
    // The exception ends here, so the caller keeps the interrupt.
    if (e is InterruptedException) Thread.currentThread().interrupt()
    PayriffLog.warning { "Device integrity: the check could not complete (${e.javaClass.simpleName})." }
    setOf(IntegritySignal.CHECK_FAILED)
}

/**
 * The process-wide device-integrity check (CONTRACT §16.16). It holds one [DeviceIntegrity] so the file checks are
 * cached across the three enforcement points (the payment view appears, `confirmCardPayment` starts, and just before
 * the card is encrypted), while the runtime checks still run fresh at each. It fails closed and never throws.
 */
internal object DeviceIntegrityGuard {

    /** The message of the [PayriffError] a blocked device gets (CONTRACT §16.16). */
    const val BLOCK_MESSAGE: String = "Card payments are not available on this device."

    @Volatile
    private var shared: DeviceIntegrity? = null

    private fun of(context: Context): DeviceIntegrity =
        shared ?: synchronized(this) {
            shared ?: DeviceIntegrity(AndroidIntegrityProbes(context.applicationContext ?: context)).also { shared = it }
        }

    /**
     * Whether the device looks compromised, independent of [PayriffConfig.allowCompromisedDevices]. A check that
     * cannot complete counts as compromised.
     */
    fun isCompromised(context: Context): Boolean = signals { of(context) }.isNotEmpty()

    /**
     * The §16.16 policy at an enforcement point: a [PayriffError] when the device is compromised and
     * [PayriffConfig.allowCompromisedDevices] is off, or `null` when the device is clean or the app opted in (then a
     * warning names the matched signals). Neither the warning nor the error carries file contents. A check that cannot
     * complete is the signal [IntegritySignal.CHECK_FAILED], with the same policy.
     */
    fun blockReason(context: Context, config: PayriffConfig?): PayriffError? = decide(signals { of(context) }, config)

    /**
     * The signals that match now on the check that [integrity] returns. Fails closed, also when [integrity] itself
     * throws: the result is then [IntegritySignal.CHECK_FAILED]. Never throws. The JVM tests drive it without a
     * `Context`.
     */
    fun signals(integrity: () -> DeviceIntegrity): Set<IntegritySignal> = failClosed { integrity().matchedSignals() }

    /** The policy over an already-computed signal set. Pure apart from the warning log, so the JVM tests drive it. */
    fun decide(signals: Set<IntegritySignal>, config: PayriffConfig?): PayriffError? {
        if (signals.isEmpty()) return null
        val names = signals.joinToString(", ") { it.label }
        if (config?.allowCompromisedDevices == true) {
            PayriffLog.warning { "Device integrity: proceeding on a compromised device; allowCompromisedDevices is on. Signals: $names." }
            return null
        }
        PayriffLog.warning { "Device integrity: card payment refused on this device. Signals: $names." }
        return PayriffError(PayriffErrorCode.DEVICE_COMPROMISED, BLOCK_MESSAGE)
    }
}

/**
 * The Android device-integrity probes (CONTRACT §16.16). File checks read well-known paths; the root-manager and
 * Xposed package checks use the `<queries>` of the SDK manifest; the Frida socket probe runs off the calling thread.
 * Emulators, `test-keys` builds and `ro.debuggable` are not signals. Each probe answers `false` for what it cannot
 * find, as in the Flutter plugin: a missing or unreadable file, a hidden package or class, a refused connection. An
 * exception that still escapes a probe fails the check closed ([DeviceIntegrity.matchedSignals]). Not unit-tested: it
 * reads the real device.
 */
internal class AndroidIntegrityProbes(private val context: Context) : IntegrityProbes {

    override fun suBinaryPresent(): Boolean = SU_BINARY_PATHS.any(::fileExists)

    override fun magiskPresent(): Boolean = MAGISK_PATHS.any(::fileExists)

    override fun kernelSuPresent(): Boolean = fileExists("/data/adb/ksu")

    override fun apatchPresent(): Boolean = fileExists("/data/adb/ap")

    override fun rootManagerAppInstalled(): Boolean = ROOT_MANAGER_PACKAGES.any(::packageInstalled)

    override fun hookingFrameworkPresent(): Boolean = xposedBridgeLoadable() || XPOSED_PACKAGES.any(::packageInstalled)

    override fun fridaPresent(): Boolean = mapsMentionFrida() || threadNamedGumJsLoop() || fridaServerListening()

    override fun debuggerAttached(): Boolean = tracerPidIsSet() || Debug.isDebuggerConnected()

    private fun fileExists(path: String): Boolean = try {
        File(path).exists()
    } catch (e: SecurityException) {
        false
    }

    private fun packageInstalled(packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    } catch (e: RuntimeException) {
        false
    }

    private fun xposedBridgeLoadable(): Boolean = try {
        Class.forName("de.robv.android.xposed.XposedBridge")
        true
    } catch (e: ClassNotFoundException) {
        false
    } catch (e: Throwable) {
        // A LinkageError or anything else means the class is not usable here.
        false
    }

    private fun mapsMentionFrida(): Boolean = fileLines("/proc/self/maps") { lines ->
        lines.any { "frida-agent" in it || "frida-gadget" in it }
    }

    private fun threadNamedGumJsLoop(): Boolean = try {
        val tasks = File("/proc/self/task").listFiles().orEmpty()
        tasks.any { task ->
            fileLines("${task.path}/comm") { lines -> lines.any { it.trim() == "gum-js-loop" } }
        }
    } catch (e: SecurityException) {
        false
    }

    /** Connects on a worker thread, so the socket operation is never on the main thread (CONTRACT §16.16). */
    private fun fridaServerListening(): Boolean {
        var listening = false
        val probe = Thread({
            listening = try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", FRIDA_PORT), FRIDA_CONNECT_TIMEOUT_MILLIS)
                    true
                }
            } catch (e: IOException) {
                false
            }
        }, "payriff-frida-probe")
        probe.start()
        probe.join(FRIDA_JOIN_TIMEOUT_MILLIS)
        return listening
    }

    private fun tracerPidIsSet(): Boolean = fileLines("/proc/self/status") { lines ->
        val tracer = lines.firstOrNull { it.startsWith("TracerPid:") } ?: return@fileLines false
        tracer.substringAfter(':').trim().toIntOrNull()?.let { it != 0 } == true
    }

    private fun fileLines(path: String, body: (Sequence<String>) -> Boolean): Boolean = try {
        val file = File(path)
        if (!file.canRead()) false else file.bufferedReader().useLines(body)
    } catch (e: IOException) {
        false
    } catch (e: SecurityException) {
        false
    }

    private companion object {
        const val FRIDA_PORT = 27042
        const val FRIDA_CONNECT_TIMEOUT_MILLIS = 100
        const val FRIDA_JOIN_TIMEOUT_MILLIS = 150L

        /** `su` binary locations (CONTRACT §16.16). */
        val SU_BINARY_PATHS = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/system/su",
            "/data/local/su", "/data/local/bin/su", "/data/local/xbin/su", "/system/sd/xbin/su",
            "/system/bin/failsafe/su", "/cache/su", "/dev/su",
        )

        /** Magisk files (CONTRACT §16.16); KernelSU and APatch have their own probes. */
        val MAGISK_PATHS = listOf(
            "/sbin/.magisk", "/data/adb/magisk", "/cache/.disable_magisk", "/dev/.magisk.unblock",
        )

        /** Root-manager packages; declared in the SDK manifest `<queries>` so they can be seen (CONTRACT §16.16). */
        val ROOT_MANAGER_PACKAGES = listOf(
            "com.topjohnwu.magisk", "io.github.huskydg.magisk", "me.weishu.kernelsu", "me.bmax.apatch",
            "eu.chainfire.supersu", "com.noshufou.android.su", "com.koushikdutta.superuser",
            "com.thirdparty.superuser", "com.yellowes.su", "com.kingroot.kinguser", "com.kingo.root",
        )

        /** Xposed / LSPosed manager packages (CONTRACT §16.16). */
        val XPOSED_PACKAGES = listOf("de.robv.android.xposed.installer", "org.lsposed.manager")
    }
}

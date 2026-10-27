package com.payriff.android

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.payriff.android.internal.DeviceIntegrityGuard
import com.payriff.android.internal.HostApp
import com.payriff.android.internal.HttpTransport
import com.payriff.android.internal.PaymentGuard
import com.payriff.android.internal.PayriffLog
import com.payriff.android.internal.SDK_VERSION
import com.payriff.android.internal.UrlConnectionTransport
import com.payriff.android.internal.card.CardApiClient
import com.payriff.android.internal.card.CardKeys
import com.payriff.android.internal.card.CardSetup
import com.payriff.android.internal.card.ClientApiHosts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Entry point of the Payriff SDK, which pays orders with a card typed into the SDK's own form.
 *
 * Your server creates the order with its secret key and gives the app the order's `orderId` and `clientSecret`. The
 * app holds only the publishable key, never the secret key:
 *
 * 1. Call [configure] once in `Application.onCreate()` with your [PayriffConfig.publishableKey], so the configuration
 *    is also available when Android recreates your process during 3-D Secure.
 * 2. Show [PayriffPaymentView], the default payment screen, with the order's `orderId` and `clientSecret`: it shows
 *    the amount, the card form and the Pay button, and pays. To build your own screen instead, show a
 *    [PayriffCardForm] and the order's details from [retrieveOrder], and pay with [PayriffCardPaymentLauncher.confirm].
 * 3. Handle the [PayriffResult]. Only [PayriffResult.Success] means paid; fulfil the order on your server once it has
 *    confirmed the payment.
 */
public object Payriff {

    /** Version of this SDK, for example `1.0.0`. Same as [version]. */
    public const val VERSION: String = SDK_VERSION

    @Volatile
    private var current: PayriffConfig? = null

    /** Replaces the HTTP transport of every API call. Only for JVM tests, which never reach Payriff. */
    @VisibleForTesting
    @Volatile
    internal var transportForTests: HttpTransport? = null

    /** Version of this SDK, for example `1.0.0`. */
    @JvmStatic
    public val version: String
        get() = VERSION

    /**
     * `true` while a card payment of this SDK runs, from [PayriffCardPaymentLauncher.confirm] until its result, the
     * 3-D Secure sheet included. A confirm started meanwhile returns [PayriffResult.Failed] with
     * [PayriffErrorCode.PAYMENT_IN_PROGRESS].
     */
    @JvmStatic
    public val isPaymentInProgress: Boolean
        get() = PaymentGuard.isBusy()

    /** The configuration passed to the last [configure] call, or `null` if [configure] was never called. */
    @JvmStatic
    public val config: PayriffConfig?
        get() = current

    /**
     * Sets the configuration. May be called again; the new configuration applies to later calls only. Call it in
     * `Application.onCreate()`.
     *
     * Never throws. A [PayriffConfig.publishableKey] that is not a publishable key (`pr_pk_live_…`), for example a
     * secret key pasted by mistake, is logged as a warning (`Invalid configuration: …`), and every card call then fails
     * with [PayriffErrorCode.INVALID_CONFIG]. [PayriffRegion.SA] with [PayriffEnvironment.SANDBOX] is logged as a
     * warning too: there is no Saudi sandbox, so its card payments go to production and charge real cards.
     */
    @JvmStatic
    public fun configure(config: PayriffConfig) {
        current = config
        PayriffLog.info { "Configured: $config (SDK $VERSION)" }
        val key = config.publishableKey
        if (key != null && !CardKeys.isValidPublishableKey(key)) {
            PayriffLog.warning { "Invalid configuration: PayriffConfig.publishableKey must be a publishable key (pr_pk_live_…)." }
        }
        ClientApiHosts.warning(config.region, config.environment)?.let { warning -> PayriffLog.warning { warning } }
    }

    /**
     * Loads an order through the Client API (`GET /api/v3/client/orders/{orderId}`, 15 s timeout, HTTP 429 retried
     * after 1 s, 2 s and 4 s), for example to show its amount next to a [PayriffCardForm]. Needs
     * [PayriffConfig.publishableKey] and the order's [clientSecret] (`pr_cs_…`, trimmed).
     *
     * @throws PayriffError [PayriffErrorCode.NOT_CONFIGURED] before [configure], [PayriffErrorCode.INVALID_CONFIG]
     *   without a valid publishable key, [PayriffErrorCode.INVALID_ORDER] for a blank [orderId] or a malformed
     *   [clientSecret], and the Client API errors ([PayriffErrorCode.UNAUTHORIZED], [PayriffErrorCode.SESSION_EXPIRED],
     *   [PayriffErrorCode.NETWORK_ERROR] …).
     */
    @JvmStatic
    @Throws(PayriffError::class)
    public suspend fun retrieveOrder(orderId: String, clientSecret: String): PayriffClientOrder = mapUnexpected {
        val id = CardSetup.orderId(orderId)
        val credentials = CardSetup.credentials(current, clientSecret)
        CardApiClient(credentials, transport()).order(id)
    }

    /**
     * Java-friendly variant of [retrieveOrder]. [callback] runs on the main thread, unless the call is cancelled
     * through the returned handle.
     */
    @JvmStatic
    public fun retrieveOrder(orderId: String, clientSecret: String, callback: PayriffCallback<PayriffClientOrder>): PayriffCancellable =
        launchWithCallback(callback) { retrieveOrder(orderId, clientSecret) }

    /**
     * Whether this device looks rooted or tampered with, or is running a hooking framework such as Frida or Xposed
     * (CONTRACT §16.16). The check is local and best-effort: it stops the common tools but a determined attacker can
     * bypass it. Call it to decide early, for example to offer another payment method; card payments already run the
     * same check and refuse a compromised device with [PayriffErrorCode.DEVICE_COMPROMISED], unless
     * [PayriffConfig.allowCompromisedDevices] is on in a debuggable app.
     *
     * The check fails closed: when it cannot complete, for example because one of its probes throws, the device counts
     * as compromised with the signal `check failed`, and this returns `true`.
     *
     * The result is independent of [PayriffConfig.allowCompromisedDevices]. The first call that completes the file
     * checks caches them for the process; the runtime checks (hooking, Frida, debugger) run on every call.
     */
    @JvmStatic
    public fun isDeviceCompromised(context: Context): Boolean {
        onContextAvailable(context)
        return DeviceIntegrityGuard.isCompromised(context)
    }

    /** Records the host app ([HostApp]). Called by the start-up provider and by every card payment. */
    internal fun onContextAvailable(context: Context) {
        HostApp.update(context)
    }

    /** The HTTP transport of every API call: the test transport in JVM tests, otherwise `HttpURLConnection`. */
    internal fun transport(): HttpTransport = transportForTests ?: UrlConnectionTransport()

    /** Forgets the configuration. Only for tests. */
    @VisibleForTesting
    internal fun reset() {
        current = null
    }

    private inline fun <T> mapUnexpected(block: () -> T): T = try {
        block()
    } catch (error: PayriffError) {
        throw error
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (unexpected: Exception) {
        throw PayriffError(PayriffErrorCode.UNKNOWN, unexpected.message ?: unexpected.javaClass.name, cause = unexpected)
    }

    private fun <T> launchWithCallback(callback: PayriffCallback<T>, block: suspend () -> T): PayriffCancellable {
        val job = CoroutineScope(Dispatchers.Main.immediate).launch {
            val value = try {
                block()
            } catch (error: PayriffError) {
                if (isActive) callback.onError(error)
                return@launch
            }
            if (isActive) callback.onSuccess(value)
        }
        return PayriffCancellable { job.cancel() }
    }
}

/** Java-friendly callback for [Payriff.retrieveOrder]. Exactly one method is called. */
public interface PayriffCallback<T> {
    /** Called on the main thread with the result. */
    public fun onSuccess(value: T)

    /** Called on the main thread when the call fails. */
    public fun onError(error: PayriffError)
}

/** Cancels a pending [Payriff] call; its callback is then never invoked. */
public fun interface PayriffCancellable {
    /** Cancels the call. Safe to call more than once. */
    public fun cancel()
}

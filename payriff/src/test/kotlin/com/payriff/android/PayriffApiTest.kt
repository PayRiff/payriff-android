package com.payriff.android

import com.payriff.android.card.CLIENT_SECRET
import com.payriff.android.card.PUBLISHABLE_KEY
import com.payriff.android.card.ok
import com.payriff.android.card.orderPayload
import com.payriff.android.internal.HttpRequest
import com.payriff.android.internal.HttpResponse
import com.payriff.android.internal.HttpTransport
import com.payriff.android.internal.PaymentGuard
import com.payriff.android.internal.screenProtectionOn
import com.payriff.android.testing.HostAppRule
import com.payriff.android.testing.LogCapture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.math.BigDecimal
import java.util.concurrent.CopyOnWriteArrayList

/** The public entry points, the configuration, the one-payment guard and logging privacy. */
@OptIn(ExperimentalCoroutinesApi::class)
class PayriffApiTest {

    @get:Rule
    val logs = LogCapture()

    @get:Rule
    val hostApp = HostAppRule(debuggable = true)

    private val sent = CopyOnWriteArrayList<HttpRequest>()
    private var now = 0L

    @Before
    fun setUp() {
        Payriff.reset()
        PaymentGuard.clock = { now }
        // Every request stays in memory: no test reaches Payriff.
        Payriff.transportForTests = HttpTransport { request ->
            sent += request
            HttpResponse(200, ok(orderPayload()))
        }
    }

    @After
    fun tearDown() {
        Payriff.reset()
        PayriffLogger.listener = null
    }

    private fun expectError(code: PayriffErrorCode, block: suspend () -> Unit) = runBlocking {
        try {
            block()
            fail("expected $code")
        } catch (error: PayriffError) {
            assertEquals(error.message, code, error.code)
        }
    }

    @Test
    fun callsBeforeConfigureFailNotConfigured() {
        assertNull(Payriff.config)
        expectError(PayriffErrorCode.NOT_CONFIGURED) { Payriff.retrieveOrder("o1", CLIENT_SECRET) }
        assertTrue("nothing was sent", sent.isEmpty())
    }

    @Test
    fun retrieveOrderUsesThePublishableKeyAndTheEnvironmentHost() = runBlocking {
        Payriff.configure(PayriffConfig(publishableKey = PUBLISHABLE_KEY, environment = PayriffEnvironment.SANDBOX))
        val order = Payriff.retrieveOrder(" o1 ", "  $CLIENT_SECRET ")
        assertEquals("o1", order.orderId)
        assertEquals(0, BigDecimal("10.5").compareTo(order.amount))
        assertTrue(order.isPayableByCard)
        val request = sent.single()
        assertEquals("GET", request.method)
        assertEquals("https://sb-api.payriff.com/api/v3/client/orders/o1", request.url)
        assertEquals(PUBLISHABLE_KEY, request.headers["X-Public-Key"])
        assertEquals(CLIENT_SECRET, request.headers["X-Client-Secret"])
        assertEquals(HostAppRule.PACKAGE_NAME, request.headers["X-Client-App-Id"])
        assertFalse("never a secret key", request.headers.containsKey("Authorization"))
    }

    @Test
    fun invalidInputFailsBeforeAnyRequest() {
        Payriff.configure(PayriffConfig(publishableKey = PUBLISHABLE_KEY))
        expectError(PayriffErrorCode.INVALID_ORDER) { Payriff.retrieveOrder(" ", CLIENT_SECRET) }
        expectError(PayriffErrorCode.INVALID_ORDER) { Payriff.retrieveOrder("o1", "cs_not_a_client_secret") }
        Payriff.configure(PayriffConfig())
        expectError(PayriffErrorCode.INVALID_CONFIG) { Payriff.retrieveOrder("o1", CLIENT_SECRET) }
        assertTrue("nothing was sent", sent.isEmpty())
    }

    /** configure never throws; a key that is not a publishable key is a warning, and every card call then fails. */
    @Test
    fun aKeyThatIsNotAPublishableKeyIsAWarning() {
        val entries = mutableListOf<PayriffLogEntry>()
        PayriffLogger.listener = PayriffLogListener { entries += it }
        val secretShaped = "0123456789ABCDEF0123456789ABCDEF"
        val invalid = PayriffConfig(publishableKey = secretShaped, enableLogging = true)
        Payriff.configure(invalid)
        assertEquals(invalid, Payriff.config)
        val warning = entries.single { it.level == PayriffLogLevel.WARNING }
        assertTrue(warning.message, warning.message.startsWith("Invalid configuration: "))
        assertTrue(logs.lines.contains(warning.formatted))
        assertFalse("the key is never logged", logs.lines.any { secretShaped in it })
        expectError(PayriffErrorCode.INVALID_CONFIG) { Payriff.retrieveOrder("o1", CLIENT_SECRET) }

        entries.clear()
        Payriff.configure(PayriffConfig(publishableKey = PUBLISHABLE_KEY, enableLogging = true))
        assertTrue("a valid key is no warning", entries.none { it.level == PayriffLogLevel.WARNING })
    }

    @Test
    fun builderMatchesNamedArguments() {
        val built = PayriffConfig.Builder()
            .publishableKey(" $PUBLISHABLE_KEY ")
            .environment(PayriffEnvironment.SANDBOX)
            .language(PayriffLanguage.RU)
            .enableLogging(true)
            .allowScreenCaptureInDebug(true)
            .allowCompromisedDevices(true)
            .build()
        val named = PayriffConfig(
            publishableKey = PUBLISHABLE_KEY,
            environment = PayriffEnvironment.SANDBOX,
            language = PayriffLanguage.RU,
            enableLogging = true,
            allowScreenCaptureInDebug = true,
            allowCompromisedDevices = true,
        )
        assertEquals(named.toString(), built.toString())
        assertEquals(named.toString(), named.newBuilder().build().toString())
        assertEquals(PayriffConfig().toString(), PayriffConfig.Builder().build().toString())
        assertEquals(
            "PayriffConfig(publishableKey=pr_pk_live_***(${PUBLISHABLE_KEY.length}), environment=SANDBOX, language=RU, " +
                "enableLogging=true, allowScreenCaptureInDebug=true, allowCompromisedDevices=true)",
            named.toString(),
        )
        val defaults = PayriffConfig()
        assertNull(defaults.publishableKey)
        assertEquals(PayriffEnvironment.PRODUCTION, defaults.environment)
        assertNull(defaults.language)
        assertFalse(defaults.enableLogging)
        assertFalse("the card form and the sheet are protected by default", defaults.allowScreenCaptureInDebug)
        assertFalse("compromised devices are blocked by default", defaults.allowCompromisedDevices)
    }

    /** CONTRACT §16.0 item 4: protected by default; only a debuggable app may opt out. */
    @Test
    fun screenProtectionIsOnUnlessADebuggableAppOptsOut() {
        val optOut = PayriffConfig(allowScreenCaptureInDebug = true)
        assertTrue(screenProtectionOn(PayriffConfig(), debuggable = true))
        assertTrue(screenProtectionOn(PayriffConfig(), debuggable = false))
        assertFalse(screenProtectionOn(optOut, debuggable = true))
        assertTrue("ignored in release builds", screenProtectionOn(optOut, debuggable = false))
        assertTrue("unknown counts as release", screenProtectionOn(optOut, debuggable = null))
    }

    @Test
    fun onlyOnePaymentAtATime() {
        assertNull(PaymentGuard.busyError())
        assertTrue(PaymentGuard.reserve("flow-1"))
        assertEquals(PayriffErrorCode.PAYMENT_IN_PROGRESS, PaymentGuard.busyError()?.code)
        assertFalse(PaymentGuard.reserve("flow-2"))
        assertFalse("another sheet cannot start", PaymentGuard.confirm("flow-2"))
        assertTrue(PaymentGuard.confirm("flow-1"))
        now += 60_000
        assertNotNull("a confirmed sheet stays busy", PaymentGuard.busyError())
        PaymentGuard.release("flow-2")
        assertNotNull("only the owner releases", PaymentGuard.busyError())
        PaymentGuard.release("flow-1")
        assertNull(PaymentGuard.busyError())
    }

    @Test
    fun anUnconfirmedReservationExpires() {
        assertTrue(PaymentGuard.reserve("flow-1"))
        now += PaymentGuard.PENDING_TIMEOUT_MILLIS - 1
        assertNotNull(PaymentGuard.busyError())
        now += 1
        assertNull(PaymentGuard.busyError())
        assertTrue("a sheet restored after process death can take over", PaymentGuard.confirm("flow-3"))
        PaymentGuard.release("flow-3")
    }

    @Test
    fun javaCallbacksRunOnTheMainThreadAndCanBeCancelled() {
        val scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        try {
            val errors = mutableListOf<PayriffErrorCode>()
            Payriff.retrieveOrder("o1", CLIENT_SECRET, object : PayriffCallback<PayriffClientOrder> {
                override fun onSuccess(value: PayriffClientOrder) = fail("unexpected success")
                override fun onError(error: PayriffError) {
                    errors += error.code
                }
            })
            scheduler.advanceUntilIdle()
            assertEquals(listOf(PayriffErrorCode.NOT_CONFIGURED), errors)

            Payriff.configure(PayriffConfig(publishableKey = PUBLISHABLE_KEY))
            val orders = mutableListOf<String>()
            Payriff.retrieveOrder("o1", CLIENT_SECRET, object : PayriffCallback<PayriffClientOrder> {
                override fun onSuccess(value: PayriffClientOrder) {
                    orders += value.orderId
                }

                override fun onError(error: PayriffError) = fail("unexpected $error")
            })
            scheduler.advanceUntilIdle()
            assertEquals(listOf("o1"), orders)

            val handle = Payriff.retrieveOrder("o1", CLIENT_SECRET, object : PayriffCallback<PayriffClientOrder> {
                override fun onSuccess(value: PayriffClientOrder) = fail("cancelled")
                override fun onError(error: PayriffError) = fail("cancelled")
            })
            handle.cancel()
            handle.cancel()
            scheduler.advanceUntilIdle()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun loggingIsOffByDefaultAndPrefixedWhenOn() {
        val entries = mutableListOf<PayriffLogEntry>()
        PayriffLogger.listener = PayriffLogListener { entries += it }
        Payriff.configure(PayriffConfig(publishableKey = PUBLISHABLE_KEY))
        assertTrue(logs.lines.isEmpty())
        assertTrue(entries.isEmpty())
        Payriff.configure(PayriffConfig(publishableKey = PUBLISHABLE_KEY, enableLogging = true))
        assertTrue(logs.lines.single().startsWith("[Payriff] [INFO] Configured: "))
        assertEquals(PayriffLogLevel.INFO, entries.single().level)
        assertFalse(entries.single().message.contains(PUBLISHABLE_KEY))
        assertTrue(entries.single().message.contains("pr_pk_live_***(${PUBLISHABLE_KEY.length})"))
    }

    @Test
    fun resultsCompareByValue() {
        val payment = PayriffPayment("o", PayriffPaymentStatus.APPROVED, amount = BigDecimal("1.0"))
        assertEquals(PayriffResult.Success(payment), PayriffResult.Success(PayriffPayment("o", PayriffPaymentStatus.APPROVED, amount = BigDecimal("1"))))
        assertEquals(payment, PayriffResult.Success(payment).payment)
        assertNull(PayriffResult.Failed(PayriffError(PayriffErrorCode.UNKNOWN, "x")).payment)
    }

    /** The helper names shared by every Payriff SDK. */
    @Test
    fun helperNamesMatchTheContract() {
        assertEquals("1.0.0", Payriff.version)
        assertEquals(Payriff.VERSION, Payriff.version)

        assertFalse(Payriff.isPaymentInProgress)
        assertTrue(PaymentGuard.reserve("flow-1"))
        assertTrue(Payriff.isPaymentInProgress)
        PaymentGuard.release("flow-1")
        assertFalse(Payriff.isPaymentInProgress)

        assertEquals("https://api.payriff.com", PayriffEnvironment.PRODUCTION.apiBaseUrl)
        assertEquals("https://sb-api.payriff.com", PayriffEnvironment.SANDBOX.apiBaseUrl)
        assertEquals(PayriffPaymentStatus.APPROVED, PayriffPaymentStatus.fromRaw(" paid "))

        val payment = PayriffPayment("o", PayriffPaymentStatus.APPROVED)
        val error = PayriffError(PayriffErrorCode.NETWORK_ERROR, "offline", isRetryable = true)
        val results = listOf(
            PayriffResult.Success(payment),
            PayriffResult.Declined(payment),
            PayriffResult.Cancelled(null, PayriffCancelReason.CANCELED),
            PayriffResult.Pending(payment, PayriffPendingReason.NOT_FINAL),
            PayriffResult.Failed(error),
        )
        assertEquals(listOf(true, false, false, false, false), results.map { it.isSuccess })
        assertEquals(listOf(payment, payment, null, payment, null), results.map { it.payment })
        assertEquals(listOf(null, null, null, null, error), results.map { it.error })
        assertTrue(results.all { org.json.JSONObject(it.toJson()).has("type") })
    }

    @Test
    fun logEntriesCarryTheFormattedLineAndEpochMillis() {
        val entries = mutableListOf<PayriffLogEntry>()
        PayriffLogger.listener = PayriffLogListener { entries += it }
        val before = System.currentTimeMillis()
        Payriff.configure(PayriffConfig(enableLogging = true))
        val entry = entries.single()
        assertTrue(entry.timestampMillis in before..System.currentTimeMillis())
        assertEquals("[Payriff] [INFO] ${entry.message}", entry.formatted)
        assertEquals(entry.formatted, entry.toString())
        assertEquals(entry.formatted, logs.lines.single())
        assertEquals(
            listOf("[Payriff] [DEBUG] d", "[Payriff] [INFO] i", "[Payriff] [WARNING] w", "[Payriff] [ERROR] e"),
            PayriffLogLevel.entries.zip(listOf("d", "i", "w", "e")).map { (level, message) -> PayriffLogEntry(level, message, 0L).formatted },
        )
    }
}

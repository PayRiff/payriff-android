package com.payriff.android.card

import com.payriff.android.Payriff
import com.payriff.android.PayriffCardField
import com.payriff.android.PayriffConfig
import com.payriff.android.PayriffError
import com.payriff.android.PayriffErrorCode
import com.payriff.android.PayriffFieldError
import com.payriff.android.PayriffLanguage
import com.payriff.android.PayriffResult
import com.payriff.android.internal.MapKeyValues
import com.payriff.android.internal.ResultCodec
import com.payriff.android.internal.card.CardApiClient
import com.payriff.android.internal.card.CardConfirmation
import com.payriff.android.internal.card.CardCrypto
import com.payriff.android.internal.card.CardSheetRequest
import com.payriff.android.internal.card.ThreeDsFlow
import com.payriff.android.testing.HostAppRule
import com.payriff.android.testing.LogCapture
import com.payriff.android.testing.Vectors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.security.SecureRandom
import java.util.Base64

/** CONTRACT §11 / §16.5: card values and Client API secrets never reach logs, errors, `toString` or the Intent result. */
@OptIn(ExperimentalCoroutinesApi::class)
class CardSecurityTest {

    @get:Rule
    val logs = LogCapture()

    @get:Rule
    val hostApp = HostAppRule()

    @After
    fun reset() = Payriff.configure(PayriffConfig())

    @Test
    fun aWholeCardPaymentLogsNoSecret() = runTest {
        Payriff.configure(PayriffConfig(enableLogging = true, publishableKey = PUBLISHABLE_KEY))
        val testKey = CardCrypto.publicKey(Base64.getDecoder().decode(Vectors.load("card_crypto.json").getString("testPublicKeySpki")))
        val transport = FakeTransport { testScheduler.currentTime }
        transport.on("GET", "/api/v3/client/orders/o1", http(200, ok(orderPayload())))
        transport.on(
            "POST",
            "/api/v3/client/orders/o1/pay",
            http(200, ok("""{"nextAction":"THREE_DS","accessUrl":"https://acs.bank.example/c?token=QUERY_SECRET","clientRequest":[{"key":"creq","value":"CREQ_SECRET"}]}""")),
        )
        transport.on("GET", "/api/v3/client/orders/o1/status", http(200, ok(statusPayload("APPROVED"))))
        val api = CardApiClient(credentials(), transport)
        val action = CardConfirmation(api, { CardCrypto.encrypt(it, testKey, SecureRandom()) }).run("o1", testCard(holder = "JOHN TESTER"))
        val ui = FakeThreeDsUi()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val flow = ThreeDsFlow(scope, ui, api, "o1", HOST, { testScheduler.currentTime })
        flow.start(action)
        flow.onNavigation("https://api.payriff.com/api/v3/gateway/kapital/callback/1?cres=CRES_SECRET", isMainFrame = true)
        flow.onNavigation("https://merchant.example/done?x=QUERY_SECRET", isMainFrame = true)
        advanceUntilIdle()
        scope.cancel()
        assertTrue(ui.result is PayriffResult.Success)
        val pay = transport.requests[1]
        val secrets = listOf(
            "4242424242424242", "987", "JOHN TESTER", CLIENT_SECRET, PUBLISHABLE_KEY, "CREQ_SECRET", "CRES_SECRET", "QUERY_SECRET",
            checkNotNull(pay.headers["x-secret-key"]), pay.bodyText!!.substringAfter("\"encryptedMessage\":\"").substringBefore('"'),
        )
        assertTrue(logs.lines.isNotEmpty())
        for (line in logs.lines) for (secret in secrets) assertFalse("log leaks $secret: $line", secret in line)
        assertFalse(ui.result.toString().contains(CLIENT_SECRET))
    }

    @Test
    fun descriptionsNeverContainSecrets() {
        val config = PayriffConfig(publishableKey = "pr_pk_live_ABCDEF")
        assertTrue(config.toString().contains("publishableKey=pr_pk_live_***(17)"))
        assertFalse(config.toString().contains("ABCDEF"))
        assertEquals("pr_pk_live_ABCDEF", config.newBuilder().build().publishableKey)
        assertEquals(null, PayriffConfig(publishableKey = "  ").publishableKey)
        val misplaced = PayriffConfig(publishableKey = "0123456789ABCDEF0123456789ABCDEF")
        assertTrue("a pasted secret key is never shown", misplaced.toString().contains("publishableKey=***(32)"))
        assertFalse(CardSheetRequest("f", "o1", "pr_cs_SECRET", PayriffLanguage.EN).toString().contains("SECRET"))
        val text = credentials().toString()
        assertFalse(text.contains(CLIENT_SECRET) || text.contains(PUBLISHABLE_KEY))
        assertFalse(testCard().toString().contains("4242"))
    }

    @Test
    fun validationErrorsAndFatalFlagsCrossTheIntent() {
        val fields = listOf(
            PayriffFieldError(PayriffCardField.NUMBER, "invalid", "Card number is invalid"),
            PayriffFieldError(PayriffCardField.CVV, "required", "Enter the CVV"),
        )
        val results = listOf(
            PayriffResult.Failed(PayriffError(PayriffErrorCode.VALIDATION_FAILED, "The card form is not filled in correctly.", fields = fields)),
            PayriffResult.Failed(PayriffError(PayriffErrorCode.SESSION_EXPIRED, "Order not found", httpStatus = 404, payriffCode = "01000", isFatal = true)),
        )
        for (result in results) {
            val store = MapKeyValues()
            ResultCodec.write(result, store)
            assertEquals(result, ResultCodec.read(store))
        }
        assertTrue(results[1].error.toString().contains("isFatal=true"))
        assertTrue(results[0].error.toString().contains("fields=[PayriffFieldError(field=NUMBER"))
    }
}

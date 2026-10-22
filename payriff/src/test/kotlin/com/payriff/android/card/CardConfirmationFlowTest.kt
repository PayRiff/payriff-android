package com.payriff.android.card

import com.payriff.android.PayriffError
import com.payriff.android.PayriffErrorCode
import com.payriff.android.internal.card.CardApiClient
import com.payriff.android.internal.card.CardConfirmation
import com.payriff.android.internal.card.CardCrypto
import com.payriff.android.internal.card.NextAction
import com.payriff.android.testing.Vectors
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.SecureRandom

/** Steps 2–3 of CONTRACT §16.7 (order check, encryption, `/pay` with its retries) with a fake transport and virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class CardConfirmationFlowTest {

    private val testKey = CardCrypto.publicKey(java.util.Base64.getDecoder().decode(Vectors.load("card_crypto.json").getString("testPublicKeySpki")))
    private val payPath = "/api/v3/client/orders/o1/pay"
    private val orderPath = "/api/v3/client/orders/o1"

    private fun TestScope.setup(): Pair<FakeTransport, CardConfirmation> {
        val transport = FakeTransport { testScheduler.currentTime }
        transport.on("GET", orderPath, http(200, ok(orderPayload())))
        var keys = 0
        val confirmation = CardConfirmation(
            api = CardApiClient(credentials(), transport),
            encrypt = { CardCrypto.encrypt(it, testKey, SecureRandom()) },
            newIdempotencyKey = { "idem-${++keys}" },
        )
        return transport to confirmation
    }

    private suspend fun expectError(block: suspend () -> Unit): PayriffError {
        try {
            block()
        } catch (e: PayriffError) {
            return e
        }
        fail("expected a PayriffError")
        throw AssertionError()
    }

    @Test
    fun loadsTheOrderThenPaysAndWipesTheCard() = runTest {
        val (transport, confirmation) = setup()
        transport.on("POST", payPath, http(200, ok("""{"nextAction":"REDIRECT","redirectUrl":"https://bank.example/r"}""")))
        val card = testCard()
        val action = confirmation.run("o1", card)
        assertTrue(action is NextAction.Redirect)
        assertEquals(listOf("GET $orderPath", "POST $payPath"), transport.requests.map { "${it.method} ${it.path}" })
        val pay = transport.requests[1]
        assertEquals("idem-1", pay.headers["X-Idempotency-Key"])
        assertTrue(pay.bodyText!!.startsWith("""{"method":"CARD","encryptedMessage":""""))
        assertTrue("4242" !in pay.bodyText!!)
        assertTrue(card.pan.all { it == '\u0000' } && card.cvv.all { it == '\u0000' })
    }

    @Test
    fun anOrderThatIsNotPayableIsNeverPaid() = runTest {
        for (payload in listOf(orderPayload(status = "APPROVED"), orderPayload(methods = "[\"GOOGLE_PAY\"]"))) {
            val (transport, confirmation) = setup()
            transport.on("GET", orderPath, http(200, ok(payload)))
            val card = testCard()
            val error = expectError { confirmation.run("o1", card) }
            assertEquals(PayriffErrorCode.ORDER_NOT_PAYABLE, error.code)
            assertEquals(1, transport.requests.size)
            assertTrue(card.pan.all { it == '\u0000' })
        }
    }

    @Test
    fun rateLimitedPayIsRetriedWithTheSameBytesAndKey() = runTest {
        val (transport, confirmation) = setup()
        val tooMany = http(429, "")
        transport.on("POST", payPath, tooMany, tooMany, tooMany, http(200, ok("""{"nextAction":"CHECK_STATUS"}""")))
        val action = confirmation.run("o1", testCard())
        assertTrue(action === NextAction.CheckStatus)
        val posts = transport.requestsTo(payPath)
        assertEquals(4, posts.size)
        assertEquals(listOf(0L, 1_000L, 3_000L, 7_000L), posts.map { it.time - posts.first().time })
        for (post in posts.drop(1)) {
            assertArrayEquals(posts.first().body, post.body)
            assertEquals(posts.first().headers, post.headers)
        }
        assertEquals("idem-1", posts.last().headers["X-Idempotency-Key"])
    }

    @Test
    fun rateLimitedFourTimesIsFatal() = runTest {
        val (transport, confirmation) = setup()
        transport.on("POST", payPath, http(429, """{"code":"01000","message":"Too many requests"}"""))
        val error = expectError { confirmation.run("o1", testCard()) }
        assertEquals(PayriffErrorCode.RATE_LIMITED, error.code)
        assertTrue(error.isFatal)
        assertEquals(4, transport.requestsTo(payPath).size)
    }

    @Test
    fun aPayWithoutResponseIsResentOnceWithTheSameBytesAndKey() = runTest {
        val (transport, confirmation) = setup()
        transport.on("POST", payPath, FakeTransport.Reply.NoResponse(), http(200, ok("""{"nextAction":"WAIT"}""")))
        assertTrue(confirmation.run("o1", testCard()) === NextAction.Wait)
        val posts = transport.requestsTo(payPath)
        assertEquals(2, posts.size)
        assertEquals(1_000L, posts[1].time - posts[0].time)
        assertArrayEquals(posts[0].body, posts[1].body)
        assertEquals(posts[0].headers, posts[1].headers)
        assertEquals("idem-1", posts[1].headers["X-Idempotency-Key"])
    }

    @Test
    fun aPayThatNeverAnswersTimesOutAfterOneRetry() = runTest {
        val (transport, confirmation) = setup()
        transport.on("POST", payPath, FakeTransport.Reply.Hang)
        val started = testScheduler.currentTime
        val error = expectError { confirmation.run("o1", testCard()) }
        assertEquals(PayriffErrorCode.NETWORK_ERROR, error.code)
        assertEquals(2, transport.requestsTo(payPath).size)
        // 30 s POST timeout, 1 s pause, 30 s again.
        assertEquals(61_000L, testScheduler.currentTime - started)
    }

    @Test
    fun aGetIsNotRetriedOnNetworkErrors() = runTest {
        val (transport, confirmation) = setup()
        transport.on("GET", orderPath, FakeTransport.Reply.Hang)
        val started = testScheduler.currentTime
        val error = expectError { confirmation.run("o1", testCard()) }
        assertEquals(PayriffErrorCode.NETWORK_ERROR, error.code)
        assertEquals(1, transport.requests.size)
        assertEquals(15_000L, testScheduler.currentTime - started)
    }

    @Test
    fun payErrorsMapLikeTheWebSdk() = runTest {
        val cases = listOf(
            http(400, """{"code":"15400","message":"encryptedMessage is required for CARD"}""") to PayriffErrorCode.INVALID_REQUEST,
            http(400, """{"code":"01000","message":"Order is not payable"}""") to PayriffErrorCode.ORDER_NOT_PAYABLE,
            http(404, """{"code":"01000","message":"Order not found"}""") to PayriffErrorCode.SESSION_EXPIRED,
            http(200, """{"code":"16001","message":"Card declined by issuer"}""") to PayriffErrorCode.PAYMENT_FAILED,
        )
        for ((reply, code) in cases) {
            val (transport, confirmation) = setup()
            transport.on("POST", payPath, reply)
            val error = expectError { confirmation.run("o1", testCard()) }
            assertEquals(code, error.code)
            assertEquals(1, transport.requestsTo(payPath).size)
        }
    }

    @Test
    fun anEncryptionFailureNeverCarriesItsMessage() = runTest {
        val transport = FakeTransport { testScheduler.currentTime }
        transport.on("GET", orderPath, http(200, ok(orderPayload())))
        val confirmation = CardConfirmation(
            api = CardApiClient(credentials(), transport),
            encrypt = { throw java.security.GeneralSecurityException("4242424242424242") },
        )
        val card = testCard()
        val error = expectError { confirmation.run("o1", card) }
        assertEquals(PayriffErrorCode.UNKNOWN, error.code)
        assertTrue("4242" !in error.message && error.cause == null)
        assertTrue(card.pan.all { it == '\u0000' })
        assertEquals(1, transport.requests.size)
    }
}

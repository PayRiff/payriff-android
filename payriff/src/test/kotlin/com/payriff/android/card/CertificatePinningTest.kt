package com.payriff.android.card

import com.payriff.android.PayriffError
import com.payriff.android.PayriffErrorCode
import com.payriff.android.internal.CertificatePinning
import com.payriff.android.internal.CertificatePinningException
import com.payriff.android.internal.HttpTransport
import com.payriff.android.internal.card.CardApiClient
import com.payriff.android.internal.card.EncryptedCard
import com.payriff.android.testing.Vectors
import com.payriff.android.testing.Vectors.objects
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Certificate pinning (CONTRACT §16.15, `vectors/tls_pins.json`): the SDK's pins and hosts equal the vector, the pure
 * pin check accepts the chain the server serves and refuses an interception chain, and a pinning failure maps to a
 * non-retryable `NETWORK_ERROR`. The check runs on the parsed certificates alone, never on a live TLS connection.
 */
class CertificatePinningTest {

    private val vector = Vectors.load("tls_pins.json")

    private fun chain(section: String): List<X509Certificate> {
        val factory = CertificateFactory.getInstance("X.509")
        val pems = vector.getJSONObject(section).getJSONArray("pems")
        return (0 until pems.length()).map { index ->
            factory.generateCertificate(ByteArrayInputStream(pems.getString(index).toByteArray(Charsets.US_ASCII))) as X509Certificate
        }
    }

    @Test
    fun theHostsAndPinsEqualTheVector() {
        val hosts = vector.getJSONArray("hosts").let { array -> (0 until array.length()).map { array.getString(it) }.toSet() }
        assertEquals(hosts, CertificatePinning.PINNED_HOSTS)
        val pins = vector.getJSONArray("pins").objects().map { it.getString("spkiSha256") }.toSet()
        assertEquals(pins, CertificatePinning.PINS)
        assertTrue(CertificatePinning.isPinned("api.payriff.com"))
        assertTrue("host is compared case-insensitively", CertificatePinning.isPinned("SB-API.payriff.com"))
        assertFalse(CertificatePinning.isPinned("pay.payriff.com"))
        assertFalse(CertificatePinning.isPinned(null))
    }

    @Test
    fun everyTrustAnchorIsAPinnedKey() {
        val factory = CertificateFactory.getInstance("X.509")
        val anchors: List<JSONObject> = vector.getJSONArray("trustAnchors").objects()
        val anchorPins = anchors.map { anchor ->
            val pem = anchor.getString("pem")
            val certificate = factory.generateCertificate(ByteArrayInputStream(pem.toByteArray(Charsets.US_ASCII))) as X509Certificate
            val spki = CertificatePinning.spkiSha256(certificate)
            assertTrue("anchor ${anchor.getString("name")} is pinned", spki in CertificatePinning.PINS)
            spki
        }.toSet()
        // The pins are the trust anchors' keys plus the current intermediate (DV R36), which has no self-signed anchor.
        assertEquals(setOf("a9khLOZJxlnJyrxstg/P+seiDCm+Yf3OsrXyFocBaI0="), CertificatePinning.PINS - anchorPins)
    }

    @Test
    fun theCurrentChainPassesAndTheInterceptedChainIsRefused() {
        val current = chain("currentChain")
        assertTrue("the served chain carries a pinned CA key", CertificatePinning.chainIsPinned(current))
        // It is the intermediate/root that is pinned, not the leaf, so a yearly leaf renewal keeps working.
        assertFalse("the leaf is not pinned", CertificatePinning.spkiSha256(current.first()) in CertificatePinning.PINS)
        assertTrue("the intermediate is pinned", CertificatePinning.spkiSha256(current[1]) in CertificatePinning.PINS)

        val intercepted = chain("interceptedChain")
        assertFalse("an interception CA has no pinned key", CertificatePinning.chainIsPinned(intercepted))
    }

    @Test
    fun aPinningFailureIsANonRetryableNetworkError() = runBlocking {
        var attempts = 0
        val transport = HttpTransport {
            attempts++
            throw CertificatePinningException()
        }
        val client = CardApiClient(credentials(), transport)
        val order = runCatching { client.order("o1") }.exceptionOrNull() as PayriffError
        assertEquals(PayriffErrorCode.NETWORK_ERROR, order.code)
        assertFalse("a pinning failure is not retryable", order.isRetryable)
        assertEquals("Payriff's server certificate could not be verified.", order.message)

        // Not even /pay is retried once: nothing is ever sent again.
        attempts = 0
        val pay = runCatching { client.pay("o1", EncryptedCard("ct".toByteArray(), "wrapped"), "idem-1") }.exceptionOrNull() as PayriffError
        assertEquals(PayriffErrorCode.NETWORK_ERROR, pay.code)
        assertEquals(1, attempts)
    }

    @Test
    fun theTransportHelperRecognisesAWrappedPinningFailure() {
        val wrapped: Throwable = javax.net.ssl.SSLHandshakeException("handshake").initCause(
            com.payriff.android.internal.PinningCertificateException("no pinned key"),
        )
        assertTrue(CertificatePinning.isPinningFailure(wrapped))
        assertFalse(CertificatePinning.isPinningFailure(java.io.IOException("offline")))
    }
}

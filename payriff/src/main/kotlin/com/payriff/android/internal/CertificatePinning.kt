package com.payriff.android.internal

import android.annotation.SuppressLint
import android.net.http.X509TrustManagerExtensions
import com.payriff.android.internal.card.Base64Codec
import java.io.IOException
import java.net.HttpURLConnection
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * TLS certificate pinning for the SDK's own HTTP client (CONTRACT §16.15, `vectors/tls_pins.json`). Every Client API
 * call (order, pay, three-ds, status) to `api.payriff.com` or `sb-api.payriff.com` is accepted only when the
 * platform's normal TLS validation (chain, expiry, host name) succeeds **and** at least one certificate of the
 * validated chain has its SubjectPublicKeyInfo SHA-256 (base64) in [PINS].
 *
 * The pins are CA keys, not the leaf (the current Sectigo intermediate and roots plus backup roots of major CAs), so a
 * yearly leaf renewal never breaks apps. There is no switch to turn pinning off. The 3-D Secure WebViews load the
 * banks' pages and are not pinned. Test-only transport hooks ([com.payriff.android.Payriff.transportForTests]) replace
 * the whole transport, so they are unaffected.
 *
 * This object is pure: it holds the pins and the SHA-256 check over parsed certificates, so the JVM unit tests exercise
 * it without a TLS connection. The Android glue ([CertificatePinner]) lives in a separate class.
 */
internal object CertificatePinning {

    /** The hosts whose connections are pinned (CONTRACT §16.2 host table). Compared lower-case. */
    val PINNED_HOSTS: Set<String> = setOf("api.payriff.com", "sb-api.payriff.com")

    /**
     * The pinned SubjectPublicKeyInfo SHA-256 digests, base64. Equal to `tls_pins.json` → `pins[].spkiSha256`: the
     * current Sectigo intermediate (DV R36) and roots (R46, USERTrust RSA), and backup roots of major CAs. A test
     * asserts this set equals the vector.
     */
    val PINS: Set<String> = setOf(
        "a9khLOZJxlnJyrxstg/P+seiDCm+Yf3OsrXyFocBaI0=", // Sectigo Public Server Authentication CA DV R36 (intermediate)
        "Douxi77vs4G+Ib/BogbTFymEYq0QSFXwSgVCaZcI09Q=", // Sectigo Public Server Authentication Root R46
        "x4QzPSC810K5/cMjb05Qm4k3Bw5zBn4lTdO/nEW/Td4=", // USERTrust RSA Certification Authority
        "ICGRfpgmOUXIWcQ/HXPLQTkFPEFPoDyjvH7ohhQpjzs=", // USERTrust ECC Certification Authority
        "sLVjNUaFYfW7n6EtgBeEpjOlcnBdNPMrZDRF36iwBdE=", // Sectigo Public Server Authentication Root E46
        "C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M=", // ISRG Root X1
        "diGVwiVYbubAI3RW4hB9xU8e/CH2GnkuvVFZE8zmgzI=", // ISRG Root X2
        "++MBgDH5WGvL9Bcn5Be30cRcL0f5O+NyoXuWtQdX1aI=", // Amazon Root CA 1
        "NqvDJlas/GRcYbcWE8S/IceH9cq77kg0jVhZeAPXq8k=", // Amazon Root CA 3
        "i7WTqTvh0OioIruIfFR4kMPnBqrS2rdiVPl/s2uC/CY=", // DigiCert Global Root G2
        "r/mIkG3eEpVdm+u/ko/cwxzOMo1bk4TyHIlByibiA5E=", // DigiCert Global Root CA
        "hxqRlPTu1bMS/0DITB1SSu0vd4u/8l8TjPgfaAp63Gc=", // GTS Root R1
        "cGuxAXyFXFkWm61cF4HPWX8S0srS9j0aSqN0k4AP+4A=", // GlobalSign Root CA - R3
        "rn+WLLnmp9v3uDP7GPqbcaiRdd+UnCMrap73yz3yu/w=", // GlobalSign Root R46
    )

    /** The message of the [com.payriff.android.PayriffError] a pinning failure becomes (CONTRACT §16.15). */
    const val FAILURE_MESSAGE: String = "Payriff's server certificate could not be verified."

    /** Whether connections to [host] are pinned. */
    fun isPinned(host: String?): Boolean = host != null && host.lowercase(Locale.ROOT) in PINNED_HOSTS

    /** The base64 SHA-256 of [certificate]'s SubjectPublicKeyInfo DER (`publicKey.encoded`). */
    fun spkiSha256(certificate: X509Certificate): String =
        Base64Codec.encode(MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded))

    /**
     * Whether [chain] carries a pinned public key: at least one certificate's [spkiSha256] is in [PINS]. A pure check
     * on the parsed certificates; it never looks at validity dates, so it does not depend on the leaf's expiry.
     */
    fun chainIsPinned(chain: List<X509Certificate>): Boolean = chain.any { spkiSha256(it) in PINS }

    /** Whether [error] or one of its causes is a pinning rejection ([PinningCertificateException]). */
    fun isPinningFailure(error: Throwable): Boolean {
        var current: Throwable? = error
        val seen = HashSet<Throwable>()
        while (current != null && seen.add(current)) {
            if (current is PinningCertificateException) return true
            current = current.cause
        }
        return false
    }
}

/** Thrown by the pinning trust manager when the validated chain carries no pinned key. A [CertificateException] so the
 * TLS stack reports it as a handshake failure. */
internal class PinningCertificateException(message: String) : CertificateException(message)

/** A pinning failure surfaced out of the transport, so the Client API can map it to a non-retryable `NETWORK_ERROR`
 * without sending anything (CONTRACT §16.15). */
internal class CertificatePinningException : IOException(CertificatePinning.FAILURE_MESSAGE)

/**
 * The Android glue for [CertificatePinning]: it pins an [HttpsURLConnection] to a pinned host by giving it an
 * `SSLSocketFactory` whose [X509TrustManager] keeps the platform's validation (through [X509TrustManagerExtensions],
 * which cleans the chain and checks it for the host) and then requires a pinned key. Host name verification stays the
 * default. Kept apart from the pure [CertificatePinning] so the JVM tests never load the TLS classes.
 */
internal object CertificatePinner {

    private val factories = ConcurrentHashMap<String, SSLSocketFactory>()

    /** Pins [connection] when it goes to a pinned host; leaves any other connection untouched. */
    fun applyTo(connection: HttpURLConnection) {
        if (connection !is HttpsURLConnection) return
        val host = connection.url?.host ?: return
        if (!CertificatePinning.isPinned(host)) return
        connection.sslSocketFactory = socketFactory(host.lowercase(Locale.ROOT))
    }

    private fun socketFactory(host: String): SSLSocketFactory = factories.getOrPut(host) {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(PinningTrustManager(host, systemTrustManager())), null)
        context.socketFactory
    }

    private fun systemTrustManager(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /**
     * Validates with the platform's default trust manager (chain, expiry and host name through
     * [X509TrustManagerExtensions]) and then requires a pinned key in the validated chain. It never weakens validation:
     * it adds a check, so the `CustomX509TrustManager` lint (which guards against managers that trust everything) does
     * not apply.
     */
    @SuppressLint("CustomX509TrustManager")
    private class PinningTrustManager(
        private val host: String,
        private val delegate: X509TrustManager,
    ) : X509TrustManager {
        private val extensions = X509TrustManagerExtensions(delegate)

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            val validated = extensions.checkServerTrusted(chain, authType, host)
            if (!CertificatePinning.chainIsPinned(validated)) {
                throw PinningCertificateException(CertificatePinning.FAILURE_MESSAGE)
            }
        }

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            delegate.checkClientTrusted(chain, authType)

        override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
    }
}

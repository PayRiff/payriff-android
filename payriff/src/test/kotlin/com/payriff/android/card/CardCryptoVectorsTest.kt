package com.payriff.android.card

import com.payriff.android.internal.card.Base64Codec
import com.payriff.android.internal.card.CardCrypto
import com.payriff.android.internal.card.CardJson
import com.payriff.android.internal.card.CardPlaintext
import com.payriff.android.testing.Vectors
import com.payriff.android.testing.Vectors.objects
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.interfaces.RSAPublicKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * `card_crypto.json` (CONTRACT §16.6): the byte-exact card JSON, AES-256-GCM with the vectors' key and IV, RSA-OAEP
 * with SHA-256 and MGF1-SHA-256 (checked by decrypting with the TEST private key through the JCA), and the pinned key.
 */
class CardCryptoVectorsTest {

    private val vectors = Vectors.load("card_crypto.json")
    private val testPublicKey = CardCrypto.publicKey(b64(vectors.getString("testPublicKeySpki")))
    private val testPrivateKey: PrivateKey =
        KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(b64(vectors.getString("testPrivateKeyPkcs8"))))
    private val aesKey = b64(vectors.getString("aesKey"))
    private val iv = b64(vectors.getString("iv"))
    private val keyIv = b64(vectors.getString("keyIv"))

    private fun b64(text: String): ByteArray = Base64.getDecoder().decode(text)

    private fun oaepDecrypt(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        cipher.init(Cipher.DECRYPT_MODE, testPrivateKey, CardCrypto.OAEP_SHA256)
        return cipher.doFinal(data)
    }

    private fun cardOf(json: org.json.JSONObject) = CardPlaintext(
        json.getString("pan").toCharArray(),
        json.getString("cardHolder").toCharArray(),
        json.getString("expiryYear").toCharArray(),
        json.getString("expiryMonth").toCharArray(),
        json.getString("cvv").toCharArray(),
    )

    @Test
    fun cardJsonAndAesGcmMatchEveryVector() {
        val cases = vectors.getJSONArray("aesGcm").objects()
        assertEquals(4, cases.size)
        for (case in cases) {
            val card = cardOf(case.getJSONObject("card"))
            assertArrayEquals(case.getString("cardJson"), case.getString("cardJson").toByteArray(Charsets.UTF_8), CardJson.encode(card))
            val encrypted = CardCrypto.encryptWith(card, testPublicKey, aesKey.copyOf(), iv.copyOf())
            assertEquals(case.getString("encryptedMessage"), encrypted.encryptedMessage.toString(Charsets.US_ASCII))
            // The wrapped key is randomized (OAEP); decrypting it must give aesKey ‖ iv.
            assertArrayEquals(keyIv, oaepDecrypt(Base64.getDecoder().decode(encrypted.wrappedKey)))
        }
    }

    @Test
    fun theVectorsWrappedKeyDecryptsWithTheTestKey() {
        assertArrayEquals(keyIv, oaepDecrypt(b64(vectors.getString("rsaWrappedKeyIv"))))
        assertArrayEquals(keyIv, aesKey + iv)
    }

    @Test
    fun theDefaultMgf1WouldNotDecrypt() {
        // Without the explicit OAEPParameterSpec the JCA uses MGF1-SHA-1, which cannot open Payriff's SHA-256 wrapping.
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        cipher.init(Cipher.DECRYPT_MODE, testPrivateKey)
        var failed = false
        try {
            cipher.doFinal(b64(vectors.getString("rsaWrappedKeyIv")))
        } catch (e: BadPaddingException) {
            failed = true
        }
        assertTrue(failed)
        assertEquals("SHA-256", CardCrypto.OAEP_SHA256.digestAlgorithm)
        assertEquals("MGF1", CardCrypto.OAEP_SHA256.mgfAlgorithm)
        assertEquals("SHA-256", (CardCrypto.OAEP_SHA256.mgfParameters as MGF1ParameterSpec).digestAlgorithm)
    }

    @Test
    fun freshRandomEncryptionRoundTrips() {
        val card = cardOf(vectors.getJSONArray("aesGcm").getJSONObject(1).getJSONObject("card"))
        val first = CardCrypto.encrypt(card, testPublicKey, SecureRandom())
        val second = CardCrypto.encrypt(card, testPublicKey, SecureRandom())
        assertNotEquals(first.encryptedMessage.toString(Charsets.US_ASCII), second.encryptedMessage.toString(Charsets.US_ASCII))
        for (encrypted in listOf(first, second)) {
            val unwrapped = oaepDecrypt(Base64.getDecoder().decode(encrypted.wrappedKey))
            assertEquals(CardCrypto.AES_KEY_BYTES + CardCrypto.IV_BYTES, unwrapped.size)
            val aes = Cipher.getInstance("AES/GCM/NoPadding")
            aes.init(Cipher.DECRYPT_MODE, SecretKeySpec(unwrapped, 0, 32, "AES"), GCMParameterSpec(128, unwrapped, 32, 12))
            val plaintext = aes.doFinal(Base64.getDecoder().decode(encrypted.encryptedMessage.toString(Charsets.US_ASCII)))
            assertEquals(vectors.getJSONArray("aesGcm").getJSONObject(1).getString("cardJson"), plaintext.toString(Charsets.UTF_8))
        }
    }

    @Test
    fun thePinnedKeyIsPayriffsKey() {
        assertEquals(vectors.getString("payriffPublicKeySpki"), CardCrypto.PAYRIFF_PUBLIC_KEY_SPKI)
        val der = Base64Codec.decode(CardCrypto.PAYRIFF_PUBLIC_KEY_SPKI)
        val sha256 = MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) }
        assertEquals(vectors.getString("payriffPublicKeySha256"), sha256)
        assertEquals(2048, (CardCrypto.payriffPublicKey() as RSAPublicKey).modulus.bitLength())
    }

    @Test
    fun jsonEscapesLikeJsonStringify() {
        fun json(holder: String) = CardJson.encode(CardPlaintext("4".toCharArray(), holder.toCharArray(), "2030".toCharArray(), "01".toCharArray(), "000".toCharArray()))
            .toString(Charsets.UTF_8).substringAfter("\"cardHolder\":").substringBefore(",\"expiryYear\"")
        assertEquals("\"a/b\"", json("a/b"))
        assertEquals("\"q\\\"b\\\\s\"", json("q\"b\\s"))
        assertEquals("\"\\u0001\\n\\t\\r\\b\\f\"", json("\u0001\n\t\r\b\u000C"))
        assertEquals("\"\\ud800x\"", json("\uD800x"))
        assertEquals("\"Ə😀\"", json("Ə😀"))
        assertEquals(listOf(0xF0, 0x9F, 0x98, 0x80), "😀".toByteArray(Charsets.UTF_8).map { it.toInt() and 0xFF })
    }

    @Test
    fun wipeOverwritesThePlaintext() {
        val card = cardOf(vectors.getJSONArray("aesGcm").getJSONObject(0).getJSONObject("card"))
        card.wipe()
        for (array in listOf(card.pan, card.cardHolder, card.expiryYear, card.expiryMonth, card.cvv)) {
            assertTrue(array.all { it == '\u0000' })
        }
        assertFalse(card.toString().contains("4111"))
    }

    @Test
    fun base64MatchesTheJdk() {
        val random = SecureRandom()
        for (size in 0..64) {
            val data = ByteArray(size).also(random::nextBytes)
            val expected = Base64.getEncoder().encodeToString(data)
            assertEquals(expected, Base64Codec.encode(data))
            assertArrayEquals(data, Base64Codec.decode(expected))
        }
    }
}

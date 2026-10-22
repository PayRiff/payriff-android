package com.payriff.android.internal.card

import java.security.KeyFactory
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/**
 * The card as typed, held only for the moment it takes to encrypt it. [wipe] overwrites every array; it is called
 * right after encryption and on every failure path. Never logged: [toString] prints nothing of it.
 */
internal class CardPlaintext(
    val pan: CharArray,
    val cardHolder: CharArray,
    val expiryYear: CharArray,
    val expiryMonth: CharArray,
    val cvv: CharArray,
) {
    fun wipe() {
        pan.fill('\u0000')
        cardHolder.fill('\u0000')
        expiryYear.fill('\u0000')
        expiryMonth.fill('\u0000')
        cvv.fill('\u0000')
    }

    override fun toString(): String = "CardPlaintext(***)"
}

/**
 * The encrypted card of one pay attempt (CONTRACT §16.6): [encryptedMessage] as Base64 ASCII bytes (the request body
 * embeds them without making a String) and [wrappedKey], the RSA-wrapped `aesKey ‖ iv` sent as the `x-secret-key`
 * header. Never logged.
 */
internal class EncryptedCard(val encryptedMessage: ByteArray, val wrappedKey: String) {
    fun wipe() = encryptedMessage.fill(0)

    override fun toString(): String = "EncryptedCard(***)"
}

/**
 * Card encryption of CONTRACT §16.6 (`card_crypto.json`), the same as the web SDK's `fields/crypto.js`:
 * - `encryptedMessage` = Base64(AES-256-GCM(aesKey, iv, UTF-8 cardJson)): ciphertext ‖ 16-byte tag, no IV prefix;
 * - `x-secret-key` = Base64(RSA-OAEP(SHA-256, MGF1-SHA-256, empty label)(Payriff key, aesKey ‖ iv)).
 *
 * A fresh 32-byte key and 12-byte IV come from [SecureRandom] for every attempt. The key, the IV, `aesKey ‖ iv` and
 * the plaintext bytes are zeroed afterwards.
 */
internal object CardCrypto {

    /** Payriff's RSA public key (SPKI, Base64), pinned: `card_crypto.json` → `payriffPublicKeySpki`. */
    const val PAYRIFF_PUBLIC_KEY_SPKI: String =
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAxRq5a+44T6Dac60XmVRQ/7cpPyFsBnamXbJlRVJk8CnES5Re5tVMohyD0hZr3zcQ" +
            "j+bxodYB4zZpQTPlrXvFBC3zz+rXnGlevxBQ6W2d3QC9q8vWH8p3ZOwTO3qVvDSHH9o+hMMRNbJ7kueq/KZlX/F+bjZ23CZw7iXEGQT3HYVY" +
            "nnHsvpaguYDteWBag2sPPLLsVjeB3zhTfQ7OsWp5XTkDuRwLugHPvs6RHLcwGCnodukWyvwUaEUQR/kMGC+RbMsAIVkcLMP5csfR3Xo7Gi98" +
            "+i44iLN00f7gE8QvEmvv8xDspyTAjDEL1a5gK7TijJ3yLG/Bwa1rr1uskYy7lwIDAQAB"

    const val AES_KEY_BYTES = 32
    const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val RSA_TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"

    /**
     * OAEP with SHA-256 for both the digest and MGF1, and an empty label. Passed explicitly: the provider default of
     * `OAEPWithSHA-256AndMGF1Padding` uses MGF1 with SHA-1 on Android, which Payriff cannot decrypt.
     */
    val OAEP_SHA256: OAEPParameterSpec =
        OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)

    /** Payriff's pinned public key. */
    fun payriffPublicKey(): PublicKey = publicKey(Base64Codec.decode(PAYRIFF_PUBLIC_KEY_SPKI))

    fun publicKey(spkiDer: ByteArray): PublicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(spkiDer))

    /** Encrypts [card] with a fresh key and IV. Throws `GeneralSecurityException` when the platform cannot. */
    fun encrypt(card: CardPlaintext, publicKey: PublicKey, random: SecureRandom = SecureRandom()): EncryptedCard {
        val aesKey = ByteArray(AES_KEY_BYTES)
        val iv = ByteArray(IV_BYTES)
        try {
            random.nextBytes(aesKey)
            random.nextBytes(iv)
            return encryptWith(card, publicKey, aesKey, iv)
        } finally {
            aesKey.fill(0)
            iv.fill(0)
        }
    }

    /** [encrypt] with a given key and IV. Only tests call it directly, with the vectors' fixed values. */
    fun encryptWith(card: CardPlaintext, publicKey: PublicKey, aesKey: ByteArray, iv: ByteArray): EncryptedCard {
        require(aesKey.size == AES_KEY_BYTES && iv.size == IV_BYTES)
        val plaintext = CardJson.encode(card)
        val keyAndIv = ByteArray(AES_KEY_BYTES + IV_BYTES)
        var sealed: ByteArray? = null
        try {
            sealed = aesGcm(aesKey, iv, plaintext)
            System.arraycopy(aesKey, 0, keyAndIv, 0, AES_KEY_BYTES)
            System.arraycopy(iv, 0, keyAndIv, AES_KEY_BYTES, IV_BYTES)
            val wrapped = rsaOaep(publicKey, keyAndIv)
            return EncryptedCard(Base64Codec.encodeToBytes(sealed), Base64Codec.encode(wrapped))
        } finally {
            plaintext.fill(0)
            keyAndIv.fill(0)
            sealed?.fill(0)
        }
    }

    private fun aesGcm(key: ByteArray, iv: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        // The JCA (like Web Crypto) returns ciphertext ‖ tag.
        return cipher.doFinal(plaintext)
    }

    private fun rsaOaep(publicKey: PublicKey, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(RSA_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, publicKey, OAEP_SHA256)
        return cipher.doFinal(data)
    }
}

/**
 * The card JSON of CONTRACT §16.6 step 2, byte for byte as JavaScript `JSON.stringify({pan, cardHolder, expiryYear,
 * expiryMonth, cvv})`: that key order, no spaces, UTF-8 with non-ASCII kept as is, `"` and `\` escaped, `/` not
 * escaped. Control characters and lone surrogates (which the form never lets through) are escaped like
 * `JSON.stringify` does, so the output is exact for any input. Written straight into one exactly sized array, so no
 * intermediate String or growing buffer holds the card.
 */
internal object CardJson {

    fun encode(card: CardPlaintext): ByteArray {
        val counter = Sink(null)
        write(counter, card)
        val out = ByteArray(counter.size)
        write(Sink(out), card)
        return out
    }

    private class Sink(val out: ByteArray?) {
        var size = 0

        fun byte(value: Int) {
            out?.set(size, value.toByte())
            size++
        }

        fun ascii(text: String) {
            for (c in text) byte(c.code)
        }
    }

    private fun write(sink: Sink, card: CardPlaintext) {
        sink.ascii("{\"pan\":")
        string(sink, card.pan)
        sink.ascii(",\"cardHolder\":")
        string(sink, card.cardHolder)
        sink.ascii(",\"expiryYear\":")
        string(sink, card.expiryYear)
        sink.ascii(",\"expiryMonth\":")
        string(sink, card.expiryMonth)
        sink.ascii(",\"cvv\":")
        string(sink, card.cvv)
        sink.byte('}'.code)
    }

    private fun string(sink: Sink, value: CharArray) {
        sink.byte('"'.code)
        var i = 0
        while (i < value.size) {
            val c = value[i]
            when {
                c == '"' -> sink.ascii("\\\"")
                c == '\\' -> sink.ascii("\\\\")
                c == '\b' -> sink.ascii("\\b")
                c == '\u000C' -> sink.ascii("\\f")
                c == '\n' -> sink.ascii("\\n")
                c == '\r' -> sink.ascii("\\r")
                c == '\t' -> sink.ascii("\\t")
                c.code < 0x20 -> unicodeEscape(sink, c)
                c.code < 0x80 -> sink.byte(c.code)
                c.code < 0x800 -> {
                    sink.byte(0xC0 or (c.code shr 6))
                    sink.byte(0x80 or (c.code and 0x3F))
                }
                Character.isHighSurrogate(c) && i + 1 < value.size && Character.isLowSurrogate(value[i + 1]) -> {
                    val cp = Character.toCodePoint(c, value[i + 1])
                    sink.byte(0xF0 or (cp shr 18))
                    sink.byte(0x80 or ((cp shr 12) and 0x3F))
                    sink.byte(0x80 or ((cp shr 6) and 0x3F))
                    sink.byte(0x80 or (cp and 0x3F))
                    i++
                }
                Character.isSurrogate(c) -> unicodeEscape(sink, c)
                else -> {
                    sink.byte(0xE0 or (c.code shr 12))
                    sink.byte(0x80 or ((c.code shr 6) and 0x3F))
                    sink.byte(0x80 or (c.code and 0x3F))
                }
            }
            i++
        }
        sink.byte('"'.code)
    }

    /** `\uXXXX` with lower-case hex, as `JSON.stringify` writes it. */
    private fun unicodeEscape(sink: Sink, c: Char) {
        sink.ascii("\\u")
        for (shift in intArrayOf(12, 8, 4, 0)) sink.byte(HEX[(c.code shr shift) and 0xF].code)
    }

    private const val HEX = "0123456789abcdef"
}

/**
 * Standard Base64 with padding and no line breaks (RFC 4648 §4). `java.util.Base64` needs API 26 and
 * `android.util.Base64` does not exist in JVM unit tests, so the SDK carries this small codec.
 */
internal object Base64Codec {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(data: ByteArray): String {
        val bytes = encodeToBytes(data)
        return String(CharArray(bytes.size) { bytes[it].toInt().toChar() })
    }

    fun encodeToBytes(data: ByteArray): ByteArray {
        val out = ByteArray((data.size + 2) / 3 * 4)
        var o = 0
        var i = 0
        while (i + 2 < data.size) {
            val n = (data[i].toInt() and 0xFF shl 16) or (data[i + 1].toInt() and 0xFF shl 8) or (data[i + 2].toInt() and 0xFF)
            out[o++] = ALPHABET[n shr 18 and 0x3F].code.toByte()
            out[o++] = ALPHABET[n shr 12 and 0x3F].code.toByte()
            out[o++] = ALPHABET[n shr 6 and 0x3F].code.toByte()
            out[o++] = ALPHABET[n and 0x3F].code.toByte()
            i += 3
        }
        val rest = data.size - i
        if (rest > 0) {
            val n = (data[i].toInt() and 0xFF shl 16) or (if (rest == 2) data[i + 1].toInt() and 0xFF shl 8 else 0)
            out[o++] = ALPHABET[n shr 18 and 0x3F].code.toByte()
            out[o++] = ALPHABET[n shr 12 and 0x3F].code.toByte()
            out[o++] = if (rest == 2) ALPHABET[n shr 6 and 0x3F].code.toByte() else '='.code.toByte()
            out[o] = '='.code.toByte()
        }
        return out
    }

    /** Decodes standard Base64; white space is ignored. Throws [IllegalArgumentException] on anything else. */
    fun decode(text: String): ByteArray {
        val clean = text.filterNot { it.isWhitespace() }.trimEnd('=')
        val out = ByteArray(clean.length * 3 / 4)
        var buffer = 0
        var bits = 0
        var o = 0
        for (c in clean) {
            val v = ALPHABET.indexOf(c)
            require(v >= 0) { "Invalid Base64" }
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[o++] = (buffer shr bits and 0xFF).toByte()
            }
        }
        return if (o == out.size) out else out.copyOf(o)
    }
}

package com.payriff.android.vectors

import com.payriff.android.testing.Vectors
import com.payriff.android.testing.Vectors.keyNames
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Guards the "every case of every vector file" rule: a vector file or a top-level section that no test asserts
 * fails here, so a new spec revision cannot be synced in silently.
 *
 * The card vectors (`card_*.json`, CONTRACT §16) are asserted by the `card` test package. Revision 5 archived the
 * hosted-flow vectors (`api.json`, `navigation.json`, `payment_url_order_id.json`, `amount.json`); they are no longer
 * synced, and this test fails if one comes back.
 */
class VectorCoverageTest {

    @Test
    fun everyVectorFileAndSectionHasATest() {
        val url = checkNotNull(Vectors::class.java.getResource("/vectors")) { "vectors are missing from the test resources" }
        val directory = File(url.toURI())
        val files = checkNotNull(directory.listFiles { file -> file.name.endsWith(".json") }).map { it.name }.toSet()
        assertEquals("a file is both covered and pending", emptySet<String>(), COVERED.keys intersect PENDING_CARD_FLOW.keys)
        assertEquals("vector files", COVERED.keys + PENDING_CARD_FLOW.keys, files)
        for ((file, sections) in COVERED) {
            assertEquals("top-level keys of $file", sections.keys, Vectors.load(file).keyNames())
        }
        for ((file, sections) in PENDING_CARD_FLOW) {
            assertEquals("top-level keys of $file", sections, Vectors.load(file).keyNames())
        }
        println("vectors: ${COVERED.size} files asserted; ${PENDING_CARD_FLOW.size} pending")
    }

    private companion object {
        /** Every top-level key of every vector file, with the test that asserts it. */
        val COVERED: Map<String, Map<String, String>> = mapOf(
            "language.json" to cases("SmallVectorsTest.languageVectors"),
            "result_json.json" to cases("ResultJsonVectorsTest.everyResultSerialisesToTheSharedShape"),
            "status.json" to cases("StatusVectorsTest.everyStatusCaseMatches"),
            "strings.json" to mapOf("description" to "-", "strings" to "SmallVectorsTest.stringTableMatchesStringsJson"),
            "card_3ds_navigation.json" to cases("CardNavigationVectorsTest.everyThreeDsNavigationStep"),
            "card_api.json" to mapOf(
                "description" to "-",
                "order" to "CardApiVectorsTest.order",
                "errors" to "CardApiVectorsTest.errors",
                "status" to "CardApiVectorsTest.status",
                "requests" to "CardApiVectorsTest.requests / headersOverARealConnection",
                "keys" to "CardApiVectorsTest.keys",
                "clientSecrets" to "CardApiVectorsTest.clientSecrets",
            ),
            "card_crypto.json" to mapOf(
                "description" to "-",
                "testPublicKeySpki" to "CardCryptoVectorsTest (encryption key)",
                "testPrivateKeyPkcs8" to "CardCryptoVectorsTest (JCA decryption)",
                "aesKey" to "CardCryptoVectorsTest.cardJsonAndAesGcmMatchEveryVector",
                "iv" to "CardCryptoVectorsTest.cardJsonAndAesGcmMatchEveryVector",
                "keyIv" to "CardCryptoVectorsTest.theVectorsWrappedKeyDecryptsWithTheTestKey",
                "rsaWrappedKeyIv" to "CardCryptoVectorsTest.theVectorsWrappedKeyDecryptsWithTheTestKey",
                "aesGcm" to "CardCryptoVectorsTest.cardJsonAndAesGcmMatchEveryVector",
                "payriffPublicKeySpki" to "CardCryptoVectorsTest.thePinnedKeyIsPayriffsKey",
                "payriffPublicKeySha256" to "CardCryptoVectorsTest.thePinnedKeyIsPayriffsKey",
            ),
            "card_format.json" to mapOf(
                "description" to "-",
                "number" to "CardValidationVectorsTest.formatVectors",
                "expiry" to "CardValidationVectorsTest.formatVectors",
                "cvv" to "CardValidationVectorsTest.formatVectors",
                "holder" to "CardValidationVectorsTest.formatVectors",
            ),
            "card_next_action.json" to cases("CardNavigationVectorsTest.everyNextActionCase"),
            "card_strings.json" to mapOf("description" to "-", "messages" to "CardValidationVectorsTest.stringTableEqualsCardStringsJson"),
            "tls_pins.json" to mapOf(
                "description" to "-",
                "hosts" to "CertificatePinningTest.theHostsAndPinsEqualTheVector",
                "pins" to "CertificatePinningTest.theHostsAndPinsEqualTheVector",
                "trustAnchors" to "CertificatePinningTest.everyTrustAnchorIsAPinnedKey",
                "currentChain" to "CertificatePinningTest.theCurrentChainPassesAndTheInterceptedChainIsRefused",
                "interceptedChain" to "CertificatePinningTest.theCurrentChainPassesAndTheInterceptedChainIsRefused",
            ),
            "card_validation.json" to mapOf(
                "description" to "-",
                "brands" to "CardValidationVectorsTest.brandsAndLimits",
                "maxPanDigits" to "CardValidationVectorsTest.brandsAndLimits",
                "maxHolderLength" to "CardValidationVectorsTest.brandsAndLimits",
                "number" to "CardValidationVectorsTest.number",
                "detectBrand" to "CardValidationVectorsTest.detectBrand",
                "expiry" to "CardValidationVectorsTest.expiry",
                "cvv" to "CardValidationVectorsTest.cvvAndHolder",
                "holder" to "CardValidationVectorsTest.cvvAndHolder",
                "visibleError" to "CardValidationVectorsTest.visibleError",
                "luhn" to "CardValidationVectorsTest.luhn",
            ),
        )

        /** Card-flow vectors that are not asserted yet. Empty since the native card flow exists (CONTRACT §16). */
        val PENDING_CARD_FLOW: Map<String, Set<String>> = emptyMap()

        private fun cases(test: String) = mapOf("description" to "-", "cases" to test)
    }
}

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
 * The card vectors (`card_*.json`, CONTRACT §16, §19) and `corner_smoothing.json` (§18.2) are asserted by the `card`
 * test package, `strings_override.json` (§18.6) by this package, and the Android sections of `wallet.json`
 * (CONTRACT §17, §20) by the `wallet` test package. Revision 5 archived the
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
            "corner_smoothing.json" to mapOf(
                "description" to "-",
                "algorithm" to "CornerSmoothingVectorsTest.everyCaseHasTheVectorsCornerAndTopRightPoints (the formulas)",
                "cases" to "CornerSmoothingVectorsTest.everyCaseHasTheVectorsCornerAndTopRightPoints",
            ),
            "strings_override.json" to mapOf(
                "description" to "-",
                "fields" to "StringsOverrideVectorsTest.everyFieldReplacesItsKey",
                "resolution" to "StringsOverrideVectorsTest.resolutionOrder",
                "payButton" to "StringsOverrideVectorsTest.payButtonText",
                "poweredBy" to "StringsOverrideVectorsTest.poweredBySplit",
            ),
            "card_api.json" to mapOf(
                "description" to "-",
                "order" to "CardApiVectorsTest.order",
                "errors" to "CardApiVectorsTest.errors",
                "status" to "CardApiVectorsTest.status",
                "requests" to "CardApiVectorsTest.requests / headersOverARealConnection",
                "keys" to "CardApiVectorsTest.keys",
                "clientSecrets" to "CardApiVectorsTest.clientSecrets",
                "hosts" to "CardApiVectorsTest.hosts / PayriffApiTest.configureLogsTheWarningOfEachHostCase",
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
            "card_next_action.json" to mapOf(
                "description" to "-",
                "cases" to "CardNavigationVectorsTest.everyNextActionCase",
                "methodSteps" to "ThreeDsMethodStepTest.methodStepsStartTheVectorsWebViews",
                "threeDsTiming" to "ThreeDsMethodStepTest.timingsAreTheVectors",
                "afterMethod" to "ThreeDsMethodStepTest.afterTheMethodEveryResultGoesWhereTheVectorSays",
            ),
            "card_strings.json" to mapOf("description" to "-", "messages" to "CardValidationVectorsTest.stringTableEqualsCardStringsJson"),
            "tls_pins.json" to mapOf(
                "description" to "-",
                "hosts" to "CertificatePinningTest.theHostsAndPinsEqualTheVector",
                "pins" to "CertificatePinningTest.theHostsAndPinsEqualTheVector",
                "trustAnchors" to "CertificatePinningTest.everyTrustAnchorIsAPinnedKey",
                "currentChain" to "CertificatePinningTest.theCurrentChainPassesAndTheInterceptedChainIsRefused",
                "gccpayChain" to "CertificatePinningTest.theGccpayChainPasses",
                "interceptedChain" to "CertificatePinningTest.theCurrentChainPassesAndTheInterceptedChainIsRefused",
            ),
            "wallet.json" to mapOf(
                "description" to "-",
                "googlePayMerchantIds" to "GooglePayVectorsTest.googlePayMerchantIdsAreTrimmedAndBlankIsNull",
                "availability" to "GooglePayVectorsTest.availabilityGooglePayColumn (the Google Pay column)",
                "amounts" to "GooglePayVectorsTest.amountsHaveTwoDecimalsFromDecimalArithmetic",
                "googlePayEnvironments" to "GooglePayVectorsTest.googlePayEnvironmentFollowsTheApiHost",
                "googlePayRequests" to "GooglePayVectorsTest.googlePayRequestsAreTheVectorsJson",
                "payRequests" to "GooglePayVectorsTest.googlePayPayRequest (the Google Pay case)",
                "buttonStyles" to "GooglePayVectorsTest.googlePayButtonStyles (shared sizes and googlePay)",
                // Native Android has no Apple Pay (CONTRACT §17.2): these are asserted by the iOS SDK.
                "applePayMerchantIdentifiers" to "- (iOS)",
                "applePayRequests" to "- (iOS)",
                "applePaySheet" to "- (iOS)",
                // CONTRACT §19.4: Apple Pay's networks; Google Pay keeps VISA and MASTERCARD (googlePayRequests).
                "applePayNetworks" to "- (iOS)",
                // Revision 10.
                "platformGuard" to "GooglePayVectorsTest.platformGuardAndroidRows (the android rows)",
                "googlePayGateway" to "GooglePayVectorsTest.googlePayGatewayComesFromTheOrderThenTheConfig",
                // Revision 11 (CONTRACT §20).
                "viewPlacement" to "WalletViewVectorsTest.viewPlacementPartsAndGaps",
                "viewWalletHeight" to "WalletViewVectorsTest.viewWalletHeightFollowsPayUnlessTheStyleSetsOne",
                "viewReveal" to "WalletViewVectorsTest.viewRevealAppliesTheOrderAndTheDecisionTogether",
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
                "madaBins" to "MadaTest.theBinsArePayriffJsList",
            ),
        )

        /** Card-flow vectors that are not asserted yet. Empty since the native card flow exists (CONTRACT §16). */
        val PENDING_CARD_FLOW: Map<String, Set<String>> = emptyMap()

        private fun cases(test: String) = mapOf("description" to "-", "cases" to test)
    }
}

package com.payriff.android.wallet

import com.google.android.gms.wallet.WalletConstants
import com.google.android.gms.wallet.button.ButtonConstants
import com.payriff.android.Payriff
import com.payriff.android.PayriffClientOrder
import com.payriff.android.PayriffConfig
import com.payriff.android.PayriffEnvironment
import com.payriff.android.PayriffGooglePayButtonStyle
import com.payriff.android.PayriffGooglePayButtonTheme
import com.payriff.android.PayriffGooglePayButtonType
import com.payriff.android.PayriffGooglePayConfig
import com.payriff.android.PayriffRegion
import com.payriff.android.card.APP_ID
import com.payriff.android.card.CLIENT_SECRET
import com.payriff.android.card.FakeTransport
import com.payriff.android.card.PUBLISHABLE_KEY
import com.payriff.android.card.credentials
import com.payriff.android.card.http
import com.payriff.android.card.ok
import com.payriff.android.internal.HttpLog
import com.payriff.android.internal.card.CardApiClient
import com.payriff.android.internal.card.NextAction
import com.payriff.android.internal.wallet.GooglePayAvailability
import com.payriff.android.internal.wallet.GooglePayButtonStyles
import com.payriff.android.internal.wallet.GooglePayEnvironment
import com.payriff.android.internal.wallet.GooglePayRequests
import com.payriff.android.testing.Vectors
import com.payriff.android.testing.Vectors.assertJsonEquals
import com.payriff.android.testing.Vectors.keyNames
import com.payriff.android.testing.Vectors.objects
import com.payriff.android.testing.Vectors.stringOrNull
import com.payriff.android.testing.Vectors.toStringMap
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.util.Locale

/**
 * `wallet.json` (CONTRACT §17), every section that applies to Android: the Google Pay merchant id, the availability
 * (Google Pay column), amounts, environments, the Google Pay requests (compared as JSON values), the Google Pay `/pay`
 * request and the Google Pay button styles. The Apple Pay sections belong to iOS (CONTRACT §17.2: native Android has no
 * Apple Pay).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GooglePayVectorsTest {

    private val vectors = Vectors.load("wallet.json")

    @Before
    fun setUp() {
        Payriff.reset()
    }

    @After
    fun tearDown() {
        Payriff.reset()
    }

    /** A GET order payload of the vectors, as the Client API client reads it. */
    private fun order(json: JSONObject): PayriffClientOrder = CardApiClient.parseOrder(json, "o1")

    @Test
    fun googlePayMerchantIdsAreTrimmedAndBlankIsNull() {
        val cases = vectors.getJSONArray("googlePayMerchantIds").objects()
        assertEquals(3, cases.size)
        for (case in cases) {
            val config = PayriffGooglePayConfig(merchantId = case.stringOrNull("value"))
            assertEquals(case.toString(), case.stringOrNull("normalized"), config.merchantId)
        }
        // The merchant name follows the same rule.
        assertEquals("Shop", PayriffGooglePayConfig(merchantName = "  Shop \n").merchantName)
        assertNull(PayriffGooglePayConfig(merchantName = "   ").merchantName)
        assertEquals(PayriffGooglePayConfig(" A ", " B "), PayriffGooglePayConfig("A", "B"))
    }

    @Test
    fun availabilityGooglePayColumn() = runTest {
        val cases = vectors.getJSONObject("availability").getJSONArray("cases").objects()
        assertEquals(10, cases.size)
        // The description's assumptions: the config is set and valid, the device can pay and is not compromised.
        val config = PayriffConfig(publishableKey = PUBLISHABLE_KEY, googlePay = PayriffGooglePayConfig())
        for (case in cases) {
            val name = case.getString("name")
            val order = order(case.getJSONObject("order"))
            val expected = case.getJSONObject("expect").getBoolean("googlePay")
            assertEquals(name, expected, GooglePayAvailability.orderProblem(order) == null)
            val offer = GooglePayAvailability.decide(config, canPresent = true, order = order, appName = "Example Shop", isReadyToPay = { true })
            assertEquals(name, expected, offer != null)
        }
    }

    @Test
    fun theOrdersWalletObjectsAreReadAsTheWebSdkReadsThem() {
        val cases = vectors.getJSONObject("availability").getJSONArray("cases").objects().associateBy { it.getString("name") }
        val withGateway = order(cases.getValue("Google Pay listed with gateway parameters").getJSONObject("order"))
        assertEquals(PayriffClientOrder.GooglePay("payriff", "BCR2DN4T"), withGateway.googlePay)
        assertNull(withGateway.applePay)
        val apple = order(cases.getValue("Apple Pay listed").getJSONObject("order"))
        assertEquals(PayriffClientOrder.ApplePay("merchant.com.payriff"), apple.applePay)
        assertNull("googlePay: null", apple.googlePay)
        val blank = order(cases.getValue("Google Pay without gatewayMerchantId is not offered").getJSONObject("order"))
        assertEquals("kept as it is", "  ", blank.googlePay?.gatewayMerchantId)
        // Only an object counts; its values only when they are strings.
        assertNull(order(JSONObject("""{"orderId":"o1","googlePay":"payriff","applePay":[1]}""")).googlePay)
        assertEquals(
            PayriffClientOrder.GooglePay(null, "M1"),
            order(JSONObject("""{"orderId":"o1","googlePay":{"gateway":7,"gatewayMerchantId":"M1"}}""")).googlePay,
        )
        assertEquals("GooglePay(gateway=payriff, gatewayMerchantId=BCR2DN4T)", withGateway.googlePay.toString())
        assertTrue(withGateway.toString().endsWith("applePay=null, googlePay=GooglePay(gateway=payriff, gatewayMerchantId=BCR2DN4T))"))
        assertEquals(withGateway, order(cases.getValue("Google Pay listed with gateway parameters").getJSONObject("order")))
        assertFalse(withGateway == blank)
    }

    @Test
    fun amountsHaveTwoDecimalsFromDecimalArithmetic() {
        val cases = vectors.getJSONArray("amounts").objects()
        assertEquals(6, cases.size)
        for (case in cases) {
            // As the API sends it: a JSON number in the order payload.
            val amount = order(JSONObject("""{"orderId":"o1","amount":${case.get("amount")}}""")).amount
            assertNotNull(amount)
            assertEquals(case.toString(), case.getString("formatted"), GooglePayRequests.totalPrice(amount!!))
        }
        // Decimal arithmetic: half up at the third decimal, never a binary floating-point string.
        assertEquals("1.01", GooglePayRequests.totalPrice(BigDecimal("1.005")))
    }

    @Test
    fun googlePayEnvironmentFollowsTheApiHost() {
        val cases = vectors.getJSONArray("googlePayEnvironments").objects()
        assertEquals(4, cases.size)
        for (case in cases) {
            val config = PayriffConfig(
                region = PayriffRegion.valueOf(case.getString("region").uppercase(Locale.ROOT)),
                environment = PayriffEnvironment.valueOf(case.getString("environment").uppercase(Locale.ROOT)),
            )
            val environment = GooglePayEnvironment.of(config.apiBaseUrl)
            assertEquals(case.toString(), case.getString("expect"), environment.name)
            val walletConstant = if (environment == GooglePayEnvironment.TEST) WalletConstants.ENVIRONMENT_TEST else WalletConstants.ENVIRONMENT_PRODUCTION
            assertEquals(walletConstant, environment.walletConstant)
        }
    }

    @Test
    fun googlePayRequestsAreTheVectorsJson() {
        val cases = vectors.getJSONArray("googlePayRequests").objects()
        assertEquals(3, cases.size)
        for (case in cases) {
            val name = case.getString("name")
            val configJson = case.getJSONObject("config")
            val config = PayriffGooglePayConfig(configJson.stringOrNull("merchantId"), configJson.stringOrNull("merchantName"))
            val region = PayriffRegion.valueOf(case.getString("region").uppercase(Locale.ROOT))
            val order = order(case.getJSONObject("order"))
            val transaction = checkNotNull(GooglePayRequests.transaction(config, order, region, case.getString("appName"))) { name }
            val expect = case.getJSONObject("expect")
            // Compared as JSON values, and as the text that goes to Google Pay's fromJson.
            assertJsonEquals("$name isReadyToPayRequest", expect.getJSONObject("isReadyToPayRequest"), GooglePayRequests.isReadyToPayRequest())
            assertJsonEquals("$name isReadyToPayRequest text", expect.getJSONObject("isReadyToPayRequest"), JSONObject(GooglePayRequests.isReadyToPayRequest().toString()))
            assertJsonEquals("$name paymentDataRequest", expect.getJSONObject("paymentDataRequest"), GooglePayRequests.paymentDataRequest(transaction))
            assertJsonEquals("$name paymentDataRequest text", expect.getJSONObject("paymentDataRequest"), JSONObject(GooglePayRequests.paymentDataRequest(transaction).toString()))
            assertJsonEquals("$name buttonAllowedPaymentMethods", expect.getJSONArray("buttonAllowedPaymentMethods"), GooglePayRequests.buttonAllowedPaymentMethods(transaction))
            assertJsonEquals("$name button text", expect.getJSONArray("buttonAllowedPaymentMethods"), JSONArray(transaction.buttonAllowedPaymentMethods))
            // buttonAllowedPaymentMethods is the allowedPaymentMethods of paymentDataRequest.
            assertJsonEquals(name, expect.getJSONObject("paymentDataRequest").getJSONArray("allowedPaymentMethods"), JSONArray(transaction.buttonAllowedPaymentMethods))
        }
    }

    @Test
    fun anOrderWithoutAmountCurrencyOrGatewayHasNoGooglePayRequest() {
        val config = PayriffGooglePayConfig()
        fun order(extra: String) = order(JSONObject("""{"orderId":"o1","status":"CREATED","paymentMethods":["GOOGLE_PAY"]$extra}"""))
        val gateway = ""","googlePay":{"gateway":"payriff","gatewayMerchantId":"M1"}"""
        assertNotNull(GooglePayRequests.transaction(config, order(""","amount":1,"currency":"AZN"$gateway"""), PayriffRegion.AZ, null))
        assertNull(GooglePayRequests.transaction(config, order(""","currency":"AZN"$gateway"""), PayriffRegion.AZ, null))
        assertNull(GooglePayRequests.transaction(config, order(""","amount":1,"currency":" "$gateway"""), PayriffRegion.AZ, null))
        assertNull(GooglePayRequests.transaction(config, order(""","amount":1,"currency":"AZN","googlePay":{"gateway":" ","gatewayMerchantId":"M1"}"""), PayriffRegion.AZ, null))
        assertEquals("the order has no amount", GooglePayAvailability.orderProblem(order(""","currency":"AZN"$gateway""")))
        assertEquals("the order has no currency", GooglePayAvailability.orderProblem(order(""","amount":1$gateway""")))
        // Without any name at all, merchantInfo is left out.
        val anonymous = GooglePayRequests.transaction(config, order(""","amount":1,"currency":"AZN"$gateway"""), PayriffRegion.AZ, "  ")!!
        assertNull(anonymous.merchantName)
        assertFalse(GooglePayRequests.paymentDataRequest(anonymous).has("merchantInfo"))
    }

    @Test
    fun googlePayPayRequest() = runTest {
        val case = vectors.getJSONArray("payRequests").objects().single { it.getJSONObject("body").getString("method") == "GOOGLE_PAY" }
        val token = case.getString("walletToken")
        val transport = FakeTransport { testScheduler.currentTime }
        transport.on(case.getString("method"), case.getString("path"), http(200, ok("""{"nextAction":"CHECK_STATUS"}""")))
        val action = CardApiClient(credentials(), transport).payWithWallet("o1", GooglePayRequests.METHOD, token, "idem-1")
        assertTrue(action === NextAction.CheckStatus)

        val request = transport.requests.single()
        assertEquals(case.getString("method"), request.method)
        assertEquals(case.getString("path"), request.path)
        for ((header, value) in case.getJSONObject("headers").toStringMap()) assertEquals(header, value, request.headers[header])
        val required = case.getJSONArray("requiredHeaders")
        for (i in 0 until required.length()) assertNotNull(required.getString(i), request.headers[required.getString(i)])
        assertEquals("idem-1", request.headers["X-Idempotency-Key"])
        val sent = request.headers.keys.map { it.lowercase(Locale.ROOT) }
        val absent = case.getJSONArray("absentHeaders")
        for (i in 0 until absent.length()) assertFalse("sends ${absent.getString(i)}", absent.getString(i).lowercase(Locale.ROOT) in sent)
        // The body as a JSON value, and the token exactly as the wallet returned it: not re-encoded, not wrapped.
        val body = JSONObject(checkNotNull(request.bodyText))
        assertJsonEquals("body", case.getJSONObject("body"), body)
        assertEquals(token, body.getString("encryptedMessage"))
        assertEquals(setOf("method", "encryptedMessage"), body.keyNames())
        assertEquals(case.getJSONObject("headers").getString("X-Client-App-Id"), APP_ID)
        assertEquals(CLIENT_SECRET, request.headers["X-Client-Secret"])
    }

    @Test
    fun theWalletBodyEscapesOnlyWhatJsonNeeds() {
        val token = "{\"a\":\"b/c\\\\d\"}\u0001é"
        val body = String(CardApiClient.walletPayBody("GOOGLE_PAY", token), Charsets.UTF_8)
        assertEquals("{\"method\":\"GOOGLE_PAY\",\"encryptedMessage\":\"{\\\"a\\\":\\\"b/c\\\\\\\\d\\\"}\\u0001é\"}", body)
        assertEquals(token, JSONObject(body).getString("encryptedMessage"))
    }

    @Test
    fun theTokenNeverReachesTheHttpLog() {
        val case = vectors.getJSONArray("payRequests").objects().single { it.getJSONObject("body").getString("method") == "GOOGLE_PAY" }
        val token = case.getString("walletToken")
        val logged = HttpLog.describeRequest(
            "POST",
            "https://api.payriff.com/api/v3/client/orders/o1/pay",
            mapOf("X-Public-Key" to PUBLISHABLE_KEY, "X-Client-Secret" to CLIENT_SECRET, "X-Idempotency-Key" to "idem-1"),
            CardApiClient.walletPayBody(GooglePayRequests.METHOD, token),
        )
        assertTrue(logged, "\"encryptedMessage\": \"***(${token.length})\"" in logged)
        for (part in listOf("MEQCIFakeSignature", "MFkwEwYH", "ZmFrZQ", "ECv2", "signedMessage")) assertFalse(part, part in logged)
        assertTrue("\"method\": \"GOOGLE_PAY\"" in logged)
    }

    @Test
    fun googlePayButtonStyles() {
        val styles = vectors.getJSONObject("buttonStyles")
        assertEquals(styles.getInt("defaultHeight").toFloat(), GooglePayButtonStyles.DEFAULT_HEIGHT_DP, 0f)
        assertEquals(styles.getInt("defaultCornerRadius").toFloat(), GooglePayButtonStyles.DEFAULT_CORNER_RADIUS_DP, 0f)
        assertEquals(styles.getInt("minHeight").toFloat(), GooglePayButtonStyles.MIN_HEIGHT_DP, 0f)
        val google = styles.getJSONObject("googlePay")

        val defaults = google.getJSONObject("defaults")
        assertEquals(defaults.getString("type").uppercase(Locale.ROOT), PayriffGooglePayButtonStyle().type.name)
        assertEquals(defaults.getString("theme").uppercase(Locale.ROOT), PayriffGooglePayButtonStyle().theme.name)

        val types = google.getJSONObject("types")
        assertEquals(PayriffGooglePayButtonType.entries.size, types.length())
        for (key in types.keyNames()) {
            val type = PayriffGooglePayButtonType.valueOf(key.uppercase(Locale.ROOT))
            assertEquals(key, buttonConstant(ButtonConstants.ButtonType::class.java, types.getString(key)), GooglePayButtonStyles.buttonType(type))
        }
        val themes = google.getJSONObject("themes")
        for (key in themes.keyNames()) {
            val theme = PayriffGooglePayButtonTheme.valueOf(key.uppercase(Locale.ROOT))
            val expected = buttonConstant(ButtonConstants.ButtonTheme::class.java, themes.getString(key))
            // An explicit theme is the same on a light and a dark page.
            assertEquals(key, expected, GooglePayButtonStyles.buttonTheme(theme, night = false))
            assertEquals(key, expected, GooglePayButtonStyles.buttonTheme(theme, night = true))
        }
        val automatic = google.getJSONObject("automatic")
        assertEquals(buttonConstant(ButtonConstants.ButtonTheme::class.java, automatic.getString("light")), GooglePayButtonStyles.buttonTheme(PayriffGooglePayButtonTheme.AUTOMATIC, night = false))
        assertEquals(buttonConstant(ButtonConstants.ButtonTheme::class.java, automatic.getString("dark")), GooglePayButtonStyles.buttonTheme(PayriffGooglePayButtonTheme.AUTOMATIC, night = true))

        // A height below 40 is raised to 40; a negative corner radius counts as 0; null takes the fallback.
        assertEquals(44f, GooglePayButtonStyles.heightDp(PayriffGooglePayButtonStyle(), GooglePayButtonStyles.DEFAULT_HEIGHT_DP), 0f)
        assertEquals(40f, GooglePayButtonStyles.heightDp(PayriffGooglePayButtonStyle(height = 30f), 44f), 0f)
        assertEquals(40f, GooglePayButtonStyles.heightDp(PayriffGooglePayButtonStyle(), 36f), 0f)
        assertEquals(56f, GooglePayButtonStyles.heightDp(PayriffGooglePayButtonStyle(height = 56f), 44f), 0f)
        assertEquals(40f, GooglePayButtonStyles.heightDp(PayriffGooglePayButtonStyle(height = Float.NaN), 44f), 0f)
        assertEquals(16f, GooglePayButtonStyles.cornerRadiusDp(PayriffGooglePayButtonStyle(), GooglePayButtonStyles.DEFAULT_CORNER_RADIUS_DP), 0f)
        assertEquals(0f, GooglePayButtonStyles.cornerRadiusDp(PayriffGooglePayButtonStyle(cornerRadius = -4f), 16f), 0f)
        assertEquals(8f, GooglePayButtonStyles.cornerRadiusDp(PayriffGooglePayButtonStyle(cornerRadius = 8f), 16f), 0f)
        assertEquals(0f, GooglePayButtonStyles.cornerRadiusDp(PayriffGooglePayButtonStyle(), -1f), 0f)
    }

    @Test
    fun theButtonOptionsAreInPixelsWithTheTransactionsPaymentMethods() {
        val transaction = GooglePayRequests.transaction(
            PayriffGooglePayConfig(),
            order(JSONObject("""{"orderId":"o1","amount":5,"currency":"AZN","status":"CREATED","paymentMethods":["GOOGLE_PAY"],"googlePay":{"gateway":"payriff","gatewayMerchantId":"M1"}}""")),
            PayriffRegion.AZ,
            "Shop",
        )!!
        val options = GooglePayButtonStyles.options(
            style = PayriffGooglePayButtonStyle(type = PayriffGooglePayButtonType.BUY, cornerRadius = 8f),
            night = true,
            density = 2.5f,
            fallbackHeight = 44f,
            fallbackCornerRadius = 16f,
            transaction = transaction,
        )
        assertEquals(ButtonConstants.ButtonType.BUY, options.buttonType)
        assertEquals(ButtonConstants.ButtonTheme.LIGHT, options.buttonTheme)
        assertEquals(20, options.cornerRadiusPx)
        assertEquals(110, options.heightPx)
        assertEquals(transaction.buttonAllowedPaymentMethods, options.allowedPaymentMethods)
        assertTrue("toString holds no merchant data", "M1" !in transaction.toString())
    }

    @Test
    fun theStyleBuilderMatchesNamedArguments() {
        val built = PayriffGooglePayButtonStyle.Builder()
            .type(PayriffGooglePayButtonType.CHECKOUT)
            .theme(PayriffGooglePayButtonTheme.LIGHT)
            .cornerRadius(4f)
            .height(48f)
            .build()
        val named = PayriffGooglePayButtonStyle(PayriffGooglePayButtonType.CHECKOUT, PayriffGooglePayButtonTheme.LIGHT, 4f, 48f)
        assertEquals(named, built)
        assertEquals(named, named.newBuilder().build())
        assertEquals(PayriffGooglePayButtonStyle(), PayriffGooglePayButtonStyle.Builder().build())
        with(PayriffGooglePayButtonStyle()) {
            assertEquals(PayriffGooglePayButtonType.PLAIN, type)
            assertEquals(PayriffGooglePayButtonTheme.AUTOMATIC, theme)
            assertNull(cornerRadius)
            assertNull(height)
        }
    }

    @Test
    fun theApplePaySectionsBelongToIos() {
        // Native Android has no applePay config (CONTRACT §17.2), so applePayMerchantIdentifiers, applePayRequests,
        // the Apple Pay pay request and applePaySheet are asserted by the iOS SDK.
        assertFalse(PayriffConfig::class.java.declaredFields.any { it.name.contains("applePay", ignoreCase = true) })
        assertTrue(vectors.has("applePayRequests") && vectors.has("applePaySheet") && vectors.has("applePayMerchantIdentifiers"))
    }

    private fun buttonConstant(holder: Class<*>, name: String): Int = holder.getField(name).getInt(null)
}

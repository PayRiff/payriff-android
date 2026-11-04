package com.payriff.android.internal.wallet

import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.wallet.WalletConstants
import com.google.android.gms.wallet.button.ButtonConstants
import com.payriff.android.PayriffClientOrder
import com.payriff.android.PayriffConfig
import com.payriff.android.PayriffGooglePayButtonStyle
import com.payriff.android.PayriffGooglePayButtonTheme
import com.payriff.android.PayriffGooglePayButtonType
import com.payriff.android.PayriffGooglePayConfig
import com.payriff.android.PayriffPaymentStatus
import com.payriff.android.PayriffRegion
import com.payriff.android.internal.PayriffLog
import com.payriff.android.internal.card.CardFormStyles
import com.payriff.android.internal.card.ClientApiHosts
import com.payriff.android.internal.card.PaymentViewPlan
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.math.BigDecimal
import java.util.Locale
import kotlin.math.max

/** The Google Pay environment (CONTRACT §17.4, `wallet.json` → `googlePayEnvironments`). */
internal enum class GooglePayEnvironment(val walletConstant: Int) {
    TEST(WalletConstants.ENVIRONMENT_TEST),
    PRODUCTION(WalletConstants.ENVIRONMENT_PRODUCTION),
    ;

    companion object {
        /** `TEST` with the sandbox host, `PRODUCTION` with any other: region `sa` has no sandbox, so it is production. */
        fun of(apiBaseUrl: String): GooglePayEnvironment = if (apiBaseUrl == ClientApiHosts.AZ_SANDBOX) TEST else PRODUCTION
    }
}

/**
 * The values of one order's Google Pay payment (CONTRACT §17.4, §17.5): the tokenization gateway and gateway merchant
 * id ([GooglePayGateway]), the amount with 2 decimals, the currency, the country of the region and the merchant's name
 * and id. None of them is secret.
 */
internal class GooglePayTransaction(
    val gateway: String,
    val gatewayMerchantId: String,
    val totalPrice: String,
    val currencyCode: String,
    val countryCode: String,
    val merchantName: String?,
    val merchantId: String?,
) {
    /** `buttonAllowedPaymentMethods` (CONTRACT §17.5) as JSON text, for the button's `ButtonOptions`. */
    val buttonAllowedPaymentMethods: String by lazy { GooglePayRequests.buttonAllowedPaymentMethods(this).toString() }

    override fun toString(): String =
        "GooglePayTransaction(gateway=$gateway, totalPrice=$totalPrice, currencyCode=$currencyCode, countryCode=$countryCode)"
}

/**
 * The `PAYMENT_GATEWAY` tokenization parameters of a Google Pay payment (CONTRACT §17.5, `wallet.json` →
 * `googlePayGateway`).
 */
internal data class GooglePayGateway(val gateway: String, val gatewayMerchantId: String)

/**
 * The Google Pay API requests of CONTRACT §17.5, exactly `wallet.json` → `googlePayRequests`: `isReadyToPayRequest`,
 * `paymentDataRequest` and the button's `buttonAllowedPaymentMethods`. Visa and Mastercard only, as for cards, with
 * `PAN_ONLY` and `CRYPTOGRAM_3DS`. Pure Kotlin, so the JVM unit tests compare them with the vectors.
 */
internal object GooglePayRequests {

    /** The `/pay` method of a Google Pay token (CONTRACT §17.7). */
    const val METHOD = "GOOGLE_PAY"

    val CARD_NETWORKS: List<String> = listOf("VISA", "MASTERCARD")
    val AUTH_METHODS: List<String> = listOf("PAN_ONLY", "CRYPTOGRAM_3DS")

    private const val API_VERSION = 2
    private const val API_VERSION_MINOR = 0

    /** The country of [region]: `AZ` or `SA`. */
    fun countryCode(region: PayriffRegion): String = when (region) {
        PayriffRegion.AZ -> "AZ"
        PayriffRegion.SA -> "SA"
    }

    /** The order's currency, trimmed and upper-cased; `null` when blank. */
    fun currencyCode(currency: String?): String? = currency?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }

    /** The amount with exactly 2 decimals and `.` as the separator, as on the Pay button (`wallet.json` → `amounts`). */
    fun totalPrice(amount: BigDecimal): String = PaymentViewPlan.amountText(amount)

    /** The config's name, else the order's, else the app's label; each trimmed and skipped when blank. */
    fun merchantName(config: PayriffGooglePayConfig, order: PayriffClientOrder, appName: String?): String? =
        listOf(config.merchantName, order.merchantName, appName).firstNotNullOfOrNull { name -> name?.trim()?.takeIf { it.isNotEmpty() } }

    /**
     * The tokenization parameters (CONTRACT §17.5, `wallet.json` → `googlePayGateway`), each value trimmed and skipped
     * when blank: the gateway merchant id of the order's `googlePay` ([order]), else of [config]; the gateway of the
     * order, else of [config] (`"payriff"` unless it names another). `null` without a gateway merchant id: Google Pay is
     * then not offered.
     */
    fun gateway(order: PayriffClientOrder.GooglePay?, config: PayriffGooglePayConfig): GooglePayGateway? {
        val gatewayMerchantId = given(order?.gatewayMerchantId) ?: given(config.gatewayMerchantId) ?: return null
        val gateway = given(order?.gateway) ?: given(config.gateway) ?: PayriffGooglePayConfig.DEFAULT_GATEWAY
        return GooglePayGateway(gateway, gatewayMerchantId)
    }

    /**
     * The payment of [order] with [config] in [region], or `null` when Google Pay lacks what it needs: a gateway merchant
     * id ([gateway]), the order's amount and its currency.
     */
    fun transaction(config: PayriffGooglePayConfig, order: PayriffClientOrder, region: PayriffRegion, appName: String?): GooglePayTransaction? {
        val tokenization = gateway(order.googlePay, config) ?: return null
        val amount = order.amount ?: return null
        val currency = currencyCode(order.currency) ?: return null
        return GooglePayTransaction(
            gateway = tokenization.gateway,
            gatewayMerchantId = tokenization.gatewayMerchantId,
            totalPrice = totalPrice(amount),
            currencyCode = currency,
            countryCode = countryCode(region),
            merchantName = merchantName(config, order, appName),
            merchantId = config.merchantId,
        )
    }

    /** `IsReadyToPayRequest`: one `CARD` method with the networks and auth methods, no tokenization. */
    fun isReadyToPayRequest(): JSONObject = request().put("allowedPaymentMethods", JSONArray().put(cardMethod()))

    /** `PaymentDataRequest`: the `CARD` method with the gateway tokenization, `merchantInfo` and the final total. */
    fun paymentDataRequest(transaction: GooglePayTransaction): JSONObject {
        val merchantInfo = JSONObject()
        transaction.merchantName?.let { merchantInfo.put("merchantName", it) }
        transaction.merchantId?.let { merchantInfo.put("merchantId", it) }
        val request = request()
        if (merchantInfo.length() > 0) request.put("merchantInfo", merchantInfo)
        return request
            .put("allowedPaymentMethods", buttonAllowedPaymentMethods(transaction))
            .put(
                "transactionInfo",
                JSONObject()
                    .put("totalPriceStatus", "FINAL")
                    .put("totalPrice", transaction.totalPrice)
                    .put("currencyCode", transaction.currencyCode)
                    .put("countryCode", transaction.countryCode),
            )
    }

    /** The `allowedPaymentMethods` of [paymentDataRequest], which the button gets too. */
    fun buttonAllowedPaymentMethods(transaction: GooglePayTransaction): JSONArray {
        val tokenization = JSONObject()
            .put("type", "PAYMENT_GATEWAY")
            .put("parameters", JSONObject().put("gateway", transaction.gateway).put("gatewayMerchantId", transaction.gatewayMerchantId))
        return JSONArray().put(cardMethod().put("tokenizationSpecification", tokenization))
    }

    private fun request(): JSONObject = JSONObject().put("apiVersion", API_VERSION).put("apiVersionMinor", API_VERSION_MINOR)

    private fun given(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

    private fun cardMethod(): JSONObject = JSONObject()
        .put("type", "CARD")
        .put(
            "parameters",
            JSONObject()
                .put("allowedAuthMethods", JSONArray(AUTH_METHODS))
                .put("allowedCardNetworks", JSONArray(CARD_NETWORKS)),
        )
}

/**
 * When Google Pay is offered (CONTRACT §17.3, `wallet.json` → `availability`), and the one debug line per order that
 * says so: `Google Pay offered: true`, or `Google Pay offered: false (<first failed condition>)`.
 */
internal object GooglePayAvailability {

    /** The reasons a payment view or button that never loads the order gives (CONTRACT §17.3 items 1–2). */
    const val NOT_CONFIGURED = "PayriffConfig.googlePay is not set"
    const val NO_HOST = "the view is not shown in a ComponentActivity"
    const val DEVICE_BLOCKED = "the device failed the integrity check"

    /** The reason Google Pay is not offered without a gateway merchant id, from the order or the config (CONTRACT §17.3). */
    const val NO_GATEWAY_MERCHANT_ID = "no gatewayMerchantId"

    /**
     * Why [order] cannot be paid with Google Pay with [config] (CONTRACT §17.3 item 3, revision 10), or `null` when it
     * can: the status must be `CREATED` or `PENDING`, a gateway merchant id must come from the order's `googlePay` or
     * from [config] ([GooglePayRequests.gateway]), and the order needs an amount and a currency for the total. The
     * order's `paymentMethods` are not checked: the merchant's config alone decides.
     */
    fun orderProblem(order: PayriffClientOrder, config: PayriffGooglePayConfig = PayriffGooglePayConfig()): String? = when {
        order.status != PayriffPaymentStatus.CREATED && order.status != PayriffPaymentStatus.PENDING ->
            "the order's status is ${order.rawStatus ?: "unknown"}"
        GooglePayRequests.gateway(order.googlePay, config) == null -> NO_GATEWAY_MERCHANT_ID
        order.amount == null -> "the order has no amount"
        GooglePayRequests.currencyCode(order.currency) == null -> "the order has no currency"
        else -> null
    }

    /**
     * Decides CONTRACT §17.3 for [order], in its order: [config] sets Google Pay and the button can be shown
     * ([canPresent]: a `ComponentActivity` hosts it), the order allows it ([orderProblem]; the caller already checked the
     * device when the view appeared), and the device can pay ([isReadyToPay] with the `isReadyToPayRequest`; an error is
     * `false`). Logs the decision. Returns the payment's values when Google Pay is offered, otherwise `null`.
     */
    suspend fun decide(
        config: PayriffConfig?,
        canPresent: Boolean,
        order: PayriffClientOrder,
        appName: String?,
        isReadyToPay: suspend (request: String) -> Boolean,
    ): GooglePayTransaction? {
        val googlePay = config?.googlePay ?: return notOffered(NOT_CONFIGURED)
        if (!canPresent) return notOffered(NO_HOST)
        orderProblem(order, googlePay)?.let { return notOffered(it) }
        val transaction = GooglePayRequests.transaction(googlePay, order, config.region, appName) ?: return notOffered("the order has no Google Pay total")
        val ready = try {
            isReadyToPay(GooglePayRequests.isReadyToPayRequest().toString())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            false
        }
        if (!ready) return notOffered("isReadyToPay is false")
        PayriffLog.debug { "Google Pay offered: true" }
        return transaction
    }

    /** Logs that Google Pay is not offered because of [reason]; always `null`. */
    fun notOffered(reason: String): GooglePayTransaction? {
        PayriffLog.debug { "Google Pay offered: false ($reason)" }
        return null
    }

    /** Logs the decision for a device that failed the integrity check when the view appeared, so no order was loaded. */
    fun deviceBlocked(config: PayriffConfig?) {
        notOffered(if (config?.googlePay == null) NOT_CONFIGURED else DEVICE_BLOCKED)
    }
}

/** What the Google Pay sheet returned (CONTRACT §17.5 step 3). Nothing of Google's `PaymentData` is ever logged. */
internal sealed class GooglePayOutcome {

    /** The customer paid: [paymentData] is Google's `PaymentData` JSON, which holds the token. [toString] leaves it out. */
    class Ok(val paymentData: String?) : GooglePayOutcome() {
        override fun toString(): String = "Ok"
    }

    /** The customer closed the sheet without paying. */
    object Cancelled : GooglePayOutcome() {
        override fun toString(): String = "Cancelled"
    }

    /** The Google Pay API reported [statusCode]. */
    class Error(val statusCode: Int) : GooglePayOutcome() {
        override fun toString(): String = "Error($statusCode)"
    }

    /** The sheet could not be launched. */
    object NotShown : GooglePayOutcome() {
        override fun toString(): String = "NotShown"
    }

    companion object {
        /**
         * The outcome of a Google Pay `Status` code: `SUCCESS` is [Ok] with [paymentData], `CANCELED` (and Google's
         * `ERROR_CODE_USER_CANCELLED`) is [Cancelled], anything else an [Error].
         */
        fun of(statusCode: Int, paymentData: () -> String?): GooglePayOutcome = when (statusCode) {
            CommonStatusCodes.SUCCESS -> Ok(paymentData())
            CommonStatusCodes.CANCELED, WalletConstants.ERROR_CODE_USER_CANCELLED -> Cancelled
            else -> Error(statusCode)
        }
    }
}

/** Reads the token of Google's `PaymentData` JSON. The token and the JSON are never logged. */
internal object GooglePayTokens {

    /**
     * `paymentMethodData.tokenizationData.token`, a string, exactly as Google returned it; `null` when it is missing or
     * blank, or the JSON cannot be read (CONTRACT §17.5 step 3).
     */
    fun token(paymentData: String?): String? {
        if (paymentData.isNullOrEmpty()) return null
        return try {
            val tokenization = JSONObject(paymentData).optJSONObject("paymentMethodData")?.optJSONObject("tokenizationData")
            (tokenization?.opt("token") as? String)?.takeIf { it.isNotBlank() }
        } catch (e: JSONException) {
            null
        }
    }
}

/**
 * What [com.google.android.gms.wallet.button.PayButton] is initialised with (CONTRACT §17.10, `wallet.json` →
 * `buttonStyles`): Google's button type and theme, the corner radius and the height in pixels, and the
 * `buttonAllowedPaymentMethods` JSON.
 */
internal data class GooglePayButtonOptions(
    val buttonType: Int,
    val buttonTheme: Int,
    val cornerRadiusPx: Int,
    val heightPx: Int,
    val allowedPaymentMethods: String,
)

/** How [PayriffGooglePayButtonStyle] maps to Google's `ButtonOptions` (CONTRACT §17.10). Pure Kotlin for the JVM tests. */
internal object GooglePayButtonStyles {

    /** The size of a standalone button without its own (`wallet.json` → `buttonStyles`), in dp. */
    const val DEFAULT_HEIGHT_DP = 44f
    const val DEFAULT_CORNER_RADIUS_DP = 16f

    /** A lower height is raised to this, in dp. */
    const val MIN_HEIGHT_DP = 40f

    fun buttonType(type: PayriffGooglePayButtonType): Int = when (type) {
        PayriffGooglePayButtonType.PLAIN -> ButtonConstants.ButtonType.PLAIN
        PayriffGooglePayButtonType.BUY -> ButtonConstants.ButtonType.BUY
        PayriffGooglePayButtonType.PAY -> ButtonConstants.ButtonType.PAY
        PayriffGooglePayButtonType.CHECKOUT -> ButtonConstants.ButtonType.CHECKOUT
        PayriffGooglePayButtonType.BOOK -> ButtonConstants.ButtonType.BOOK
        PayriffGooglePayButtonType.DONATE -> ButtonConstants.ButtonType.DONATE
        PayriffGooglePayButtonType.ORDER -> ButtonConstants.ButtonType.ORDER
        PayriffGooglePayButtonType.SUBSCRIBE -> ButtonConstants.ButtonType.SUBSCRIBE
    }

    /** [theme] for a page in the light or the dark ([night]) theme: [PayriffGooglePayButtonTheme.AUTOMATIC] contrasts with it. */
    fun buttonTheme(theme: PayriffGooglePayButtonTheme, night: Boolean): Int = when (theme) {
        PayriffGooglePayButtonTheme.AUTOMATIC -> if (night) ButtonConstants.ButtonTheme.LIGHT else ButtonConstants.ButtonTheme.DARK
        PayriffGooglePayButtonTheme.DARK -> ButtonConstants.ButtonTheme.DARK
        PayriffGooglePayButtonTheme.LIGHT -> ButtonConstants.ButtonTheme.LIGHT
    }

    /** The style's height, else [fallback], in dp; at least [MIN_HEIGHT_DP]. */
    fun heightDp(style: PayriffGooglePayButtonStyle, fallback: Float): Float = max(MIN_HEIGHT_DP, CardFormStyles.size(style.height ?: fallback))

    /** The style's corner radius, else [fallback], in dp; a negative one counts as 0. */
    fun cornerRadiusDp(style: PayriffGooglePayButtonStyle, fallback: Float): Float = CardFormStyles.size(style.cornerRadius ?: fallback)

    /**
     * The button's options for [style] on a light or dark ([night]) page at [density], with [fallbackHeight] and
     * [fallbackCornerRadius] (dp) where the style has none, for [transaction].
     */
    fun options(
        style: PayriffGooglePayButtonStyle,
        night: Boolean,
        density: Float,
        fallbackHeight: Float,
        fallbackCornerRadius: Float,
        transaction: GooglePayTransaction,
    ): GooglePayButtonOptions = GooglePayButtonOptions(
        buttonType = buttonType(style.type),
        buttonTheme = buttonTheme(style.theme, night),
        cornerRadiusPx = CardFormStyles.px(cornerRadiusDp(style, fallbackCornerRadius), density),
        heightPx = CardFormStyles.px(heightDp(style, fallbackHeight), density),
        allowedPaymentMethods = transaction.buttonAllowedPaymentMethods,
    )
}

/**
 * The Google Pay API (CONTRACT §17.5) behind a small interface, so the JVM unit tests fake readiness and results. The
 * real one is [GooglePayLauncher]: `PaymentsClient`, with the sheet's result through the host's activity-result
 * registry.
 */
internal interface GooglePayApi {

    /** `PaymentsClient.isReadyToPay` with [request] (an `IsReadyToPayRequest`); an error counts as `false`. */
    suspend fun isReadyToPay(request: String): Boolean

    /**
     * `PaymentsClient.loadPaymentData` with [request] (a `PaymentDataRequest`): opens the Google Pay sheet. Its
     * [GooglePayOutcome] arrives later through the host's activity-result registry, also after the activity was
     * recreated. Throws [RuntimeException] when the sheet cannot be opened.
     */
    fun loadPaymentData(request: String)
}

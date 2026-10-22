package com.payriff.android.internal.card

import com.payriff.android.PayriffClientOrder
import com.payriff.android.PayriffConfig
import com.payriff.android.PayriffEnvironment
import com.payriff.android.PayriffError
import com.payriff.android.PayriffErrorCode
import com.payriff.android.PayriffLanguage
import com.payriff.android.PayriffPaymentStatus
import com.payriff.android.internal.ApiParsing
import com.payriff.android.internal.CertificatePinning
import com.payriff.android.internal.CertificatePinningException
import com.payriff.android.internal.HostApp
import com.payriff.android.internal.HttpRequest
import com.payriff.android.internal.HttpResponse
import com.payriff.android.internal.HttpLog
import com.payriff.android.internal.HttpTransport
import com.payriff.android.internal.PayriffLog
import com.payriff.android.internal.Urls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.math.BigDecimal
import java.util.Locale

/** The Client API host of each environment (CONTRACT §16.2). The card flow ignores [PayriffConfig.baseUrl]. */
internal object ClientApiHosts {
    const val PRODUCTION = "https://api.payriff.com"

    /** **Provisional**: the backend has not confirmed the sandbox host yet (CONTRACT §16.13). Keep it this one constant. */
    const val SANDBOX = "https://sb-api.payriff.com"

    fun of(environment: PayriffEnvironment): String = when (environment) {
        PayriffEnvironment.PRODUCTION -> PRODUCTION
        PayriffEnvironment.SANDBOX -> SANDBOX
    }
}

/** What every Client API request carries. Never logged: [toString] leaves the key and the secret out. */
internal class CardCredentials(val host: String, val publishableKey: String, val clientSecret: String, val appId: String?) {
    override fun toString(): String = "CardCredentials(host=$host, appId=$appId)"
}

/** Key and secret rules of CONTRACT §16.2–§16.3 (`card_api.json` → `keys`, `clientSecrets`). */
internal object CardKeys {
    const val PUBLISHABLE_PREFIX = "pr_pk_live_"
    const val CLIENT_SECRET_PREFIX = "pr_cs_"
    private const val MAX_APP_ID_LENGTH = 255
    private val APP_ID = Regex("[A-Za-z][A-Za-z0-9_-]*(\\.[A-Za-z0-9_-]+)+")

    /** A publishable key: `pr_pk_live_…` of visible ASCII. A secret key (32 hex digits) never matches. */
    fun isValidPublishableKey(key: String?): Boolean = key != null && key.startsWith(PUBLISHABLE_PREFIX) && isHeaderSafe(key)

    /** The value sent as `X-Client-Secret`: trimmed, otherwise as is; `null` when it is not a `pr_cs_…` secret. */
    fun clientSecretToSend(raw: String?): String? =
        raw?.trim()?.takeIf { it.startsWith(CLIENT_SECRET_PREFIX) && isHeaderSafe(it) }

    /** The app's package name as `X-Client-App-Id`, or `null` (header omitted) when unknown or not reverse-DNS. */
    fun appId(packageName: String?): String? =
        packageName?.takeIf { it.length <= MAX_APP_ID_LENGTH && APP_ID.matches(it) }

    /** Visible ASCII only, so the value can never break or smuggle an HTTP header. */
    private fun isHeaderSafe(value: String): Boolean = value.all { it.code in 0x21..0x7E }
}

/** Builds the Client API setup from the configuration. Every failure is a [PayriffError]; none contains a secret. */
internal object CardSetup {

    fun credentials(config: PayriffConfig?, clientSecret: String?, packageName: String? = HostApp.packageName): CardCredentials {
        if (config == null) throw PayriffError(PayriffErrorCode.NOT_CONFIGURED, "Payriff.configure() has not been called.")
        val key = config.publishableKey
        if (!CardKeys.isValidPublishableKey(key)) {
            throw PayriffError(
                PayriffErrorCode.INVALID_CONFIG,
                "PayriffConfig.publishableKey must be your publishable key (pr_pk_live_…). Never use the secret key in the app.",
            )
        }
        val secret = CardKeys.clientSecretToSend(clientSecret) ?: throw PayriffError(
            PayriffErrorCode.INVALID_ORDER,
            "clientSecret must be the order's pr_cs_… value from your backend.",
        )
        return CardCredentials(ClientApiHosts.of(config.environment), checkNotNull(key), secret, CardKeys.appId(packageName))
    }

    /** The trimmed order id. Throws [PayriffErrorCode.INVALID_ORDER] when it is blank. */
    fun orderId(raw: String?): String =
        raw?.trim()?.takeIf { it.isNotEmpty() } ?: throw PayriffError(PayriffErrorCode.INVALID_ORDER, "orderId must not be blank.")
}

/** One `/status` answer. */
internal class CardStatus(val rawStatus: String?, val amount: BigDecimal?, val currency: String?) {
    val status: PayriffPaymentStatus = PayriffPaymentStatus.fromRaw(rawStatus)

    /** Final in the Client API's sense (CONTRACT §16.7; `card_api.json` → `status`). */
    val isFinal: Boolean get() = rawStatus?.trim()?.uppercase(Locale.ROOT) in FINAL_STATUSES

    companion object {
        val FINAL_STATUSES = setOf(
            "APPROVED", "PREAUTH_APPROVED", "DECLINED", "CANCELED", "EXPIRED", "PREAUTH_EXPIRED", "REFUNDED", "PARTIAL_REFUND",
            "REVERSE",
        )
    }
}

/** The Client API calls the card flow needs. Interface so flow tests can fake it. */
internal interface CardApi {
    suspend fun order(orderId: String): PayriffClientOrder
    suspend fun pay(orderId: String, card: EncryptedCard, idempotencyKey: String): NextAction
    suspend fun threeDs(orderId: String): NextAction
    suspend fun status(orderId: String): CardStatus
}

/**
 * The Client API over [HttpTransport] (CONTRACT §16.3). Every call throws [PayriffError] and is cancellable.
 *
 * - Headers on every request: `X-Public-Key`, `X-Client-Secret`, `X-Client-App-Id` (omitted when unknown) and
 *   `Accept`. `/pay` adds `Content-Type`, `x-secret-key` and `X-Idempotency-Key`. Never `Origin`, `X-Parent-Origin`
 *   or `Authorization`.
 * - Timeouts: GET 15 s, POST 30 s.
 * - HTTP 429 is retried 3 times after 1 s, 2 s and 4 s; a `/pay` without any response is retried once after 1 s.
 *   Retries resend the identical request: the same bytes and the same idempotency key.
 * - Nothing secret is logged: the method, the path and the HTTP status, and in debuggable apps every request and
 *   response with its secrets redacted ([HttpLog], CONTRACT §11).
 */
internal class CardApiClient(
    private val credentials: CardCredentials,
    private val transport: HttpTransport,
) : CardApi {

    override suspend fun order(orderId: String): PayriffClientOrder {
        val payload = call("GET", orderPath(orderId), null, null)
        return parseOrder(payload, orderId.trim())
    }

    override suspend fun pay(orderId: String, card: EncryptedCard, idempotencyKey: String): NextAction {
        val body = payBody(card)
        try {
            val payload = call("POST", orderPath(orderId) + "/pay", body, PayHeaders(card.wrappedKey, idempotencyKey))
            return NextAction.parse(payload)
        } finally {
            body.fill(0)
        }
    }

    override suspend fun threeDs(orderId: String): NextAction = NextAction.parse(call("GET", orderPath(orderId) + "/three-ds", null, null))

    override suspend fun status(orderId: String): CardStatus {
        val payload = call("GET", orderPath(orderId) + "/status", null, null)
        return CardStatus(payload.opt("status") as? String, decimal(payload.opt("amount")), payload.opt("currency") as? String)
    }

    private class PayHeaders(val wrappedKey: String, val idempotencyKey: String)

    private suspend fun call(method: String, path: String, body: ByteArray?, pay: PayHeaders?): JSONObject {
        val headers = LinkedHashMap<String, String>()
        headers["X-Public-Key"] = credentials.publishableKey
        headers["X-Client-Secret"] = credentials.clientSecret
        credentials.appId?.let { headers["X-Client-App-Id"] = it }
        headers["Accept"] = JSON
        if (pay != null) {
            headers["Content-Type"] = JSON
            headers["x-secret-key"] = pay.wrappedKey
            headers["X-Idempotency-Key"] = pay.idempotencyKey
        }
        val timeout = if (method == "POST") POST_TIMEOUT_MILLIS else GET_TIMEOUT_MILLIS
        val request = HttpRequest(method, credentials.host + path, headers, body, timeout.toInt())
        val response = send(request, timeout, retryNetworkOnce = pay != null)
        val json = ApiParsing.parseObject(response.body)
        if (response.status in SUCCESS_RANGE && json != null && json.opt("code") == ApiParsing.SUCCESS_CODE) {
            return json.opt("payload") as? JSONObject ?: JSONObject()
        }
        val error = CardApiErrors.map(response.status, json)
        PayriffLog.warning { "$method $path failed: ${error.code} (HTTP ${response.status}, code ${error.payriffCode})" }
        throw error
    }

    private suspend fun send(request: HttpRequest, timeoutMillis: Long, retryNetworkOnce: Boolean): HttpResponse {
        var rateLimitRetries = 0
        var networkRetried = false
        val path = request.url.removePrefix(credentials.host)
        while (true) {
            PayriffLog.debug { "${request.method} $path" }
            HttpLog.logRequest(request)
            val response = try {
                withTimeoutOrNull(timeoutMillis) { transport.execute(request) }
            } catch (e: CertificatePinningException) {
                // CONTRACT §16.15: a pinning failure is a non-retryable NETWORK_ERROR; nothing was sent.
                PayriffLog.warning { "${request.method} $path failed: the server certificate is not pinned." }
                throw PayriffError(PayriffErrorCode.NETWORK_ERROR, CertificatePinning.FAILURE_MESSAGE, isRetryable = false)
            } catch (e: IOException) {
                null
            } catch (e: RuntimeException) {
                if (e is CancellationException) throw e
                // For example IllegalArgumentException from header validation, whose message may quote a header value.
                // The cause is dropped on purpose (CONTRACT §11).
                throw PayriffError(PayriffErrorCode.UNKNOWN, "The request to Payriff could not be sent (${e.javaClass.simpleName}).")
            }
            if (response == null) {
                if (retryNetworkOnce && !networkRetried) {
                    networkRetried = true
                    PayriffLog.debug { "No response for ${request.method} $path; retrying once with the same request." }
                    delay(NETWORK_RETRY_DELAY_MILLIS)
                    continue
                }
                throw PayriffError(PayriffErrorCode.NETWORK_ERROR, "No response from Payriff (offline or timed out).", isRetryable = true)
            }
            PayriffLog.debug { "HTTP ${response.status} for ${request.method} $path" }
            HttpLog.logResponse(request, response)
            if (response.status == HTTP_TOO_MANY_REQUESTS && rateLimitRetries < RATE_LIMIT_BACKOFF_MILLIS.size) {
                delay(RATE_LIMIT_BACKOFF_MILLIS[rateLimitRetries++])
                continue
            }
            return response
        }
    }

    companion object {
        const val GET_TIMEOUT_MILLIS = 15_000L
        const val POST_TIMEOUT_MILLIS = 30_000L
        const val NETWORK_RETRY_DELAY_MILLIS = 1_000L
        val RATE_LIMIT_BACKOFF_MILLIS = listOf(1_000L, 2_000L, 4_000L)
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private val SUCCESS_RANGE = 200..299
        private const val JSON = "application/json"
        private const val ORDERS_PATH = "/api/v3/client/orders/"

        /** `/api/v3/client/orders/{orderId}`: the id trimmed and percent-encoded as one segment (CONTRACT §6, §16.3). */
        fun orderPath(orderId: String): String = ORDERS_PATH + Urls.encodePathSegment(CardSetup.orderId(orderId))

        /** `{"method":"CARD","encryptedMessage":"<base64>"}` as bytes, built without a String holding the ciphertext. */
        fun payBody(card: EncryptedCard): ByteArray {
            val head = "{\"method\":\"CARD\",\"encryptedMessage\":\"".toByteArray(Charsets.US_ASCII)
            val tail = "\"}".toByteArray(Charsets.US_ASCII)
            val out = ByteArray(head.size + card.encryptedMessage.size + tail.size)
            System.arraycopy(head, 0, out, 0, head.size)
            System.arraycopy(card.encryptedMessage, 0, out, head.size, card.encryptedMessage.size)
            System.arraycopy(tail, 0, out, head.size + card.encryptedMessage.size, tail.size)
            return out
        }

        fun parseOrder(payload: JSONObject, requestedOrderId: String): PayriffClientOrder {
            val rawStatus = payload.opt("status") as? String
            val methods = payload.opt("paymentMethods") as? JSONArray
            return PayriffClientOrder(
                orderId = (payload.opt("orderId") as? String)?.takeIf { it.isNotBlank() } ?: requestedOrderId,
                amount = decimal(payload.opt("amount")),
                currency = payload.opt("currency") as? String,
                description = payload.opt("description") as? String,
                merchantName = payload.opt("merchantName") as? String,
                status = PayriffPaymentStatus.fromRaw(rawStatus),
                rawStatus = rawStatus,
                language = (payload.opt("language") as? String)?.let { name ->
                    PayriffLanguage.entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
                },
                paymentMethods = if (methods == null) emptyList() else (0 until methods.length()).mapNotNull { methods.opt(it) as? String },
            )
        }

        private fun decimal(value: Any?): BigDecimal? = when (value) {
            is Number -> value.toString().toBigDecimalOrNull()
            is String -> value.trim().toBigDecimalOrNull()
            else -> null
        }
    }
}

/** Error mapping of CONTRACT §16.3 (`card_api.json` → `errors`), the same as the web SDK. */
internal object CardApiErrors {

    private const val HTTP_BAD_REQUEST = 400
    private const val HTTP_UNAUTHORIZED = 401
    private const val HTTP_NOT_FOUND = 404
    private const val HTTP_TOO_MANY_REQUESTS = 429
    private const val INVALID_REQUEST_CODE = "15400"

    fun map(httpStatus: Int, body: String): PayriffError = map(httpStatus, ApiParsing.parseObject(body))

    /** Whether the client retries this response itself before failing (429 only). */
    fun isRetried(httpStatus: Int): Boolean = httpStatus == HTTP_TOO_MANY_REQUESTS

    fun map(httpStatus: Int, json: JSONObject?): PayriffError {
        val payriffCode = json?.opt("code") as? String
        val (serverMessage, ticket) = ApiParsing.splitMessage(json?.opt("message") as? String)
        fun error(code: PayriffErrorCode, fallback: String, fatal: Boolean, retryable: Boolean = false) = PayriffError(
            code = code,
            message = serverMessage ?: fallback,
            isRetryable = retryable,
            httpStatus = httpStatus,
            payriffCode = payriffCode,
            supportTicket = ticket,
            isFatal = fatal,
        )
        return when {
            httpStatus == HTTP_UNAUTHORIZED -> error(
                PayriffErrorCode.UNAUTHORIZED,
                "Payriff refused the publishable key or this app (HTTP 401).",
                fatal = true,
            )
            httpStatus == HTTP_NOT_FOUND -> error(
                PayriffErrorCode.SESSION_EXPIRED,
                "The order was not found or its clientSecret expired (HTTP 404). Create a new order.",
                fatal = true,
            )
            httpStatus == HTTP_TOO_MANY_REQUESTS -> error(
                PayriffErrorCode.RATE_LIMITED,
                "Too many requests to Payriff (HTTP 429).",
                fatal = true,
            )
            httpStatus == HTTP_BAD_REQUEST && payriffCode == INVALID_REQUEST_CODE -> error(
                PayriffErrorCode.INVALID_REQUEST,
                "Payriff rejected the request format (code 15400).",
                fatal = false,
            )
            httpStatus == HTTP_BAD_REQUEST -> error(
                PayriffErrorCode.ORDER_NOT_PAYABLE,
                "The order cannot be paid (HTTP 400).",
                fatal = false,
            )
            else -> error(
                PayriffErrorCode.PAYMENT_FAILED,
                "Payriff did not accept the payment (HTTP $httpStatus).",
                fatal = false,
                retryable = true,
            )
        }
    }
}

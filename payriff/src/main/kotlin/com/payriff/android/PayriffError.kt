package com.payriff.android

/** Machine-readable error codes of [PayriffError]. The names are the same on every Payriff SDK. */
public enum class PayriffErrorCode {
    /** [Payriff.configure] was never called. */
    NOT_CONFIGURED,

    /**
     * The configuration cannot make card calls: [PayriffConfig.publishableKey] is missing or is not a publishable key
     * (`pr_pk_live_…`).
     */
    INVALID_CONFIG,

    /**
     * The order given to a card call is invalid: a blank `orderId`, or a `clientSecret` that is not the order's
     * `pr_cs_…` value. The message names the field.
     */
    INVALID_ORDER,

    /** Another card payment of this SDK is running ([Payriff.isPaymentInProgress]). */
    PAYMENT_IN_PROGRESS,

    /** The 3-D Secure sheet could not be shown, for example because Android System WebView is missing. */
    PRESENTATION_FAILED,

    /**
     * HTTP 401: the publishable key is wrong or revoked, or this app's package name is not allowed for it. Fatal.
     */
    UNAUTHORIZED,

    /** HTTP 429, after the SDK already retried 3 times (1 s, 2 s, 4 s). Fatal. */
    RATE_LIMITED,

    /**
     * Payriff answered with an error envelope that no other code describes. The card flow maps every Client API error
     * to one of the specific codes, so it does not return this one; the code stays part of the result JSON shared by
     * every Payriff SDK (CONTRACT §5.3).
     */
    API_ERROR,

    /** No HTTP response: offline, DNS, TLS or timeout. A `/pay` without a response was already retried once. */
    NETWORK_ERROR,

    /** Anything else, for example a card that could not be encrypted on this device. Never retryable. */
    UNKNOWN,

    /**
     * The card form is not filled in correctly. [PayriffError.fields] lists each field and its error. No
     * request was made.
     */
    VALIDATION_FAILED,

    /**
     * HTTP 404. The order does not exist, belongs to another merchant, or its `clientSecret` is wrong or
     * expired. Fatal: create a new order.
     */
    SESSION_EXPIRED,

    /**
     * The order cannot be paid by card: its status is not `CREATED` or `PENDING`, `CARD` is not among its
     * payment methods, or Payriff answered HTTP 400.
     */
    ORDER_NOT_PAYABLE,

    /** Payriff rejected the request format (HTTP 400 with code `15400`). An integration bug. */
    INVALID_REQUEST,

    /**
     * Payriff did not accept the payment (any other failure response, for example a card the issuer
     * refused before 3-D Secure). Carries Payriff's message. Retryable: the customer may try again.
     */
    PAYMENT_FAILED,

    /** Payriff answered with a next step the SDK cannot run, and the order status was not final. */
    UNEXPECTED_RESPONSE,

    /**
     * The device looks rooted or tampered with, or a hooking framework is loaded, so the card payment was refused
     * on it ([Payriff.isDeviceCompromised], CONTRACT §16.16). A check that cannot complete counts too: it fails closed.
     * Never retryable and not fatal: the same order can be paid on another device. For development, a debuggable app
     * can allow it anyway with [PayriffConfig.allowCompromisedDevices]; release builds ignore that flag.
     */
    DEVICE_COMPROMISED,
}

/** A field of [PayriffCardForm]. */
public enum class PayriffCardField {
    /** The card number. */
    NUMBER,

    /** The expiry date (`MM / YY`). */
    EXPIRY,

    /** The 3-digit security code. */
    CVV,

    /** The cardholder name (only when [PayriffCardForm.showHolder] is on). */
    HOLDER,
}

/**
 * One invalid field of a [PayriffErrorCode.VALIDATION_FAILED] error. It never contains what the customer typed.
 *
 * @property field The field.
 * @property errorCode Why it is invalid: `required`, `incomplete`, `invalid`, `unsupported` or `expired`.
 * @property error The localized message the form shows under the field.
 */
public class PayriffFieldError(
    public val field: PayriffCardField,
    public val errorCode: String,
    public val error: String,
) {
    override fun equals(other: Any?): Boolean =
        other is PayriffFieldError && field == other.field && errorCode == other.errorCode && error == other.error

    override fun hashCode(): Int = 31 * (31 * field.hashCode() + errorCode.hashCode()) + error.hashCode()

    override fun toString(): String = "PayriffFieldError(field=$field, errorCode=$errorCode, error=$error)"
}

/**
 * An SDK error.
 *
 * Payment outcomes are never thrown: [PayriffCardPaymentLauncher] returns [PayriffResult.Failed] carrying this error.
 * [Payriff.retrieveOrder] throws it.
 *
 * No key, client secret or card data ever appears in an error message.
 *
 * @property code Machine-readable code.
 * @property message Human-readable description. For Payriff API errors this is Payriff's message without
 *   its `Support ticket` suffix.
 * @property isRetryable Whether repeating the same call may succeed. For [PayriffErrorCode.PAYMENT_FAILED]: the
 *   customer may try again.
 * @property httpStatus HTTP status of the response, when there was one.
 * @property payriffCode Payriff's envelope `code`, for example `01000` or `15400`.
 * @property supportTicket Payriff's support ticket number from the error message, when present.
 * @property isFatal `true` means this order can no longer be paid from the app: create a new order (and a new
 *   `clientSecret`) before trying again.
 * @property fields The invalid fields of a [PayriffErrorCode.VALIDATION_FAILED] error, otherwise empty.
 */
public class PayriffError @JvmOverloads constructor(
    public val code: PayriffErrorCode,
    override val message: String,
    public val isRetryable: Boolean = false,
    public val httpStatus: Int? = null,
    public val payriffCode: String? = null,
    public val supportTicket: String? = null,
    cause: Throwable? = null,
    public val isFatal: Boolean = false,
    public val fields: List<PayriffFieldError> = emptyList(),
) : Exception(message, cause) {

    override fun toString(): String = buildString {
        append("PayriffError(code=").append(code.name)
        append(", message=").append(message)
        append(", isRetryable=").append(isRetryable)
        if (httpStatus != null) append(", httpStatus=").append(httpStatus)
        if (payriffCode != null) append(", payriffCode=").append(payriffCode)
        if (supportTicket != null) append(", supportTicket=").append(supportTicket)
        if (isFatal) append(", isFatal=true")
        if (fields.isNotEmpty()) append(", fields=").append(fields)
        append(')')
    }

    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

package com.payriff.android.internal

import android.os.SystemClock
import com.payriff.android.PayriffError
import com.payriff.android.PayriffErrorCode
import java.util.UUID

/**
 * One card payment at a time.
 *
 * A confirm reserves a flow id and holds it until its result; the 3-D Secure sheet confirms the reservation when it
 * is created (also after process death) and releases it when it finishes. A reservation that is never confirmed
 * expires after [PENDING_TIMEOUT_MILLIS].
 */
internal object PaymentGuard {

    const val PENDING_TIMEOUT_MILLIS = 10_000L

    /** Monotonic clock in milliseconds. Replaced in JVM unit tests. */
    @Volatile
    var clock: () -> Long = { SystemClock.elapsedRealtime() }

    private var activeFlowId: String? = null
    private var reservedAt = 0L
    private var confirmed = false

    fun newFlowId(): String = UUID.randomUUID().toString()

    /** [PayriffErrorCode.PAYMENT_IN_PROGRESS] when another payment holds the guard, else `null`. */
    @Synchronized
    fun busyError(): PayriffError? = if (isBusy()) inProgressError() else null

    /** Reserves the guard for [flowId] if it is free. Returns whether it did. */
    @Synchronized
    fun reserve(flowId: String): Boolean {
        if (isBusy()) return false
        activeFlowId = flowId
        reservedAt = clock()
        confirmed = false
        return true
    }

    /**
     * Called by the sheet activity when it is created (also after process death). Returns `false` when
     * another payment holds the guard; the activity must then finish with [inProgressError].
     */
    @Synchronized
    fun confirm(flowId: String): Boolean {
        if (activeFlowId == flowId) {
            confirmed = true
            return true
        }
        if (isBusy()) return false
        activeFlowId = flowId
        confirmed = true
        return true
    }

    /** Frees the guard if [flowId] holds it. */
    @Synchronized
    fun release(flowId: String) {
        if (activeFlowId == flowId) {
            activeFlowId = null
            confirmed = false
        }
    }

    fun inProgressError(): PayriffError =
        PayriffError(PayriffErrorCode.PAYMENT_IN_PROGRESS, "Another Payriff card payment is in progress.")

    /** Whether a payment holds the guard: a confirm in progress, or a 3-D Secure sheet. */
    @Synchronized
    fun isBusy(): Boolean {
        if (activeFlowId == null) return false
        return confirmed || clock() - reservedAt < PENDING_TIMEOUT_MILLIS
    }
}

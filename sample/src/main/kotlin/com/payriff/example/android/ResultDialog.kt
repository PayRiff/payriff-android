package com.payriff.example.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.payriff.android.PayriffCancelReason
import com.payriff.android.PayriffPendingReason
import com.payriff.android.PayriffResult
import org.json.JSONObject
import java.math.RoundingMode

/**
 * What the result dialog shows for one card payment. Plain strings, so it survives a configuration change in the
 * activity's saved state (a [PayriffResult] is not parcelable).
 */
class ResultContent(
    val kind: Kind,
    val title: String,
    val amount: String?,
    val message: String,
    val details: String,
    val json: String,
) {
    enum class Kind { SUCCESS, DECLINED, PENDING, CANCELLED, FAILED }

    fun save(out: Bundle) {
        out.putString(KEY_KIND, kind.name)
        out.putString(KEY_TITLE, title)
        out.putString(KEY_AMOUNT, amount)
        out.putString(KEY_MESSAGE, message)
        out.putString(KEY_DETAILS, details)
        out.putString(KEY_JSON, json)
    }

    companion object {
        private const val KEY_KIND = "result.kind"
        private const val KEY_TITLE = "result.title"
        private const val KEY_AMOUNT = "result.amount"
        private const val KEY_MESSAGE = "result.message"
        private const val KEY_DETAILS = "result.details"
        private const val KEY_JSON = "result.json"

        /** The content saved by [save], or `null` when no dialog was showing. */
        fun restore(state: Bundle?): ResultContent? {
            val kind = state?.getString(KEY_KIND)?.let { name -> Kind.entries.firstOrNull { it.name == name } } ?: return null
            return ResultContent(
                kind = kind,
                title = state.getString(KEY_TITLE).orEmpty(),
                amount = state.getString(KEY_AMOUNT),
                message = state.getString(KEY_MESSAGE).orEmpty(),
                details = state.getString(KEY_DETAILS).orEmpty(),
                json = state.getString(KEY_JSON).orEmpty(),
            )
        }

        fun of(context: Context, result: PayriffResult): ResultContent {
            val payment = result.payment
            val amount = payment?.amount?.let { "${it.setScale(2, RoundingMode.HALF_UP).toPlainString()} ${payment.currency.orEmpty()}".trim() }
            val json = JSONObject(result.toJson()).toString(2)
            val paymentLines = buildString {
                appendLine("Order id: ${payment?.orderId ?: "—"}")
                append("Status:   ${payment?.status ?: "—"}")
                if (payment?.rawStatus != null && payment.rawStatus != payment.status.name) append(" (${payment.rawStatus})")
            }
            return when (result) {
                is PayriffResult.Success -> ResultContent(
                    Kind.SUCCESS, context.getString(R.string.result_success), amount, context.getString(R.string.result_success_message), paymentLines, json,
                )
                is PayriffResult.Declined -> ResultContent(
                    Kind.DECLINED, context.getString(R.string.result_declined), amount, context.getString(R.string.result_declined_message), paymentLines, json,
                )
                is PayriffResult.Pending -> ResultContent(
                    Kind.PENDING,
                    context.getString(R.string.result_pending),
                    amount,
                    context.getString(R.string.result_pending_message),
                    "$paymentLines\nReason:   ${pendingReason(result.reason)}",
                    json,
                )
                is PayriffResult.Cancelled -> ResultContent(
                    Kind.CANCELLED,
                    context.getString(R.string.result_cancelled),
                    amount,
                    context.getString(R.string.result_cancelled_message),
                    "$paymentLines\nReason:   ${cancelReason(result.reason)}",
                    json,
                )
                is PayriffResult.Failed -> {
                    val error = result.error
                    val details = buildString {
                        appendLine("Error:    ${error.code}")
                        appendLine("HTTP:     ${error.httpStatus ?: "—"}")
                        appendLine("Payriff:  ${error.payriffCode ?: "—"}")
                        appendLine("Ticket:   ${error.supportTicket ?: "—"}")
                        appendLine("Retry:    ${if (error.isRetryable) "yes, the customer may try again" else "no"}")
                        append("Fatal:    ${if (error.isFatal) "yes, create a new order" else "no"}")
                        error.fields.forEach { append("\n  ${it.field}: ${it.error}") }
                    }
                    ResultContent(Kind.FAILED, context.getString(R.string.result_failed), null, error.message, details, json)
                }
            }
        }

        private fun pendingReason(reason: PayriffPendingReason): String = when (reason) {
            PayriffPendingReason.NOT_FINAL -> "not final yet"
            PayriffPendingReason.UNVERIFIED -> "unverified"
            PayriffPendingReason.VERIFICATION_FAILED -> "verification failed"
            PayriffPendingReason.UNEXPECTED_STATUS -> "unexpected status"
        }

        private fun cancelReason(reason: PayriffCancelReason): String = when (reason) {
            PayriffCancelReason.USER_CLOSED -> "closed by the customer"
            PayriffCancelReason.CANCELED -> "order cancelled"
            PayriffCancelReason.EXPIRED -> "order expired"
        }
    }
}

/** Shows [content] in a dialog over [activity]; [onDismiss] runs once it closes. */
fun showResultDialog(activity: Activity, content: ResultContent, onDismiss: () -> Unit): AlertDialog {
    val view = activity.layoutInflater.inflate(R.layout.dialog_result, null)
    val color = activity.getColor(
        when (content.kind) {
            ResultContent.Kind.SUCCESS -> R.color.success
            ResultContent.Kind.DECLINED, ResultContent.Kind.FAILED -> R.color.error
            ResultContent.Kind.PENDING -> R.color.warning
            ResultContent.Kind.CANCELLED -> R.color.text_secondary
        },
    )
    view.findViewById<FrameLayout>(R.id.resultIconFrame).background = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }
    view.findViewById<ImageView>(R.id.resultIcon).setImageResource(
        when (content.kind) {
            ResultContent.Kind.SUCCESS -> R.drawable.ic_result_success
            ResultContent.Kind.DECLINED -> R.drawable.ic_result_declined
            ResultContent.Kind.PENDING -> R.drawable.ic_result_pending
            ResultContent.Kind.CANCELLED -> R.drawable.ic_result_cancelled
            ResultContent.Kind.FAILED -> R.drawable.ic_result_failed
        },
    )
    view.findViewById<TextView>(R.id.resultTitle).text = content.title
    view.findViewById<TextView>(R.id.resultAmount).apply {
        text = content.amount
        visibility = if (content.amount == null) View.GONE else View.VISIBLE
    }
    view.findViewById<TextView>(R.id.resultMessage).text = content.message
    view.findViewById<TextView>(R.id.resultDetails).text = content.details
    view.findViewById<TextView>(R.id.resultJson).text = content.json
    val dialog = AlertDialog.Builder(activity)
        .setView(view)
        .setPositiveButton(R.string.result_close, null)
        .create()
    dialog.setOnDismissListener { onDismiss() }
    dialog.show()
    return dialog
}

package com.payriff.example.android

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.fragment.app.DialogFragment
import com.payriff.android.PayriffCardResultCallback
import com.payriff.android.PayriffPaymentView
import com.payriff.android.PayriffPaymentViewStyle
import com.payriff.android.PayriffResult
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * "Pay in popup": [PayriffPaymentView] in a centred dialog with a close button, at most 440 dp wide and 16 dp clear of
 * the screen's edges. It closes with the view's first result, which goes to [MainActivity] as a fragment result
 * ([PaymentOutcome]). Back, a tap outside and the close button close it, except while a payment runs.
 *
 * When the activity is recreated during 3-D Secure, the fragment comes back with the view and its id, so the view
 * receives the result.
 */
class PaymentPopupFragment : AppCompatDialogFragment(R.layout.fragment_payment_popup) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // The dialog sits clear of the system bars and moves above the keyboard: the view needs no insets.
        consumeSystemInsets(view)
        val paymentView = view.findViewById<PayriffPaymentView>(R.id.paymentView)
        // The dialog's rounded background shows through.
        paymentView.style = PayriffPaymentViewStyle(backgroundColor = Color.TRANSPARENT)
        keepOpenWhilePaying(this, paymentView)
        view.findViewById<View>(R.id.closeButton).setOnClickListener {
            if (!paymentView.isProcessing) dismissAllowingStateLoss()
        }
        val order = PastedOrder.from(arguments)
        if (order == null) {
            dismissAllowingStateLoss()
            return
        }
        // Set while the fragment's view is created: a result that arrives after a recreation comes here too.
        paymentView.onResultListener = PayriffCardResultCallback { result -> finishWith(result) }
        paymentView.setOrder(order.orderId, order.clientSecret)
    }

    override fun onStart() {
        super.onStart()
        val window = dialog?.window ?: return
        val metrics = resources.displayMetrics
        val margin = (MARGIN_DP * metrics.density).roundToInt()
        // The payment view's own background in a card with rounded corners; the margin around it stays transparent.
        val card = GradientDrawable().apply {
            cornerRadius = CORNER_RADIUS_DP * metrics.density
            setColor(requireContext().getColor(R.color.payment_background))
        }
        window.setBackgroundDrawable(InsetDrawable(card, margin))
        val width = min(metrics.widthPixels, (MAX_WIDTH_DP * metrics.density).roundToInt() + 2 * margin)
        window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    private fun finishWith(result: PayriffResult) {
        if (!isAdded) return
        parentFragmentManager.setFragmentResult(PaymentOutcome.REQUEST_KEY, PaymentOutcome.of(requireContext(), result))
        dismissAllowingStateLoss()
    }

    companion object {
        private const val MAX_WIDTH_DP = 440
        private const val MARGIN_DP = 16
        private const val CORNER_RADIUS_DP = 16f

        /** The popup for the order your backend created. */
        fun newInstance(order: PastedOrder): PaymentPopupFragment = PaymentPopupFragment().apply { arguments = order.toBundle() }
    }
}

/**
 * Keeps [fragment]'s dialog open while [paymentView] pays: back, a tap outside and a swipe do nothing until the
 * payment's result, so the result is not lost. Checked before every frame; the view redraws when a payment starts and
 * ends. [onChange] receives whether the dialog may close again.
 */
fun keepOpenWhilePaying(fragment: DialogFragment, paymentView: PayriffPaymentView, onChange: (closable: Boolean) -> Unit = {}) {
    paymentView.viewTreeObserver.addOnPreDrawListener {
        val closable = !paymentView.isProcessing
        if (fragment.isCancelable != closable) {
            fragment.isCancelable = closable
            onChange(closable)
        }
        true
    }
}

package com.payriff.example.android

import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.view.View
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.payriff.android.PayriffCardResultCallback
import com.payriff.android.PayriffPaymentView
import com.payriff.android.PayriffPaymentViewStyle
import com.payriff.android.PayriffResult

/**
 * "Pay in bottom sheet": [PayriffPaymentView] in a modal bottom sheet sized to its content, like a payment sheet. The
 * sheet closes with the view's first result, which goes to [MainActivity] as a fragment result ([PaymentOutcome]). Back,
 * a tap outside and a swipe down close it, except while a payment runs.
 *
 * When the activity is recreated during 3-D Secure, the fragment comes back with the view and its id, so the view
 * receives the result.
 */
class PaymentSheetFragment : BottomSheetDialogFragment(R.layout.fragment_payment_sheet) {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        (super.onCreateDialog(savedInstanceState) as BottomSheetDialog).apply {
            // Sized to its content: the sheet opens all the way, and a swipe down closes it without stopping half way.
            behavior.skipCollapsed = true
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // The sheet makes room for the navigation bar and the keyboard itself.
        consumeSystemInsets(view)
        val paymentView = view.findViewById<PayriffPaymentView>(R.id.paymentView)
        // The sheet's own background shows through, rounded corners included.
        paymentView.style = PayriffPaymentViewStyle(backgroundColor = Color.TRANSPARENT)
        keepOpenWhilePaying(this, paymentView) { closable ->
            (dialog as? BottomSheetDialog)?.behavior?.isDraggable = closable
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

    private fun finishWith(result: PayriffResult) {
        if (!isAdded) return
        parentFragmentManager.setFragmentResult(PaymentOutcome.REQUEST_KEY, PaymentOutcome.of(requireContext(), result))
        dismissAllowingStateLoss()
    }

    companion object {
        /** The sheet for the order your backend created. */
        fun newInstance(order: PastedOrder): PaymentSheetFragment = PaymentSheetFragment().apply { arguments = order.toBundle() }
    }
}

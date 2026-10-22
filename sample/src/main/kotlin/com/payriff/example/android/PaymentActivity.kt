package com.payriff.example.android

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.payriff.android.PayriffCardResultCallback
import com.payriff.android.PayriffPaymentView
import com.payriff.android.PayriffResult

/**
 * "Pay in page": [PayriffPaymentView] on a screen of its own, in the SDK's default look (the card part of
 * pay.payriff.com), under a back button. It finishes with the view's first result, which [MainActivity] shows in the
 * result dialog. Back closes it, except while a payment runs.
 */
class PaymentActivity : AppCompatActivity() {

    private lateinit var paymentView: PayriffPaymentView

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_payment)
        // The screen makes room for the system bars and the keyboard, so the view inside gets no insets.
        applySystemInsets(findViewById(R.id.root))
        paymentView = findViewById(R.id.paymentView)
        val order = PastedOrder.from(intent.extras)
        if (order == null) {
            finish()
            return
        }
        // Set while the activity is created: a result that arrives after the activity was recreated during 3-D Secure
        // reaches the view with the same id (R.id.paymentView), and this listener.
        paymentView.onResultListener = PayriffCardResultCallback { result -> finishWith(result) }
        paymentView.setOrder(order.orderId, order.clientSecret)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { leave() }
        onBackPressedDispatcher.addCallback(this) { leave() }
    }

    private fun leave() {
        if (!paymentView.isProcessing) finish()
    }

    private fun finishWith(result: PayriffResult) {
        setResult(RESULT_OK, Intent().putExtras(PaymentOutcome.of(this, result)))
        finish()
    }

    companion object {
        /** Opens the payment screen for the order your backend created. */
        fun intent(context: Context, order: PastedOrder): Intent =
            Intent(context, PaymentActivity::class.java).putExtras(order.toBundle())
    }
}

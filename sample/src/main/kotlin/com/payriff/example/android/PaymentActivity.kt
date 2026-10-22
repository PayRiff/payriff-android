package com.payriff.example.android

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import com.payriff.android.PayriffCardFormLayout
import com.payriff.android.PayriffCardFormStyle
import com.payriff.android.PayriffCardResultCallback
import com.payriff.android.PayriffPaymentView
import com.payriff.android.PayriffPaymentViewStyle
import com.payriff.android.PayriffResult
import com.payriff.android.PayriffTextStyle

/**
 * The payment screen: nothing but [PayriffPaymentView], the SDK's default look (the card part of pay.payriff.com),
 * under a back button. [CardActivity] opens it for the order in its fields. It finishes with the view's first result,
 * which [CardActivity] shows in the result dialog.
 *
 * Settings change the view's style: "Custom style" ([customPaymentViewStyle]), or the default with the labels, the
 * badges, the secure line or the "Powered by Payriff" footer switched off.
 */
class PaymentActivity : ComponentActivity() {

    private lateinit var paymentView: PayriffPaymentView

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_payment)
        applySystemInsets(findViewById(R.id.root))
        paymentView = findViewById(R.id.paymentView)
        val orderId = intent.getStringExtra(EXTRA_ORDER_ID)
        val clientSecret = intent.getStringExtra(EXTRA_CLIENT_SECRET)
        if (orderId == null || clientSecret == null) {
            finish()
            return
        }
        paymentView.style = ExampleSettings(this).paymentViewStyle(this)
        // Set while the activity is created: a result that arrives after the activity was recreated during 3-D Secure
        // reaches the view with the same id (R.id.paymentView), and this listener.
        paymentView.onResultListener = PayriffCardResultCallback { result -> finishWith(result) }
        paymentView.setOrder(orderId, clientSecret)
        findViewById<View>(R.id.backButton).setOnClickListener { leave() }
        onBackPressedDispatcher.addCallback(this) { leave() }
    }

    /** Back closes the screen, except while a payment runs. */
    private fun leave() {
        if (!paymentView.isProcessing) finish()
    }

    private fun finishWith(result: PayriffResult) {
        val extras = Bundle()
        ResultContent.of(this, result).save(extras)
        // Any result but a failure used the order up.
        extras.putBoolean(EXTRA_ORDER_USED, result !is PayriffResult.Failed)
        setResult(RESULT_OK, Intent().putExtras(extras))
        finish()
    }

    companion object {
        private const val EXTRA_ORDER_ID = "com.payriff.example.android.extra.ORDER_ID"
        private const val EXTRA_CLIENT_SECRET = "com.payriff.example.android.extra.CLIENT_SECRET"
        private const val EXTRA_ORDER_USED = "com.payriff.example.android.extra.ORDER_USED"

        /** Opens the payment screen for the order your backend created. */
        fun intent(context: Context, order: PastedOrder): Intent = Intent(context, PaymentActivity::class.java)
            .putExtra(EXTRA_ORDER_ID, order.orderId)
            .putExtra(EXTRA_CLIENT_SECRET, order.clientSecret)

        /** Whether the result in [data] used the order up: anything but a failure. */
        fun orderUsed(data: Intent): Boolean = data.getBooleanExtra(EXTRA_ORDER_USED, false)
    }
}

/**
 * Settings → "Custom style": the same view restyled through [PayriffPaymentViewStyle] and [PayriffCardFormStyle], to
 * show that an app can change every part of the default look. The colours have light and dark variants.
 */
fun customPaymentViewStyle(context: Context): PayriffPaymentViewStyle {
    val accent = context.getColor(R.color.custom_form_accent)
    return PayriffPaymentViewStyle(
        buttonColor = accent,
        buttonCornerRadius = 28f,
        buttonHeight = 52f,
        showPoweredBy = false,
        formStyle = PayriffCardFormStyle(
            layout = PayriffCardFormLayout.STACKED,
            labelTextStyle = PayriffTextStyle(size = 12f, weight = PayriffTextStyle.BOLD, color = accent),
            inputTextStyle = PayriffTextStyle(size = 17f, weight = PayriffTextStyle.MEDIUM),
            placeholderColor = context.getColor(R.color.text_secondary),
            fieldBackgroundColor = context.getColor(R.color.custom_form_field),
            borderColor = context.getColor(R.color.custom_form_border),
            focusedBorderColor = accent,
            errorColor = context.getColor(R.color.error),
            borderWidth = 1.5f,
            cornerRadius = 20f,
            holderHeight = 54f,
            rowHeight = 54f,
            horizontalPadding = 18f,
            labelSpacing = 6f,
        ),
    )
}

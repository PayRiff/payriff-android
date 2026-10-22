package com.payriff.example.android

import android.content.ClipboardManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.payriff.android.PayriffPaymentView

/**
 * The only screen, like the iOS and Flutter examples. The order comes from your backend, as in a real app: your server
 * creates it with its secret key and returns `orderId` and `clientSecret`. Here you type them, or Paste reads the JSON
 * `{"orderId", "clientSecret"}` (or the two values separated by white space) from the clipboard.
 *
 * "Pay in page", "Pay in bottom sheet" and "Pay in popup" show the SDK's [PayriffPaymentView] for that order on a screen
 * of its own ([PaymentActivity]), in a bottom sheet ([PaymentSheetFragment]) or in a dialog ([PaymentPopupFragment]).
 * The outcome opens in a result dialog ([ResultDialogFragment]); any result but a failure clears the fields, as an
 * order can be paid once.
 */
class MainActivity : AppCompatActivity() {

    // Registered while the activity is created: a result that arrives after a recreation comes here too.
    private val paymentPage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val outcome = result.data?.extras
        if (result.resultCode == RESULT_OK && outcome != null) onPaymentFinished(outcome)
    }

    private lateinit var orderIdInput: EditText
    private lateinit var clientSecretInput: EditText
    private lateinit var pasteError: TextView
    private lateinit var payButtons: List<Button>

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applySystemInsets(findViewById(R.id.root))

        findViewById<View>(R.id.missingKey).isVisible = !ExampleSettings.hasPublishableKey
        orderIdInput = findViewById(R.id.orderIdInput)
        clientSecretInput = findViewById(R.id.clientSecretInput)
        pasteError = findViewById(R.id.pasteError)
        orderIdInput.doAfterTextChanged { render() }
        clientSecretInput.doAfterTextChanged { render() }
        findViewById<Button>(R.id.pasteButton).setOnClickListener { paste() }

        val inPage = findViewById<Button>(R.id.payInPage)
        val inBottomSheet = findViewById<Button>(R.id.payInBottomSheet)
        val inPopup = findViewById<Button>(R.id.payInPopup)
        payButtons = listOf(inPage, inBottomSheet, inPopup)
        inPage.setOnClickListener { pay { order -> paymentPage.launch(PaymentActivity.intent(this, order)) } }
        inBottomSheet.setOnClickListener { pay { order -> PaymentSheetFragment.newInstance(order).show(supportFragmentManager, PAYMENT_TAG) } }
        inPopup.setOnClickListener { pay { order -> PaymentPopupFragment.newInstance(order).show(supportFragmentManager, PAYMENT_TAG) } }
        // The bottom sheet's and the popup's outcome. Registered while the activity is created, like the page's.
        supportFragmentManager.setFragmentResultListener(PaymentOutcome.REQUEST_KEY, this) { _, outcome -> onPaymentFinished(outcome) }
        render()
    }

    /** The orderId and clientSecret in the fields, when both are set. */
    private fun typedOrder(): PastedOrder? {
        val orderId = orderIdInput.text.toString().trim()
        val clientSecret = clientSecretInput.text.toString().trim()
        return if (orderId.isEmpty() || clientSecret.isEmpty()) null else PastedOrder(orderId, clientSecret)
    }

    /** Reads `{"orderId", "clientSecret"}` or the two values separated by white space from the clipboard. */
    private fun paste() {
        // Plain text only: coerceToText would read a content URI through its provider, on the main thread.
        val clip = getSystemService(ClipboardManager::class.java)?.primaryClip
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text else null
        val order = PastedOrder.parse(text)
        pasteError.text = if (order == null) getString(R.string.paste_empty) else null
        pasteError.isVisible = order == null
        if (order == null) return
        orderIdInput.setText(order.orderId)
        clientSecretInput.setText(order.clientSecret)
    }

    /** Shows the payment view for the order in the fields with [present]; one payment at a time. */
    private fun pay(present: (PastedOrder) -> Unit) {
        val order = typedOrder() ?: return
        if (supportFragmentManager.findFragmentByTag(PAYMENT_TAG) != null) return
        currentFocus?.let { focused ->
            WindowCompat.getInsetsController(window, focused).hide(WindowInsetsCompat.Type.ime())
            focused.clearFocus()
        }
        present(order)
    }

    private fun onPaymentFinished(outcome: Bundle) {
        if (PaymentOutcome.orderUsed(outcome)) {
            orderIdInput.text = null
            clientSecretInput.text = null
        }
        ResultContent.restore(outcome)?.let { ResultDialogFragment.show(supportFragmentManager, it) }
    }

    /** The pay buttons wait for a publishable key and both fields. */
    private fun render() {
        val ready = ExampleSettings.hasPublishableKey && typedOrder() != null
        for (button in payButtons) button.isEnabled = ready
    }

    private companion object {
        /** The bottom sheet's or the popup's fragment tag. */
        const val PAYMENT_TAG = "payment"
    }
}

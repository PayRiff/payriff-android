package com.payriff.example.android

import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doAfterTextChanged
import com.payriff.android.Payriff
import com.payriff.android.PayriffCardForm
import com.payriff.android.PayriffCardFormStyle
import com.payriff.android.PayriffCardPaymentLauncher
import com.payriff.android.PayriffClientOrder
import com.payriff.android.PayriffError
import com.payriff.android.PayriffErrorCode
import com.payriff.android.PayriffResult
import com.payriff.android.PayriffTextStyle
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * "Card (native)", the example's main screen.
 *
 * The order comes from your backend, as in a real app: your server creates it with its secret key and returns
 * `orderId` and `clientSecret`. Here you paste them ("Paste order" reads the JSON `{"orderId", "clientSecret"}`) or
 * type them. "Pay with PayriffPaymentView" opens [PaymentActivity], the SDK's ready-made payment screen, for that
 * order. Below it, the screen is built from the SDK's parts instead: it shows the order through
 * [Payriff.retrieveOrder], and Pay confirms it with the card in the [PayriffCardForm] through
 * [PayriffCardPaymentLauncher]. Only the publishable key and the order's client secret are used. Either way, the outcome
 * opens in a result dialog. Below: the SDK log.
 *
 * The form shows the cardholder name and has Payriff's default look, the card form of the hosted payment page; "Form
 * style" switches to a custom [PayriffCardFormStyle].
 */
class CardActivity : ComponentActivity() {

    // Registered while the activity is created: a result that arrives after recreation (the 3-D Secure sheet was open
    // during a rotation or process death) goes to the same handler.
    private val cardPayment = PayriffCardPaymentLauncher(this) { result -> onPaymentResult(result) }

    // The payment view's result, which PaymentActivity returns; registered while the activity is created, too.
    private val paymentScreen =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result -> onPaymentScreenResult(result) }

    private val scope = MainScope()
    private val logListener: () -> Unit = ::renderLog

    private lateinit var configSummary: TextView
    private lateinit var missingKey: View
    private lateinit var cardContent: View
    private lateinit var cardForm: PayriffCardForm
    private lateinit var payButton: Button
    private lateinit var amountHeader: TextView
    private lateinit var descriptionHeader: TextView
    private lateinit var orderIdInput: EditText
    private lateinit var clientSecretInput: EditText
    private lateinit var pasteButton: Button
    private lateinit var paymentViewButton: Button
    private lateinit var clearOrderButton: Button
    private lateinit var orderInfoView: TextView
    private lateinit var pasteErrorView: TextView
    private lateinit var logView: TextView

    /** The order last checked with Payriff.retrieveOrder, and what the check found. */
    private var checkedOrder: PastedOrder? = null
    private var retrievedOrder: PayriffClientOrder? = null
    private var orderError: String? = null
    private var checkJob: Job? = null
    private var paying = false

    /** The result dialog on screen, kept across a configuration change. */
    private var shownResult: ResultContent? = null
    private var resultDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_card)
        applySystemInsets(findViewById(R.id.root))

        configSummary = findViewById(R.id.configSummary)
        missingKey = findViewById(R.id.missingKey)
        cardContent = findViewById(R.id.cardContent)
        cardForm = findViewById(R.id.cardForm)
        payButton = findViewById(R.id.payButton)
        amountHeader = findViewById(R.id.cardAmountHeader)
        descriptionHeader = findViewById(R.id.cardDescriptionHeader)
        orderIdInput = findViewById(R.id.cardOrderIdInput)
        clientSecretInput = findViewById(R.id.cardClientSecretInput)
        pasteButton = findViewById(R.id.pasteOrderButton)
        paymentViewButton = findViewById(R.id.paymentViewButton)
        clearOrderButton = findViewById(R.id.clearOrderButton)
        orderInfoView = findViewById(R.id.pastedOrderInfo)
        pasteErrorView = findViewById(R.id.pasteErrorView)
        logView = findViewById(R.id.logView)
        val holderToggle = findViewById<CheckBox>(R.id.holderToggle)
        cardForm.showHolder = holderToggle.isChecked
        holderToggle.setOnCheckedChangeListener { _, checked -> cardForm.showHolder = checked }
        val formStyleGroup = findViewById<RadioGroup>(R.id.formStyleGroup)
        applyFormStyle(formStyleGroup.checkedRadioButtonId)
        formStyleGroup.setOnCheckedChangeListener { _, checkedId -> applyFormStyle(checkedId) }
        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.clearLogButton).setOnClickListener { LogStore.clear() }

        orderIdInput.doAfterTextChanged { onOrderFieldsChanged() }
        clientSecretInput.doAfterTextChanged { onOrderFieldsChanged() }
        // Submitting either field checks the order, like a paste.
        orderIdInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT && typedOrder() != null) checkOrder()
            false
        }
        clientSecretInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) checkOrder()
            false
        }
        pasteButton.setOnClickListener { pasteOrder() }
        paymentViewButton.setOnClickListener { openPaymentView() }
        clearOrderButton.setOnClickListener { clearOrder() }
        payButton.setOnClickListener { pay() }
        render()
        ResultContent.restore(savedInstanceState)?.let(::showResult)
    }

    override fun onStart() {
        super.onStart()
        // Settings may have changed the configuration: the form follows PayriffConfig.language again.
        cardForm.language = null
        renderConfig()
        renderLog()
        LogStore.addListener(logListener)
    }

    override fun onStop() {
        LogStore.removeListener(logListener)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        shownResult?.save(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        // The fields come back after a recreation, the checked order does not: check it again.
        if (typedOrder() != null) checkOrder()
    }

    override fun onDestroy() {
        resultDialog?.setOnDismissListener(null)
        resultDialog?.dismiss()
        scope.cancel()
        super.onDestroy()
    }

    // region Order from your backend

    /** The orderId and clientSecret in the fields, when both are set. */
    private fun typedOrder(): PastedOrder? {
        val orderId = orderIdInput.text.toString().trim()
        val clientSecret = clientSecretInput.text.toString().trim()
        return if (orderId.isEmpty() || clientSecret.isEmpty()) null else PastedOrder(orderId, clientSecret)
    }

    /**
     * Reads `{"orderId", "clientSecret"}` or the two values separated by white space from the clipboard, then checks
     * the order with [Payriff.retrieveOrder].
     */
    private fun pasteOrder() {
        forgetCheck()
        // Plain text only: coerceToText would read a content URI through its provider, on the main thread.
        val clip = getSystemService(ClipboardManager::class.java)?.primaryClip
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text else null
        val order = PastedOrder.parse(text)
        if (order == null) {
            orderError = getString(R.string.card_paste_empty)
            render()
            return
        }
        orderIdInput.setText(order.orderId)
        clientSecretInput.setText(order.clientSecret)
        checkOrder()
    }

    /** Loads the typed or pasted order, so the screen can show its merchant, status, amount and description. */
    private fun checkOrder() {
        forgetCheck()
        val order = typedOrder()
        checkedOrder = order
        if (order == null) {
            render()
            return
        }
        checkJob = scope.launch {
            try {
                retrievedOrder = Payriff.retrieveOrder(order.orderId, order.clientSecret)
            } catch (error: PayriffError) {
                orderError = "${error.code}: ${error.message}"
            }
            checkJob = null
            render()
        }
        render()
    }

    /** An edit makes the last check stale. */
    private fun onOrderFieldsChanged() {
        if (typedOrder() != checkedOrder) forgetCheck()
        render()
    }

    private fun forgetCheck() {
        checkJob?.cancel()
        checkJob = null
        checkedOrder = null
        retrievedOrder = null
        orderError = null
    }

    /** Forgets the order: an order can be paid once. */
    private fun clearOrder() {
        orderIdInput.setText("")
        clientSecretInput.setText("")
        forgetCheck()
        render()
    }

    // endregion

    // region Payment view

    /** Opens [PaymentActivity], the SDK's ready-made payment screen, for the order in the fields. */
    private fun openPaymentView() {
        val order = typedOrder() ?: return
        paymentScreen.launch(PaymentActivity.intent(this, order))
    }

    /** The payment view's result, shown like the card form's. Back without a result changes nothing. */
    private fun onPaymentScreenResult(result: ActivityResult) {
        val data = result.data
        if (result.resultCode != RESULT_OK || data == null) return
        if (PaymentActivity.orderUsed(data)) clearOrder()
        ResultContent.restore(data.extras)?.let(::showResult)
    }

    // endregion

    // region Form style

    /** "Form style": Payriff's default look (the hosted payment page's card form) or [customFormStyle]. */
    private fun applyFormStyle(checkedId: Int) {
        cardForm.style = if (checkedId == R.id.formStyleCustom) customFormStyle() else PayriffCardFormStyle()
    }

    /**
     * What an app can change: rounder, tinted fields with violet outlines, small bold labels in the accent colour and
     * larger text. Every property left out keeps Payriff's default; the colours have light and dark variants.
     */
    private fun customFormStyle(): PayriffCardFormStyle {
        val accent = getColor(R.color.custom_form_accent)
        return PayriffCardFormStyle(
            labelTextStyle = PayriffTextStyle(size = 12f, weight = PayriffTextStyle.BOLD, color = accent),
            inputTextStyle = PayriffTextStyle(size = 17f, weight = PayriffTextStyle.MEDIUM),
            fieldBackgroundColor = getColor(R.color.custom_form_field),
            borderColor = getColor(R.color.custom_form_border),
            focusedBorderColor = accent,
            borderWidth = 1.5f,
            cornerRadius = 20f,
            rowHeight = 54f,
            horizontalPadding = 18f,
            labelSpacing = 6f,
            sectionSpacing = 24f,
        )
    }

    // endregion

    private fun pay() {
        val order = typedOrder() ?: return
        setPaying(true)
        cardPayment.confirm(cardForm, order.orderId, order.clientSecret) { result -> onPaymentResult(result) }
    }

    private fun onPaymentResult(result: PayriffResult) {
        setPaying(false)
        // The form already shows what to fix; no request was made.
        if (result.error?.code == PayriffErrorCode.VALIDATION_FAILED) return
        // Any result but a failure used the order up. Pay uses the fields, which are locked while it runs.
        if (result !is PayriffResult.Failed && typedOrder() != null) clearOrder()
        showResult(ResultContent.of(this, result))
    }

    private fun showResult(content: ResultContent) {
        resultDialog?.setOnDismissListener(null)
        resultDialog?.dismiss()
        shownResult = content
        resultDialog = showResultDialog(this, content) {
            shownResult = null
            resultDialog = null
        }
    }

    private fun setPaying(value: Boolean) {
        paying = value
        render()
    }

    /** The order section, the amount header and the Pay button. */
    private fun render() {
        val typed = typedOrder()
        val order = retrievedOrder
        val amount = order?.amount?.let { formatAmount(it, order.currency) }
        amountHeader.text = amount ?: getString(R.string.card_no_order)
        descriptionHeader.text = when {
            order != null -> order.description ?: order.merchantName.orEmpty()
            checkJob != null -> getString(R.string.card_checking_order)
            else -> getString(R.string.card_no_order_hint)
        }
        payButton.text = when {
            paying -> getString(R.string.card_paying)
            amount != null -> getString(R.string.card_pay_amount, amount)
            else -> getString(R.string.card_pay)
        }
        payButton.isEnabled = !paying && typed != null
        paymentViewButton.isEnabled = !paying && typed != null
        orderInfoView.text = order?.let {
            getString(R.string.card_order_info, it.merchantName ?: getString(R.string.card_order_unnamed), it.rawStatus ?: it.status.name)
        }
        orderInfoView.visibility = if (order != null) View.VISIBLE else View.GONE
        pasteErrorView.text = orderError
        pasteErrorView.visibility = if (orderError != null) View.VISIBLE else View.GONE
        clearOrderButton.visibility = if (typed != null) View.VISIBLE else View.GONE
        for (view in listOf(orderIdInput, clientSecretInput, pasteButton, clearOrderButton)) view.isEnabled = !paying
    }

    private fun renderConfig() {
        val config = Payriff.config
        configSummary.text = if (config == null) {
            getString(R.string.config_missing)
        } else {
            listOf(
                "SDK ${Payriff.version}",
                config.environment.name.lowercase().replaceFirstChar { it.uppercase() },
                if (config.publishableKey != null) "publishable key set" else "no publishable key",
                "language: ${config.language?.name ?: "device"}",
            ).joinToString(" · ")
        }
        val configured = config?.publishableKey != null
        missingKey.visibility = if (configured) View.GONE else View.VISIBLE
        cardContent.visibility = if (configured) View.VISIBLE else View.GONE
    }

    private fun renderLog() {
        val lines = LogStore.snapshot()
        logView.text = if (lines.isEmpty()) getString(R.string.log_empty) else lines.joinToString("\n")
    }

    private fun formatAmount(amount: BigDecimal, currency: String?): String =
        "${amount.setScale(2, RoundingMode.HALF_UP).toPlainString()} ${currency.orEmpty()}".trim()
}

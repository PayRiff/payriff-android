package com.payriff.android

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.text.method.DigitsKeyListener
import android.text.method.PasswordTransformationMethod
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.os.ConfigurationCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.payriff.android.internal.HostApp
import com.payriff.android.internal.Languages
import com.payriff.android.internal.card.BrandMarkView
import com.payriff.android.internal.card.CardEditText
import com.payriff.android.internal.card.CardErrorCode
import com.payriff.android.internal.card.CardFormPlan
import com.payriff.android.internal.card.CardFormStyles
import com.payriff.android.internal.card.CardFormat
import com.payriff.android.internal.card.CardPlaintext
import com.payriff.android.internal.card.CardStrings
import com.payriff.android.internal.card.CardValidation
import com.payriff.android.internal.card.FieldUiState
import com.payriff.android.internal.card.ResolvedCardFormStyle
import com.payriff.android.internal.card.SecureWindows
import com.payriff.android.internal.card.applyTextStyle
import com.payriff.android.internal.screenProtectionOn
import java.util.Calendar
import kotlin.math.roundToInt

/** Card brands the form accepts (CONTRACT §16.4). */
public enum class PayriffCardBrand {
    VISA,
    MASTERCARD,
}

/**
 * A change in one field of [PayriffCardForm] (CONTRACT §16.4). It never contains what the customer typed.
 *
 * @property field The field that changed.
 * @property isValid Whether the field is valid now.
 * @property isEmpty Whether the field is empty.
 * @property error The field's error message as the form shows it right now, or `null`. In the grouped layout the card
 *   box has one error line, which shows the first of the number, expiry and CVV errors.
 * @property brand The detected brand ([PayriffCardField.NUMBER] only), or `null`.
 * @property isComplete Whether every field of the form is valid, so a payment can be confirmed.
 */
public class PayriffCardFormChange(
    public val field: PayriffCardField,
    public val isValid: Boolean,
    public val isEmpty: Boolean,
    public val error: String?,
    public val brand: PayriffCardBrand?,
    public val isComplete: Boolean,
) {
    override fun toString(): String =
        "PayriffCardFormChange(field=$field, isValid=$isValid, isEmpty=$isEmpty, error=$error, brand=$brand, " +
            "isComplete=$isComplete)"
}

/** Listener for [PayriffCardForm] changes. */
public fun interface PayriffCardFormListener {
    /** Called on the main thread when a field changes, gains or loses focus, or shows or hides its error. */
    public fun onChange(change: PayriffCardFormChange)
}

/**
 * The native card form of the card flow (CONTRACT §16.4–§16.5). By default it looks like the card form of Payriff's
 * hosted payment page: the cardholder name first, then one "Card information" box with the card number on top, the
 * brand's mark at its end (Visa or Mastercard, a neutral card outline until the typed digits tell), and
 * expiry | CVC below it. [showHolder] = `false` hides the cardholder name, and [style] changes the layout, labels,
 * text, colours and sizes. Pay with [PayriffCardPaymentLauncher.confirm]. In Compose, host it with
 * `AndroidView(factory = { PayriffCardForm(it) })`.
 *
 * Card data stays inside the SDK: no property or event exposes the number, expiry, CVV or name, and they leave the
 * device only encrypted. The form
 * - validates and formats like Payriff.js (Visa and Mastercard, Luhn, expiry up to 20 years ahead, 3-digit CVV), except
 *   that card numbers stop at 16 digits;
 * - shows errors at the right time: definite errors at once, "incomplete" after leaving the field, "required" after a
 *   payment attempt;
 * - turns off suggestions, autocorrection and the keyboard's personalised learning, masks the CVV, blocks copy and cut
 *   on the number and CVV (paste into the number works), and offers autofill hints only for number, expiry and name;
 * - never saves what was typed in the instance state, and clears it after a final payment result and when it is
 *   detached from the window;
 * - sets `FLAG_SECURE` on its activity's window while it is attached, and on its own window when it sits in a dialog,
 *   bottom sheet or popup, so screenshots, recordings and the recents thumbnail show nothing
 *   ([PayriffConfig.allowScreenCaptureInDebug] turns this off in debug builds only).
 *
 * Labels, placeholders and errors follow [language] (default: [PayriffConfig.language], then the device language), and
 * the default colours follow the system light or dark theme. For the complete payment screen of the hosted page, with
 * the Pay button, use [PayriffPaymentView], which contains this form.
 */
public class PayriffCardForm @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    /**
     * Shows the cardholder-name field, as Payriff's hosted payment page does. Defaults to `true`. Set it to `false` to
     * hide the field; the payment is then sent with the name `Unknown`, as on the web.
     */
    public var showHolder: Boolean = CardFormPlan.DEFAULT_SHOW_HOLDER
        set(value) {
            if (field == value) return
            field = value
            if (!value) {
                holder.wipe()
                holder.touched = false
            }
            rebuild()
        }

    /** Moves the focus number → expiry → CVV when a typed field becomes valid with the caret at its end. Default `true`. */
    public var autoAdvance: Boolean = true

    /** Language of labels, placeholders and errors; `null` uses [PayriffConfig.language], then the device language. */
    public var language: PayriffLanguage? = null
        set(value) {
            field = value
            applyTexts()
        }

    /**
     * The look of the form: layout, labels, text, colours and sizes (CONTRACT §16.4). The default reproduces the card
     * form of Payriff's hosted payment page. Changing it keeps what was typed.
     */
    public var style: PayriffCardFormStyle = PayriffCardFormStyle()
        set(value) {
            if (field == value) return
            field = value
            rebuild()
        }

    /** Receives [PayriffCardFormChange] events. */
    public var onChangeListener: PayriffCardFormListener? = null

    /** `true` while a payment confirmed with this form runs; the fields are locked meanwhile. */
    public var isProcessing: Boolean = false
        private set

    /** Whether every field is valid. */
    public val isComplete: Boolean
        get() = mountedFields().all { it.check().valid }

    private val number = Field(PayriffCardField.NUMBER)
    private val expiry = Field(PayriffCardField.EXPIRY)
    private val cvv = Field(PayriffCardField.CVV)
    private val holder = Field(PayriffCardField.HOLDER)
    private val brandMark = BrandMarkView(context)
    private var sections: List<Section> = emptyList()
    private lateinit var resolved: ResolvedCardFormStyle
    private var submitted = false
    private var night = isNight(resources.configuration)
    private var rebuilding = false
    private var screenProtection: SecureWindows.Protection? = null

    init {
        orientation = VERTICAL
        isSaveEnabled = false
        isSaveFromParentEnabled = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            importantForContentCapture = IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        }
        configureInputs()
        rebuild()
    }

    /** Clears every field and its error state. Programmatic: no auto-advance. */
    public fun clear() {
        submitted = false
        for (field in allFields()) {
            field.wipe()
            field.touched = false
        }
        brandMark.setBrand(null, animate = true)
        sections.forEach { it.render() }
    }

    /** Moves the focus to [field]. */
    public fun focus(field: PayriffCardField) {
        fieldOf(field).input.requestFocus()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        updateLocked()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val config = Payriff.config ?: PayriffConfig()
        if (screenProtection == null && screenProtectionOn(config, HostApp.debuggable)) {
            // The activity's window and, for a form in a dialog, bottom sheet or popup, that window too.
            screenProtection = SecureWindows.protect(this)
        }
        applyTexts()
    }

    override fun onDetachedFromWindow() {
        // CONTRACT §16.5: values are cleared when the form is disposed.
        clear()
        screenProtection?.let(SecureWindows::release)
        screenProtection = null
        super.onDetachedFromWindow()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val nowNight = isNight(newConfig)
        if (nowNight != night) {
            night = nowNight
            rebuild()
        }
    }

    // region Card flow (internal)

    /** The outcome of [collectForPayment]. */
    internal sealed class Collected {
        class Invalid(val errors: List<PayriffFieldError>) : Collected()
        class Valid(val card: CardPlaintext) : Collected()
    }

    /** Step 1 of CONTRACT §16.7: marks the form submitted, shows every error, and returns the errors or the card. */
    internal fun collectForPayment(): Collected {
        submitted = true
        sections.forEach { it.render() }
        mountedFields().forEach { emit(it) }
        val language = effectiveLanguage()
        val errors = mountedFields().mapNotNull { field ->
            val code = field.check().errorCode ?: return@mapNotNull null
            PayriffFieldError(field.field, code.value, CardStrings.error(language, field.field, code))
        }
        if (errors.isNotEmpty()) return Collected.Invalid(errors)
        val pan = CardValidation.validateNumber(number.input.text).value
        val exp = checkNotNull(CardValidation.validateExpiry(expiry.input.text, nowYear(), nowMonth()).value)
        val code = CardValidation.validateCvv(cvv.input.text).value
        val name = if (showHolder) CardValidation.validateHolder(holder.input.text).value else UNKNOWN_HOLDER
        return Collected.Valid(
            CardPlaintext(pan.toCharArray(), name.toCharArray(), exp.year.toCharArray(), exp.month.toCharArray(), code.toCharArray()),
        )
    }

    /** Locks the fields while a payment runs (CONTRACT §16.7 step 3). */
    internal fun setProcessing(processing: Boolean) {
        isProcessing = processing
        updateLocked()
    }

    /** A payment with this form ended; [final] clears the card (CONTRACT §16.5). */
    internal fun onPaymentFinished(final: Boolean) {
        setProcessing(false)
        if (final) clear()
    }

    internal fun effectiveLanguage(): PayriffLanguage {
        val tag = ConfigurationCompat.getLocales(resources.configuration)[0]?.toLanguageTag()
        return Languages.effective(language, Payriff.config?.language, tag)
    }

    // endregion

    /** One input of the form and its state. The input moves between containers when the form is rebuilt. */
    private inner class Field(val field: PayriffCardField) {
        val input = CardEditText(context)
        var focused = false
        var touched = false
        var programmatic = false

        /** The section that shows the field, or `null` while it is not on the form. */
        var section: Section? = null

        /** What accessibility services call the field when no visible label names it (CONTRACT §16.4). */
        var accessibilityLabel: String? = null

        init {
            input.id = generateViewId()
            input.background = null
            input.gravity = Gravity.CENTER_VERTICAL or Gravity.START
            input.setOnFocusChangeListener { _, hasFocus ->
                // A rebuild moves the inputs into new sections; that is not the customer leaving the field.
                if (rebuilding) return@setOnFocusChangeListener
                focused = hasFocus
                if (!hasFocus) touched = true
                section?.render()
                emit(this)
            }
            ViewCompat.setAccessibilityDelegate(
                input,
                object : AccessibilityDelegateCompat() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        val label = accessibilityLabel ?: return
                        // Like a floating label: the label is the hint, and "label, placeholder" is read while the field
                        // is empty. The typed text stays as TextView reports it (masked for the CVV).
                        val empty = input.length() == 0
                        if (empty) info.text = input.hint?.let { "$label, $it" } ?: label
                        info.hintText = label
                        info.isShowingHintText = empty
                    }
                },
            )
        }

        fun check() = when (field) {
            PayriffCardField.NUMBER -> CardValidation.validateNumber(input.text).let { Check(it.valid, it.empty, it.errorCode, it.brand) }
            PayriffCardField.EXPIRY -> CardValidation.validateExpiry(input.text, nowYear(), nowMonth()).let { Check(it.valid, it.empty, it.errorCode, null) }
            PayriffCardField.CVV -> CardValidation.validateCvv(input.text).let { Check(it.valid, it.empty, it.errorCode, null) }
            PayriffCardField.HOLDER -> CardValidation.validateHolder(input.text).let { Check(it.valid, it.empty, it.errorCode, null) }
        }

        fun visibleError(): CardErrorCode? = CardValidation.visibleError(check().errorCode, FieldUiState(focused, touched, submitted))

        /**
         * Empties the field. `setText` rather than `Editable.clear()`: it also drops the editor's undo/redo history,
         * which holds every typed value and would otherwise bring the expiry or name back with Ctrl+Z after a payment
         * (CONTRACT §16.5).
         */
        fun wipe() {
            programmatic = true
            input.setText("")
            programmatic = false
        }
    }

    private class Check(val valid: Boolean, val empty: Boolean, val errorCode: CardErrorCode?, val brand: PayriffCardBrand?)

    /**
     * One outlined box of the form (CONTRACT §16.4): its label above it, its fields in rows separated by lines of the
     * border colour, and one error line below it.
     */
    private inner class Section(val plan: CardFormPlan.Section) {
        val fields: List<Field> = plan.fields.map(::fieldOf)
        val root = LinearLayout(context).apply { orientation = VERTICAL }
        val label = TextView(context)
        val error = TextView(context)
        private val box = LinearLayout(context).apply { orientation = VERTICAL }
        private val outline = GradientDrawable()

        init {
            val s = resolved
            label.applyTextStyle(s.label)
            label.visibility = if (s.showLabels) View.VISIBLE else View.GONE
            root.addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = s.labelSpacingPx })

            outline.cornerRadius = s.cornerRadiusPx
            outline.setColor(s.fieldBackgroundColor)
            box.background = outline
            // The cells sit inside the outline, so they never cover it.
            box.setPadding(s.borderWidthPx, s.borderWidthPx, s.borderWidthPx, s.borderWidthPx)
            plan.rows.forEachIndexed { index, row ->
                if (index > 0) box.addView(divider(), LayoutParams(LayoutParams.MATCH_PARENT, s.borderWidthPx))
                box.addView(rowView(row), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            }
            root.addView(box, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

            error.applyTextStyle(s.error)
            error.accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
            error.visibility = View.GONE
            root.addView(error, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(ERROR_GAP_DP) })
            fields.forEach { it.section = this }
        }

        /** A row of the box: one cell, or cells side by side (50/50) with a line between them. */
        private fun rowView(row: List<PayriffCardField>): View {
            if (row.size == 1) return cell(fieldOf(row.single()))
            val view = LinearLayout(context)
            view.orientation = HORIZONTAL
            view.isBaselineAligned = false
            row.forEachIndexed { index, field ->
                if (index > 0) view.addView(divider(), LayoutParams(resolved.borderWidthPx, LayoutParams.MATCH_PARENT))
                view.addView(cell(fieldOf(field)), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            }
            return view
        }

        /** Shows the section's one error line and colours its outline and its invalid fields. */
        fun render() {
            val line = CardFormPlan.errorLine(plan) { fieldOf(it).visibleError() }
            val message = line?.let { (field, code) -> CardStrings.error(effectiveLanguage(), field, code) }
            if (message == null) {
                if (error.length() > 0) error.text = ""
                error.visibility = View.GONE
            } else {
                error.visibility = View.VISIBLE
                // Only a new message: setting the same text again would make TalkBack repeat it on every keystroke.
                if (error.text.toString() != message) error.text = message
            }
            val color = resolved.outlineColor(showsError = line != null, focused = fields.any { it.focused })
            outline.setStroke(resolved.borderWidthPx, color)
            for (field in fields) field.input.setTextColor(if (field.visibleError() != null) resolved.errorColor else resolved.input.color)
        }
    }

    /** Formats a field as it is edited and decides auto-advance (web `input` handler). */
    private inner class Watcher(
        private val target: Field,
        private val caretAfter: (CharSequence, Int, String) -> Int = CardFormat::caretAfterReformat,
        private val format: (CharSequence, Boolean) -> String,
    ) : TextWatcher {
        private var inserting = true
        private var selfChange = false

        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
            if (!selfChange) inserting = after >= count
        }

        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) = Unit

        override fun afterTextChanged(s: Editable) {
            if (selfChange) return
            val formatted = format(s, inserting)
            if (!s.contentEquals(formatted)) {
                val caret = caretAfter(s, target.input.selectionEnd.coerceAtLeast(0), formatted)
                selfChange = true
                if (formatted.length == s.length) s.replaceChanged(formatted) else s.replace(0, s.length, formatted)
                selfChange = false
                target.input.setSelection(caret.coerceIn(0, s.length))
            }
            onEdited(target, typed = inserting && !target.programmatic && !target.input.isAutofilling)
        }
    }

    private fun onEdited(target: Field, typed: Boolean) {
        val check = target.check()
        if (target.field == PayriffCardField.NUMBER) brandMark.setBrand(check.brand, animate = true)
        target.section?.render()
        emit(target)
        if (autoAdvance && typed && check.valid && target.input.selectionEnd == target.input.length()) {
            when (target.field) {
                PayriffCardField.NUMBER -> expiry.input.requestFocus()
                PayriffCardField.EXPIRY -> cvv.input.requestFocus()
                PayriffCardField.CVV, PayriffCardField.HOLDER -> Unit
            }
        }
    }

    private fun emit(target: Field) {
        val listener = onChangeListener ?: return
        val check = target.check()
        val shown = target.visibleError()?.let { CardStrings.error(effectiveLanguage(), target.field, it) }
        listener.onChange(
            PayriffCardFormChange(
                field = target.field,
                isValid = check.valid,
                isEmpty = check.empty,
                error = shown,
                brand = if (target.field == PayriffCardField.NUMBER) check.brand else null,
                isComplete = isComplete,
            ),
        )
    }

    // IME_FLAG_NO_PERSONALIZED_LEARNING is an API 26 constant; keyboards on older versions ignore the unknown flag.
    @SuppressLint("InlinedApi")
    private fun configureInputs() {
        val noLearning = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        with(number.input) {
            // The formatter inserts spaces. A plain TYPE_CLASS_NUMBER would filter them out again, so the key
            // listener accepts them and the raw input type keeps the number keyboard.
            keyListener = DigitsKeyListener.getInstance("0123456789 ")
            setRawInputType(InputType.TYPE_CLASS_NUMBER)
            imeOptions = noLearning
            blocksCopy = true
            addTextChangedListener(Watcher(number) { s, _ -> CardFormat.formatNumber(s) })
        }
        with(expiry.input) {
            // Same for the " / " the formatter inserts.
            keyListener = DigitsKeyListener.getInstance("0123456789 /")
            setRawInputType(InputType.TYPE_CLASS_NUMBER)
            imeOptions = noLearning
            addTextChangedListener(Watcher(expiry) { s, inserting -> CardFormat.formatExpiry(s, inserting) })
        }
        with(cvv.input) {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
            imeOptions = noLearning
            blocksCopy = true
            addTextChangedListener(Watcher(cvv) { s, _ -> CardFormat.formatCvv(s) })
        }
        with(holder.input) {
            // CONTRACT §16.4: the keyboard opens in caps mode, and the watcher upper-cases whatever arrives anyway.
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME or
                InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = noLearning
            // Also filters the watcher's own replace, so the limit counts the upper-cased text (`ß` → `SS`).
            filters = arrayOf(InputFilter.LengthFilter(CardValidation.MAX_HOLDER_LENGTH))
            addTextChangedListener(Watcher(holder, CardFormat::caretAfterHolder) { s, _ -> CardFormat.formatHolder(s) })
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            number.input.setAutofillHints(View.AUTOFILL_HINT_CREDIT_CARD_NUMBER)
            number.input.importantForAutofill = IMPORTANT_FOR_AUTOFILL_YES
            expiry.input.setAutofillHints(View.AUTOFILL_HINT_CREDIT_CARD_EXPIRATION_DATE)
            expiry.input.importantForAutofill = IMPORTANT_FOR_AUTOFILL_YES
            holder.input.setAutofillHints(View.AUTOFILL_HINT_NAME)
            holder.input.importantForAutofill = IMPORTANT_FOR_AUTOFILL_YES
            // The CVV is never offered to autofill, so no service can save it.
            cvv.input.importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Only accessibility tools (TalkBack, Switch Access …) may read the fields, not other services.
            allFields().forEach { it.input.setAccessibilityDataSensitive(ACCESSIBILITY_DATA_SENSITIVE_YES) }
        }
    }

    /**
     * Builds the form for [style] and [showHolder] (CONTRACT §16.4), from [CardFormPlan]. The inputs move into the new
     * sections and keep what was typed; the field that had the focus gets it back.
     */
    private fun rebuild() {
        val focusedField = allFields().firstOrNull { it.input.hasFocus() }
        rebuilding = true
        try {
            resolved = CardFormStyles.resolve(style, night, resources.displayMetrics.density)
            removeAllViews()
            allFields().forEach { it.section = null }
            val lines = CardFormPlan.lines(resolved.layout, showHolder)
            val built = ArrayList<Section>()
            lines.forEachIndexed { index, line ->
                val view = if (line.size == 1) {
                    Section(line.single()).also { built += it }.root
                } else {
                    LinearLayout(context).apply {
                        orientation = HORIZONTAL
                        isBaselineAligned = false
                        line.forEachIndexed { position, plan ->
                            val section = Section(plan).also { built += it }
                            addView(
                                section.root,
                                LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { if (position > 0) marginStart = dp(STACKED_GAP_DP) },
                            )
                        }
                    }
                }
                addView(
                    view,
                    LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { if (index > 0) topMargin = resolved.sectionSpacingPx },
                )
            }
            sections = built
            updateImeActions(CardFormPlan.focusOrder(lines))
        } finally {
            rebuilding = false
        }
        allFields().forEach { it.focused = false }
        if (focusedField?.section != null) focusedField.input.requestFocus()
        applyTexts()
    }

    /** A field's cell in its box: the input, with the brand mark at the end of the card number. */
    private fun cell(target: Field): View {
        val s = resolved
        val input = target.input
        detach(input)
        input.applyTextStyle(s.input)
        input.setHintTextColor(s.placeholderColor)
        input.highlightColor = (s.focusedBorderColor and 0x00FFFFFF) or HIGHLIGHT_ALPHA
        // The row height includes the box's outline (CONTRACT §16.14: holder 46, card rows 40).
        input.minHeight = s.cellHeightPx(target.field)
        val padding = s.horizontalPaddingPx
        if (target.field != PayriffCardField.NUMBER) {
            input.setPaddingRelative(padding, dp(PADDING_V_DP), padding, dp(PADDING_V_DP))
            return input
        }
        detach(brandMark)
        brandMark.outlineColor = s.placeholderColor
        input.setPaddingRelative(padding, dp(PADDING_V_DP), padding + dp(BRAND_WIDTH_DP) + padding, dp(PADDING_V_DP))
        return FrameLayout(context).apply {
            addView(input, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            addView(
                brandMark,
                FrameLayout.LayoutParams(dp(BRAND_WIDTH_DP), dp(BRAND_HEIGHT_DP), Gravity.END or Gravity.CENTER_VERTICAL).apply {
                    marginEnd = padding
                },
            )
        }
    }

    /** A line of the border colour between two rows or cells of a box. */
    private fun divider(): View = View(context).apply {
        setBackgroundColor(resolved.borderColor)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun detach(view: View) {
        (view.parent as? ViewGroup)?.removeView(view)
    }

    /** Next moves through [order], the order the fields appear in; the last field gets Done. */
    private fun updateImeActions(order: List<PayriffCardField>) {
        order.forEachIndexed { index, field ->
            val input = fieldOf(field).input
            val next = order.getOrNull(index + 1)?.let { fieldOf(it).input.id }
            val action = if (next != null) EditorInfo.IME_ACTION_NEXT else EditorInfo.IME_ACTION_DONE
            input.imeOptions = (input.imeOptions and EditorInfo.IME_MASK_ACTION.inv()) or action
            input.nextFocusForwardId = next ?: View.NO_ID
        }
    }

    private fun applyTexts() {
        val language = effectiveLanguage()
        for (field in allFields()) field.input.hint = CardStrings.placeholder(language, field.field)
        for (section in sections) {
            section.label.text = CardFormPlan.labelText(section.plan.label, style, language)
            val named = CardFormPlan.labelNamesField(section.plan, style)
            section.label.labelFor = if (named) section.fields.single().input.id else View.NO_ID
            for (field in section.fields) {
                field.accessibilityLabel = CardFormPlan.accessibilityLabel(section.plan, field.field, style, language)
            }
            section.render()
        }
    }

    private fun updateLocked() {
        val editable = isEnabled && !isProcessing
        allFields().forEach { it.input.isEnabled = editable }
        alpha = if (isProcessing) PROCESSING_ALPHA else 1f
    }

    private fun mountedFields(): List<Field> = if (showHolder) listOf(number, expiry, cvv, holder) else listOf(number, expiry, cvv)

    private fun allFields(): List<Field> = listOf(number, expiry, cvv, holder)

    private fun fieldOf(field: PayriffCardField): Field = when (field) {
        PayriffCardField.NUMBER -> number
        PayriffCardField.EXPIRY -> expiry
        PayriffCardField.CVV -> cvv
        PayriffCardField.HOLDER -> holder
    }

    private fun isNight(configuration: Configuration): Boolean =
        (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun nowYear(): Int = Calendar.getInstance().get(Calendar.YEAR)

    private fun nowMonth(): Int = Calendar.getInstance().get(Calendar.MONTH) + 1

    private companion object {
        const val UNKNOWN_HOLDER = "Unknown"
        const val PADDING_V_DP = 8
        const val STACKED_GAP_DP = 12
        const val ERROR_GAP_DP = 6

        // The brand mark's badge (CONTRACT §16.4).
        const val BRAND_WIDTH_DP = 36
        const val BRAND_HEIGHT_DP = 24
        const val PROCESSING_ALPHA = 0.6f
        const val HIGHLIGHT_ALPHA = 0x40000000
    }
}

/**
 * Replaces only the characters that differ from [formatted], which has the same length (the upper-cased holder). A
 * whole-text replace drops the keyboard's composing region, and the keyboard then types the word a second time; this
 * keeps that region and the caret where they are.
 */
private fun Editable.replaceChanged(formatted: String) {
    var start = 0
    while (start < length && this[start] == formatted[start]) start++
    var end = length
    while (end > start && this[end - 1] == formatted[end - 1]) end--
    replace(start, end, formatted, start, end)
}

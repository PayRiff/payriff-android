package com.payriff.example.android

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import com.payriff.android.Payriff
import com.payriff.android.PayriffEnvironment
import com.payriff.android.PayriffLanguage

/**
 * Publishable key, environment and language, the logging and screenshot switches, and the look of the payment view.
 * Saving reconfigures Payriff; the payment view's look applies the next time it opens.
 */
class SettingsActivity : ComponentActivity() {

    private lateinit var settings: ExampleSettings
    private lateinit var publishableKeyInput: EditText
    private lateinit var environmentGroup: RadioGroup
    private lateinit var languageGroup: RadioGroup
    private lateinit var loggingSwitch: Switch
    private lateinit var screenCaptureSwitch: Switch
    private lateinit var customStyleSwitch: Switch
    private lateinit var showLabelsSwitch: Switch
    private lateinit var showBadgesSwitch: Switch
    private lateinit var showSecureNoteSwitch: Switch
    private lateinit var showPoweredBySwitch: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        applySystemInsets(findViewById(R.id.root))
        settings = ExampleSettings(this)

        publishableKeyInput = findViewById(R.id.publishableKeyInput)
        environmentGroup = findViewById(R.id.environmentGroup)
        languageGroup = findViewById(R.id.languageGroup)
        loggingSwitch = findViewById(R.id.loggingSwitch)
        screenCaptureSwitch = findViewById(R.id.screenCaptureSwitch)
        customStyleSwitch = findViewById(R.id.customStyleSwitch)
        showLabelsSwitch = findViewById(R.id.showLabelsSwitch)
        showBadgesSwitch = findViewById(R.id.showBadgesSwitch)
        showSecureNoteSwitch = findViewById(R.id.showSecureNoteSwitch)
        showPoweredBySwitch = findViewById(R.id.showPoweredBySwitch)
        // "Custom style" ignores the parts of the default look.
        customStyleSwitch.setOnCheckedChangeListener { _, custom -> enableParts(!custom) }

        findViewById<Button>(R.id.saveButton).setOnClickListener { save() }
        findViewById<Button>(R.id.resetButton).setOnClickListener {
            settings.reset()
            Payriff.configure(settings.toConfig())
            render()
        }
        if (savedInstanceState == null) render()
    }

    private fun render() {
        publishableKeyInput.setText(settings.publishableKey)
        environmentGroup.check(if (settings.environment == PayriffEnvironment.PRODUCTION) R.id.productionButton else R.id.sandboxButton)
        languageGroup.check(LANGUAGE_BUTTONS.getValue(settings.language))
        loggingSwitch.isChecked = settings.enableLogging
        screenCaptureSwitch.isChecked = settings.allowScreenCapture
        customStyleSwitch.isChecked = settings.customStyle
        showLabelsSwitch.isChecked = settings.showLabels
        showBadgesSwitch.isChecked = settings.showBadges
        showSecureNoteSwitch.isChecked = settings.showSecureNote
        showPoweredBySwitch.isChecked = settings.showPoweredBy
    }

    private fun enableParts(enabled: Boolean) {
        for (part in listOf(showLabelsSwitch, showBadgesSwitch, showSecureNoteSwitch, showPoweredBySwitch)) part.isEnabled = enabled
    }

    private fun save() {
        settings.publishableKey = publishableKeyInput.text.toString()
        settings.environment = if (environmentGroup.checkedRadioButtonId == R.id.productionButton) {
            PayriffEnvironment.PRODUCTION
        } else {
            PayriffEnvironment.SANDBOX
        }
        settings.language = LANGUAGE_BUTTONS.entries.firstOrNull { it.value == languageGroup.checkedRadioButtonId }?.key
        settings.enableLogging = loggingSwitch.isChecked
        settings.allowScreenCapture = screenCaptureSwitch.isChecked
        settings.customStyle = customStyleSwitch.isChecked
        settings.showLabels = showLabelsSwitch.isChecked
        settings.showBadges = showBadgesSwitch.isChecked
        settings.showSecureNote = showSecureNoteSwitch.isChecked
        settings.showPoweredBy = showPoweredBySwitch.isChecked
        Payriff.configure(settings.toConfig())
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    private companion object {
        /** `null` follows the device language. */
        val LANGUAGE_BUTTONS: Map<PayriffLanguage?, Int> = mapOf(
            null to R.id.languageDevice,
            PayriffLanguage.AZ to R.id.languageAz,
            PayriffLanguage.EN to R.id.languageEn,
            PayriffLanguage.RU to R.id.languageRu,
        )
    }
}

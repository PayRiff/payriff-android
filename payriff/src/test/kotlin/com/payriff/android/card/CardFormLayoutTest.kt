package com.payriff.android.card

import com.payriff.android.PayriffCardField
import com.payriff.android.PayriffCardField.CVV
import com.payriff.android.PayriffCardField.EXPIRY
import com.payriff.android.PayriffCardField.HOLDER
import com.payriff.android.PayriffCardField.NUMBER
import com.payriff.android.PayriffCardFormLayout
import com.payriff.android.PayriffCardFormStyle
import com.payriff.android.PayriffLanguage
import com.payriff.android.PayriffTextStyle
import com.payriff.android.internal.card.CardErrorCode
import com.payriff.android.internal.card.CardFormPlan
import com.payriff.android.internal.card.CardFormPlan.Label
import com.payriff.android.internal.card.CardFormStyles
import com.payriff.android.internal.card.CardValidation
import com.payriff.android.internal.card.FieldUiState
import com.payriff.android.internal.card.ResolvedTextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CONTRACT §16.4 "Layout and appearance": the sections the card form is built from, their order, the one error line
 * per section, the outline colours, the style defaults and overrides, and the stacked layout.
 */
class CardFormLayoutTest {

    private val default = PayriffCardFormStyle()

    private fun shape(lines: List<List<CardFormPlan.Section>>) = lines.map { line -> line.map { it.label to it.rows } }

    @Test
    fun groupedIsTheDefaultHolderFirstThenOneCardInformationBox() {
        assertEquals(PayriffCardFormLayout.GROUPED, default.layout)
        val lines = CardFormPlan.lines(PayriffCardFormLayout.GROUPED, showHolder = true)
        assertEquals(
            listOf(
                listOf(Label.HOLDER to listOf(listOf(HOLDER))),
                // One box: the number row, then expiry | CVC split in the row below.
                listOf(Label.CARD_INFORMATION to listOf(listOf(NUMBER), listOf(EXPIRY, CVV))),
            ),
            shape(lines),
        )
        assertEquals(listOf(HOLDER, NUMBER, EXPIRY, CVV), CardFormPlan.focusOrder(lines))

        val withoutHolder = CardFormPlan.lines(PayriffCardFormLayout.GROUPED, showHolder = false)
        assertEquals(listOf(listOf(Label.CARD_INFORMATION to listOf(listOf(NUMBER), listOf(EXPIRY, CVV)))), shape(withoutHolder))
        assertEquals(listOf(NUMBER, EXPIRY, CVV), CardFormPlan.focusOrder(withoutHolder))
    }

    @Test
    fun theDefaultFormShowsTheCardHolderLikeTheHostedPaymentPage() {
        // CONTRACT §16.4: PayriffCardForm starts with showHolder = CardFormPlan.DEFAULT_SHOW_HOLDER and the default style,
        // which must give pay.payriff.com's form: "Card holder", then the "Card information" box.
        assertTrue("the holder is shown unless the integrator sets showHolder = false", CardFormPlan.DEFAULT_SHOW_HOLDER)
        val lines = CardFormPlan.lines(default.layout, CardFormPlan.DEFAULT_SHOW_HOLDER)
        assertEquals(
            listOf(
                listOf(Label.HOLDER to listOf(listOf(HOLDER))),
                listOf(Label.CARD_INFORMATION to listOf(listOf(NUMBER), listOf(EXPIRY, CVV))),
            ),
            shape(lines),
        )
        assertEquals(listOf("Card holder", "Card information"), lines.flatten().map { CardFormPlan.labelText(it.label, default, PayriffLanguage.EN) })
    }

    @Test
    fun groupedLabelsAreTheHostedPagesCopyUnlessTheStyleSetsThem() {
        assertEquals("Card holder", CardFormPlan.labelText(Label.HOLDER, default, PayriffLanguage.EN))
        assertEquals("Card information", CardFormPlan.labelText(Label.CARD_INFORMATION, default, PayriffLanguage.EN))
        assertEquals("Kart sahibinin adı", CardFormPlan.labelText(Label.HOLDER, default, PayriffLanguage.AZ))
        assertEquals("Kart məlumatları", CardFormPlan.labelText(Label.CARD_INFORMATION, default, PayriffLanguage.AZ))
        assertEquals("Информация о карте", CardFormPlan.labelText(Label.CARD_INFORMATION, default, PayriffLanguage.RU))

        val custom = PayriffCardFormStyle(holderLabel = "Name on card", cardLabel = "Card details")
        assertEquals("Name on card", CardFormPlan.labelText(Label.HOLDER, custom, PayriffLanguage.RU))
        assertEquals("Card details", CardFormPlan.labelText(Label.CARD_INFORMATION, custom, PayriffLanguage.AZ))
    }

    @Test
    fun theCardBoxFieldsAreNamedByTheirLabelsForAccessibility() {
        val (holder, card) = CardFormPlan.lines(PayriffCardFormLayout.GROUPED, showHolder = true).map { it.single() }
        // No field label inside the box: label.number / label.expiry / label.cvv name the fields.
        assertFalse(CardFormPlan.labelNamesField(card, default))
        assertEquals(
            listOf("Card number", "Expiry date", "Security code"),
            card.fields.map { CardFormPlan.accessibilityLabel(card, it, default, PayriffLanguage.EN) },
        )
        assertEquals(
            listOf("Номер карты", "Срок действия", "Код безопасности"),
            card.fields.map { CardFormPlan.accessibilityLabel(card, it, default, PayriffLanguage.RU) },
        )
        // The visible "Card holder" label names the holder field; without labels the field carries it itself.
        assertTrue(CardFormPlan.labelNamesField(holder, default))
        assertNull(CardFormPlan.accessibilityLabel(holder, HOLDER, default, PayriffLanguage.EN))
        val noLabels = PayriffCardFormStyle(showLabels = false, holderLabel = "Name on card")
        assertFalse(CardFormPlan.labelNamesField(holder, noLabels))
        assertEquals("Name on card", CardFormPlan.accessibilityLabel(holder, HOLDER, noLabels, PayriffLanguage.EN))
        assertEquals("Card number", CardFormPlan.accessibilityLabel(card, NUMBER, noLabels, PayriffLanguage.EN))
    }

    @Test
    fun eachSectionShowsOneErrorLineTheFirstOfNumberExpiryCvc() {
        val (holder, card) = CardFormPlan.lines(PayriffCardFormLayout.GROUPED, showHolder = true).map { it.single() }

        // While typing: the number's definite error shows at once, an incomplete expiry waits for blur, an empty CVV
        // for the payment attempt (visibleError, card_validation.json).
        val typing = mapOf(
            NUMBER to visible(CardValidation.validateNumber("4242 4242 4242 4241").errorCode, focused = false, touched = true, submitted = false),
            EXPIRY to visible(CardValidation.validateExpiry("1", YEAR, MONTH).errorCode, focused = true, touched = false, submitted = false),
            CVV to visible(CardValidation.validateCvv("").errorCode, focused = false, touched = false, submitted = false),
        )
        assertEquals(NUMBER to CardErrorCode.INVALID, CardFormPlan.errorLine(card, typing::get))

        // After a payment attempt with a valid number: expiry and CVV both have errors; the line shows the expiry's.
        val submitted = mapOf(
            NUMBER to visible(CardValidation.validateNumber("4242 4242 4242 4242").errorCode, focused = false, touched = true, submitted = true),
            EXPIRY to visible(CardValidation.validateExpiry("1", YEAR, MONTH).errorCode, focused = false, touched = true, submitted = true),
            CVV to visible(CardValidation.validateCvv("").errorCode, focused = false, touched = false, submitted = true),
        )
        assertNull(submitted[NUMBER])
        assertEquals(CardErrorCode.REQUIRED, submitted[CVV])
        assertEquals(EXPIRY to CardErrorCode.INCOMPLETE, CardFormPlan.errorLine(card, submitted::get))

        assertEquals(CVV to CardErrorCode.INCOMPLETE, CardFormPlan.errorLine(card, mapOf(CVV to CardErrorCode.INCOMPLETE)::get))
        assertNull(CardFormPlan.errorLine(card, emptyMap<PayriffCardField, CardErrorCode>()::get))

        // The holder's section shows the holder's error only, whatever the card box shows.
        val everything = PayriffCardField.entries.associateWith { CardErrorCode.REQUIRED }
        assertEquals(HOLDER to CardErrorCode.REQUIRED, CardFormPlan.errorLine(holder, everything::get))
        assertNull(CardFormPlan.errorLine(holder, mapOf(NUMBER to CardErrorCode.INVALID)::get))
    }

    @Test
    fun theOutlineIsTheErrorColourThenTheFocusColourThenTheBorderColour() {
        val light = CardFormStyles.resolve(default, night = false, density = 1f)
        assertEquals(0xFFEC4D3D.toInt(), light.outlineColor(showsError = true, focused = true))
        assertEquals(0xFFEC4D3D.toInt(), light.outlineColor(showsError = true, focused = false))
        // The focus colour is Payriff's brand colour, whatever the app's theme.
        assertEquals(BRAND, light.outlineColor(showsError = false, focused = true))
        assertEquals(0xFFECEEF0.toInt(), light.outlineColor(showsError = false, focused = false))

        val dark = CardFormStyles.resolve(default, night = true, density = 1f)
        assertEquals(0xFF3F4145.toInt(), dark.outlineColor(showsError = false, focused = false))
        assertEquals(BRAND, dark.outlineColor(showsError = false, focused = true))
        assertEquals(0xFFEC5545.toInt(), dark.outlineColor(showsError = true, focused = true))
    }

    @Test
    fun theDefaultsAreThePaymentPagesInLightAndDark() {
        // CONTRACT §16.14 "Colours" and "Metrics". Nothing set in the text styles: every text keeps its default.
        with(default) {
            assertEquals(PayriffTextStyle(), labelTextStyle)
            assertEquals(PayriffTextStyle(), inputTextStyle)
            assertEquals(PayriffTextStyle(), errorTextStyle)
            assertTrue(showLabels)
            assertEquals(listOf(null, null), listOf(holderLabel, cardLabel))
            assertEquals(listOf<Int?>(null, null, null, null, null), listOf(placeholderColor, fieldBackgroundColor, borderColor, focusedBorderColor, errorColor))
            assertEquals(
                listOf(1f, 12f, 46f, 40f, 14f, 10f, 16f),
                listOf(borderWidth, cornerRadius, holderHeight, rowHeight, horizontalPadding, labelSpacing, sectionSpacing),
            )
        }
        val light = CardFormStyles.resolve(default, night = false, density = 3f)
        // Transparent fields: they show the page behind the form.
        assertEquals(0x00000000, light.fieldBackgroundColor)
        assertEquals(0xFFECEEF0.toInt(), light.borderColor)
        assertEquals(0xFF76787F.toInt(), light.placeholderColor)
        assertEquals(0xFFEC4D3D.toInt(), light.errorColor)
        assertEquals(BRAND, light.focusedBorderColor)
        // Labels in the text colour, typed text in the input colour, error lines in the error colour.
        assertEquals(0xFF34363A.toInt(), light.label.color)
        assertEquals(0xFF4D4F54.toInt(), light.input.color)
        assertEquals(light.errorColor, light.error.color)
        assertEquals(listOf(14f, 14f, 13f), listOf(light.label.size, light.input.size, light.error.size))
        assertEquals(listOf(600, 400, 400), listOf(light.label.weight, light.input.weight, light.error.weight))
        // dp → px at density 3.
        assertEquals(
            listOf(3, 138, 120, 42, 30, 48),
            listOf(light.borderWidthPx, light.holderHeightPx, light.rowHeightPx, light.horizontalPaddingPx, light.labelSpacingPx, light.sectionSpacingPx),
        )
        assertEquals(36f, light.cornerRadiusPx, 0f)

        val dark = CardFormStyles.resolve(default, night = true, density = 1f)
        assertEquals(0x00000000, dark.fieldBackgroundColor)
        assertEquals(0xFF3F4145.toInt(), dark.borderColor)
        assertEquals(0xFF5F6167.toInt(), dark.placeholderColor)
        assertEquals(0xFFEC5545.toInt(), dark.errorColor)
        assertEquals(BRAND, dark.focusedBorderColor)
        assertEquals(0xFFE1E4E8.toInt(), dark.label.color)
        assertEquals(0xFF8E8E93.toInt(), dark.input.color)
        assertEquals(dark.errorColor, dark.error.color)
    }

    @Test
    fun theHeightsIncludeTheOutlineAndTheRowsOfTheCardBoxShareTheirDivider() {
        for (density in listOf(1f, 2f, 3f)) {
            val s = CardFormStyles.resolve(default, night = false, density = density)
            val border = s.borderWidthPx
            // The holder field: its cell plus the outline above and below is holderHeight (46).
            assertEquals(s.holderHeightPx, s.cellHeightPx(HOLDER) + 2 * border)
            // The card box: outline, number row, the one divider between the rows, expiry | CVC row, outline. Two
            // 40 dp rows that share the line between them: 40 + 40 - 1 = 79 dp.
            val box = border + s.cellHeightPx(NUMBER) + border + s.cellHeightPx(EXPIRY) + border
            assertEquals(2 * s.rowHeightPx - border, box)
            assertEquals(s.cellHeightPx(EXPIRY), s.cellHeightPx(CVV))
        }
        assertEquals(79, CardFormStyles.resolve(default, night = false, density = 1f).let { 2 * it.rowHeightPx - it.borderWidthPx })
        // A border wider than half the height leaves no negative cell.
        assertEquals(0, CardFormStyles.resolve(PayriffCardFormStyle(rowHeight = 4f, borderWidth = 3f), night = false, density = 1f).cellHeightPx(NUMBER))
    }

    @Test
    fun aStyleOverrideTakesEffect() {
        val custom = PayriffCardFormStyle(
            labelTextStyle = PayriffTextStyle(size = 12f, weight = PayriffTextStyle.BOLD, color = LABEL),
            inputTextStyle = PayriffTextStyle(size = 18f, weight = PayriffTextStyle.MEDIUM),
            placeholderColor = PLACEHOLDER,
            fieldBackgroundColor = BACKGROUND,
            borderColor = BORDER,
            focusedBorderColor = FOCUS,
            errorColor = ERROR,
            borderWidth = 2f,
            cornerRadius = 20f,
            rowHeight = 56f,
            holderHeight = 60f,
            horizontalPadding = 16f,
            labelSpacing = 6f,
            sectionSpacing = 24f,
        )
        for (night in listOf(false, true)) {
            val s = CardFormStyles.resolve(custom, night, density = 2f)
            assertEquals(listOf(PLACEHOLDER, BACKGROUND, BORDER, FOCUS, ERROR), listOf(s.placeholderColor, s.fieldBackgroundColor, s.borderColor, s.focusedBorderColor, s.errorColor))
            assertEquals(ERROR, s.outlineColor(showsError = true, focused = true))
            assertEquals(FOCUS, s.outlineColor(showsError = false, focused = true))
            assertEquals(BORDER, s.outlineColor(showsError = false, focused = false))
            assertEquals(LABEL, s.label.color)
            assertEquals(12f, s.label.size, 0f)
            assertEquals(700, s.label.weight)
            assertEquals(18f, s.input.size, 0f)
            assertEquals(500, s.input.weight)
            // The error lines follow the custom error colour unless their own style sets one.
            assertEquals(ERROR, s.error.color)
            assertEquals(
                listOf(4, 112, 120, 32, 12, 48),
                listOf(s.borderWidthPx, s.rowHeightPx, s.holderHeightPx, s.horizontalPaddingPx, s.labelSpacingPx, s.sectionSpacingPx),
            )
            assertEquals(40f, s.cornerRadiusPx, 0f)
        }
        val ownErrorText = CardFormStyles.resolve(custom.copy(errorTextStyle = PayriffTextStyle(size = 12f, color = LABEL)), false, 1f)
        assertEquals(LABEL, ownErrorText.error.color)
        assertEquals(12f, ownErrorText.error.size, 0f)
        assertEquals("the weight it leaves out stays regular", 400, ownErrorText.error.weight)

        // Sizes that make no sense are clamped; a hairline border still shows as one pixel.
        val odd = CardFormStyles.resolve(
            PayriffCardFormStyle(
                borderWidth = 0.2f,
                cornerRadius = -4f,
                rowHeight = Float.NaN,
                holderHeight = -1f,
                labelTextStyle = PayriffTextStyle(size = 0f, weight = 5000),
            ),
            night = false,
            density = 1f,
        )
        assertEquals(1, odd.borderWidthPx)
        assertEquals(0f, odd.cornerRadiusPx, 0f)
        assertEquals(0, odd.rowHeightPx)
        assertEquals(0, odd.holderHeightPx)
        assertEquals(1f, odd.label.size, 0f)
        assertEquals(1000, odd.label.weight)
        assertEquals(0, CardFormStyles.resolve(PayriffCardFormStyle(borderWidth = 0f), false, 3f).borderWidthPx)
        // A text size that is not a number keeps the default.
        val notANumber = CardFormStyles.resolve(PayriffCardFormStyle(inputTextStyle = PayriffTextStyle(size = Float.POSITIVE_INFINITY)), false, 1f)
        assertEquals(14f, notANumber.input.size, 0f)
    }

    @Test
    fun aTextStyleIsMergedOverItsDefault() {
        // What a PayriffTextStyle leaves out keeps the default of its text: a colour alone keeps 14 sp semibold.
        val partial = PayriffCardFormStyle(
            labelTextStyle = PayriffTextStyle(color = LABEL),
            inputTextStyle = PayriffTextStyle(size = 18f),
            errorTextStyle = PayriffTextStyle(weight = PayriffTextStyle.BOLD),
        )
        fun ResolvedTextStyle.look() = Triple(size, weight, color)

        val light = CardFormStyles.resolve(partial, night = false, density = 1f)
        assertEquals(Triple(14f, 600, LABEL), light.label.look())
        assertEquals(Triple(18f, 400, 0xFF4D4F54.toInt()), light.input.look())
        assertEquals(Triple(13f, 700, 0xFFEC4D3D.toInt()), light.error.look())
        assertTrue(listOf(light.label, light.input, light.error).all { it.typeface == null })

        // The colours left out still follow the theme; the one set is used in both.
        val dark = CardFormStyles.resolve(partial, night = true, density = 1f)
        assertEquals(Triple(14f, 600, LABEL), dark.label.look())
        assertEquals(Triple(18f, 400, 0xFF8E8E93.toInt()), dark.input.look())
        assertEquals(Triple(13f, 700, 0xFFEC5545.toInt()), dark.error.look())

        // An empty text style is the default.
        val empty = CardFormStyles.resolve(PayriffCardFormStyle(labelTextStyle = PayriffTextStyle()), night = false, density = 1f)
        assertEquals(CardFormStyles.resolve(default, night = false, density = 1f).label.look(), empty.label.look())
    }

    @Test
    fun theStackedLayoutStillKeepsEveryFieldSeparate() {
        val lines = CardFormPlan.lines(PayriffCardFormLayout.STACKED, showHolder = true)
        assertEquals(
            listOf(
                listOf(Label.NUMBER to listOf(listOf(NUMBER))),
                listOf(Label.EXPIRY to listOf(listOf(EXPIRY)), Label.CVV to listOf(listOf(CVV))),
                listOf(Label.HOLDER to listOf(listOf(HOLDER))),
            ),
            shape(lines),
        )
        assertEquals(listOf(NUMBER, EXPIRY, CVV, HOLDER), CardFormPlan.focusOrder(lines))
        val stacked = PayriffCardFormStyle(layout = PayriffCardFormLayout.STACKED)
        val everything = PayriffCardField.entries.associateWith { CardErrorCode.REQUIRED }
        for (section in lines.flatten()) {
            val field = section.fields.single()
            // Its own label above it, naming it, and its own error below it.
            assertTrue(CardFormPlan.labelNamesField(section, stacked))
            assertEquals(field to CardErrorCode.REQUIRED, CardFormPlan.errorLine(section, everything::get))
            val othersOnly = (PayriffCardField.entries - field).associateWith { CardErrorCode.INVALID }
            assertNull(CardFormPlan.errorLine(section, othersOnly::get))
        }
        assertEquals(
            listOf("Card number", "Expiry date", "Security code", "Card holder"),
            lines.flatten().map { CardFormPlan.labelText(it.label, stacked, PayriffLanguage.EN) },
        )
        val noLabels = stacked.copy(showLabels = false)
        assertEquals(
            listOf("Card number", "Expiry date", "Security code", "Card holder"),
            lines.flatten().map { CardFormPlan.accessibilityLabel(it, it.fields.single(), noLabels, PayriffLanguage.EN) },
        )
        assertEquals(2, CardFormPlan.lines(PayriffCardFormLayout.STACKED, showHolder = false).size)
        assertEquals(listOf(NUMBER, EXPIRY, CVV), CardFormPlan.focusOrder(CardFormPlan.lines(PayriffCardFormLayout.STACKED, showHolder = false)))
    }

    @Test
    fun theJavaBuilderMatchesNamedArguments() {
        val built = PayriffCardFormStyle.Builder()
            .layout(PayriffCardFormLayout.STACKED)
            .labelTextStyle(PayriffTextStyle(13f, PayriffTextStyle.BOLD))
            .showLabels(false)
            .holderLabel("Name on card")
            .borderColor(BORDER)
            .focusedBorderColor(FOCUS)
            .cornerRadius(16f)
            .holderHeight(52f)
            .sectionSpacing(24f)
            .build()
        val named = PayriffCardFormStyle(
            layout = PayriffCardFormLayout.STACKED,
            labelTextStyle = PayriffTextStyle(size = 13f, weight = 700),
            showLabels = false,
            holderLabel = "Name on card",
            borderColor = BORDER,
            focusedBorderColor = FOCUS,
            cornerRadius = 16f,
            holderHeight = 52f,
            sectionSpacing = 24f,
        )
        assertEquals(named, built)
        assertEquals(named, named.newBuilder().build())
        assertEquals(default, PayriffCardFormStyle.Builder().build())
    }

    private fun visible(code: CardErrorCode?, focused: Boolean, touched: Boolean, submitted: Boolean): CardErrorCode? =
        CardValidation.visibleError(code, FieldUiState(focused, touched, submitted))

    private companion object {
        const val YEAR = 2026
        const val MONTH = 10

        /** Payriff's brand colour `#4DBDC6` (CONTRACT §16.14). */
        const val BRAND = 0xFF4DBDC6.toInt()
        const val LABEL = 0xFF123456.toInt()
        const val PLACEHOLDER = 0xFF777777.toInt()
        const val BACKGROUND = 0xFFFAF8FF.toInt()
        const val BORDER = 0xFFB9A8FF.toInt()
        const val FOCUS = 0xFF00A3A3.toInt()
        const val ERROR = 0xFFE00000.toInt()
    }
}

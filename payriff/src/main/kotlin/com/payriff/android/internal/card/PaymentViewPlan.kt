package com.payriff.android.internal.card

import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import com.payriff.android.PayriffClientOrder
import com.payriff.android.PayriffLanguage
import com.payriff.android.PayriffPaymentViewStyle
import com.payriff.android.PayriffTextStyle
import com.payriff.android.PayriffWalletPlacement
import com.payriff.android.R
import com.payriff.android.internal.wallet.GooglePayButtonStyles
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.WeakHashMap
import kotlin.math.max

/**
 * What [com.payriff.android.PayriffPaymentView] shows, top to bottom, and its copy (CONTRACT §16.14, §17.9, §20). Pure
 * Kotlin, so the JVM unit tests check the structure the view is built from.
 */
internal object PaymentViewPlan {

    /** A part of the view. */
    enum class Part { WALLET_BUTTON, WALLET_DIVIDER, FORM, PAY_BUTTON, ERROR, BADGES, SECURE_NOTE, POWERED_BY }

    /** A part and the space above it, in dp. */
    data class Item(val part: Part, val gapAboveDp: Int)

    /** The wallet divider, 16 below the wallet button (CONTRACT §17.9). */
    const val WALLET_DIVIDER_GAP_DP = 16

    /** The card form, 16 below the wallet divider, or below the wallet button when the style hides the divider. */
    const val WALLET_FORM_GAP_DP = 16

    /** The wallet button, 12 below the Pay button with [PayriffWalletPlacement.BELOW_PAY_BUTTON] (CONTRACT §20.1). */
    const val WALLET_BELOW_PAY_GAP_DP = 12

    /** Between the divider's text and each of its lines, and the lines' thickness. */
    const val WALLET_DIVIDER_TEXT_GAP_DP = 12
    const val WALLET_DIVIDER_LINE_DP = 1

    /** The Pay button, 24 below the form. */
    const val BUTTON_GAP_DP = 24

    /** The error line, 8 below the button. */
    const val ERROR_GAP_DP = 8

    /** The badge row, 24 below the button (or its error line). */
    const val BADGES_GAP_DP = 24

    /** The secure line, 20 below the badges; 24 below the button when the badges are hidden. */
    const val SECURE_NOTE_GAP_DP = 20
    const val SECURE_NOTE_GAP_WITHOUT_BADGES_DP = 24

    /** The footer, at least 24 below the content; at the bottom when there is room. */
    const val FOOTER_GAP_DP = 24

    /** Between two badges. */
    const val BADGE_SPACING_DP = 8

    /**
     * The frame drawn in code around each badge's mark (CONTRACT §18.4): a 1 dp stroke inside the badge's bounds, with
     * an outer corner radius of 4 dp and no fill, in `#ECEEF0` unless the style sets [PayriffPaymentViewStyle.badgeBorderColor].
     */
    const val BADGE_BORDER_DP = 1f
    const val BADGE_CORNER_RADIUS_DP = 4f
    const val BADGE_BORDER_COLOR = 0xFFECEEF0.toInt()

    /** Between the lock and the secure text. */
    const val LOCK_GAP_DP = 6

    /** The lock before the secure text, 15×15, drawn in code ([LockGlyph]). */
    const val LOCK_SIZE_DP = 15

    /**
     * The Pay button's title is one line: a long title is truncated at its end, never wrapped, so Pay grows only with the
     * font scale, and the wallet button with it (CONTRACT §20.2).
     */
    const val BUTTON_TITLE_MAX_LINES = 1

    /** The Pay button's spinner and the space around its title. */
    const val SPINNER_SIZE_DP = 20
    const val BUTTON_PADDING_HORIZONTAL_DP = 16
    const val BUTTON_PADDING_VERTICAL_DP = 8

    /** The page behind everything. */
    const val BACKGROUND_LIGHT = 0xFFFCFCFD.toInt()
    const val BACKGROUND_DARK = 0xFF222224.toInt()

    /** The Pay button: Payriff's brand colour and a white title, 16 sp semibold. */
    const val BUTTON_COLOR = CardFormStyles.FOCUS
    const val BUTTON_TEXT_COLOR = 0xFFFFFFFF.toInt()
    const val BUTTON_TEXT_SIZE_SP = 16f

    /** The secure line and the footer: 12 sp regular in `#76787F`. */
    const val SECONDARY_TEXT_COLOR = 0xFF76787F.toInt()
    const val SECONDARY_TEXT_SIZE_SP = 12f

    /** The Pay button's opacity while disabled (without disabled colours, CONTRACT §18.3), and while pressed. */
    const val DISABLED_ALPHA = 0.5f
    const val PRESSED_ALPHA = 0.8f

    /** The word of `view.poweredBy` that the Payriff logo replaces. */
    const val WORDMARK = "Payriff"

    /**
     * The content top to bottom with the space above each part (CONTRACT §20.1, `wallet.json` → `viewPlacement`). While
     * a wallet is offered ([showsWallet]) the wallet button goes where [PayriffPaymentViewStyle.walletPlacement] says:
     * first, with its divider unless the style hides it, or 12 below the Pay button, where the divider never shows.
     * Then the form, the Pay button, the error line (only while there is one), the badges and the secure line, unless
     * the style hides them. Without a wallet the layout is exactly CONTRACT §16.14. The footer is laid out apart from it
     * ([footer]).
     */
    fun content(style: PayriffPaymentViewStyle, showsError: Boolean, showsWallet: Boolean = false): List<Item> = buildList {
        val placement = style.walletPlacement.takeIf { showsWallet }
        if (placement == PayriffWalletPlacement.TOP) {
            add(Item(Part.WALLET_BUTTON, 0))
            if (style.showWalletDivider) add(Item(Part.WALLET_DIVIDER, WALLET_DIVIDER_GAP_DP))
        }
        add(Item(Part.FORM, if (placement == PayriffWalletPlacement.TOP) WALLET_FORM_GAP_DP else 0))
        add(Item(Part.PAY_BUTTON, BUTTON_GAP_DP))
        if (placement == PayriffWalletPlacement.BELOW_PAY_BUTTON) add(Item(Part.WALLET_BUTTON, WALLET_BELOW_PAY_GAP_DP))
        if (showsError) add(Item(Part.ERROR, ERROR_GAP_DP))
        if (style.showBadges) add(Item(Part.BADGES, BADGES_GAP_DP))
        if (style.showSecureNote) {
            add(Item(Part.SECURE_NOTE, if (style.showBadges) SECURE_NOTE_GAP_DP else SECURE_NOTE_GAP_WITHOUT_BADGES_DP))
        }
    }

    /**
     * Every part of [content] in the order the view keeps its children for [placement]: whichever of them show,
     * [content] lists them in this order. Below Pay the divider, which never shows there, stays first.
     */
    fun parts(placement: PayriffWalletPlacement): List<Part> = when (placement) {
        PayriffWalletPlacement.TOP ->
            listOf(Part.WALLET_BUTTON, Part.WALLET_DIVIDER, Part.FORM, Part.PAY_BUTTON, Part.ERROR, Part.BADGES, Part.SECURE_NOTE)
        PayriffWalletPlacement.BELOW_PAY_BUTTON ->
            listOf(Part.WALLET_DIVIDER, Part.FORM, Part.PAY_BUTTON, Part.WALLET_BUTTON, Part.ERROR, Part.BADGES, Part.SECURE_NOTE)
    }

    /**
     * The wallet button's height in the view, in dp (CONTRACT §20.2, `wallet.json` → `viewWalletHeight`): the wallet
     * style's [styleHeight], else the Pay button's height as laid out ([payButtonHeight]); at least 40.
     */
    fun walletHeight(styleHeight: Float?, payButtonHeight: Float): Float =
        max(GooglePayButtonStyles.MIN_HEIGHT_DP, CardFormStyles.size(styleHeight ?: payButtonHeight))

    /**
     * [walletHeight] in pixels at [density], from the Pay button's measured height in pixels: without [styleHeight],
     * exactly [payButtonHeightPx]; at least 40 dp.
     */
    fun walletHeightPx(styleHeight: Float?, payButtonHeightPx: Int, density: Float): Int {
        val height = if (styleHeight != null) CardFormStyles.px(styleHeight, density) else payButtonHeightPx
        return max(CardFormStyles.px(GooglePayButtonStyles.MIN_HEIGHT_DP, density), height)
    }

    /** The "Powered by Payriff" footer, or `null` when the style hides it. */
    fun footer(style: PayriffPaymentViewStyle): Item? = if (style.showPoweredBy) Item(Part.POWERED_BY, FOOTER_GAP_DP) else null

    /**
     * The Pay button's title: `view.pay` (or the style's [label], trimmed), then the amount with 2 decimals and `.` as
     * the separator, then the currency, for example "Pay 1.02 AZN". Only the word while the amount is unknown. A `null`
     * or blank [label] keeps `view.pay` (CONTRACT §16.14 "Text overrides").
     */
    fun payTitle(language: PayriffLanguage, order: PayriffClientOrder?, label: String? = null): String =
        payTitle(ResolvedStrings(language), order, title = null, label = label)

    /** The Pay button's title for [style] (CONTRACT §18.1): see the four-argument [payTitle]. */
    fun payTitle(texts: ResolvedStrings, order: PayriffClientOrder?, style: PayriffPaymentViewStyle): String =
        payTitle(texts, order, style.payButtonTitle, style.payButtonLabel)

    /**
     * The Pay button's title (CONTRACT §18.1, `strings_override.json` → `payButton`): the whole [title], trimmed, when it
     * is set, with no amount even while it is unknown. Else the word, [label] (trimmed), then
     * [com.payriff.android.PayriffStrings.pay] and `view.pay` ([texts]), followed by the amount with 2 decimals and the
     * currency, or alone while the amount is unknown.
     */
    fun payTitle(texts: ResolvedStrings, order: PayriffClientOrder?, title: String?, label: String?): String {
        title?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val pay = label?.trim()?.takeIf { it.isNotEmpty() } ?: texts.view(CardStrings.VIEW_PAY)
        val amount = order?.amount ?: return pay
        val currency = order.currency?.trim().orEmpty()
        return listOf(pay, amountText(amount), currency).filter { it.isNotEmpty() }.joinToString(" ")
    }

    /**
     * The text of the secure line: the style's [PayriffPaymentViewStyle.secureNoteText] as it is, or `view.secure` in
     * [language] when it is `null` or blank (CONTRACT §16.14 "Text overrides").
     */
    fun secureText(language: PayriffLanguage, style: PayriffPaymentViewStyle): String = secureText(ResolvedStrings(language), style)

    /** [secureText] with [com.payriff.android.PayriffStrings.secureNote] under the style's text (CONTRACT §18.6). */
    fun secureText(texts: ResolvedStrings, style: PayriffPaymentViewStyle): String =
        style.secureNoteText?.takeIf { it.isNotBlank() } ?: texts.view(CardStrings.VIEW_SECURE)

    /**
     * The text of the wallet divider: the style's [PayriffPaymentViewStyle.walletDividerText], trimmed, or
     * `view.orPayByCard` in [language] when it is `null` or blank (CONTRACT §17.9).
     */
    fun walletDividerText(language: PayriffLanguage, style: PayriffPaymentViewStyle): String =
        walletDividerText(ResolvedStrings(language), style)

    /** [walletDividerText] with [com.payriff.android.PayriffStrings.orPayByCard] under the style's text (CONTRACT §18.6). */
    fun walletDividerText(texts: ResolvedStrings, style: PayriffPaymentViewStyle): String =
        style.walletDividerText?.trim()?.takeIf { it.isNotEmpty() } ?: texts.view(CardStrings.VIEW_OR_PAY_BY_CARD)

    /**
     * The Pay button's colours (CONTRACT §16.14, §18.3): [background] and [text] while enabled; [disabledBackground] and
     * [disabledText] while disabled when the style sets a disabled colour, else `null` (the enabled colours at 50%).
     */
    class ButtonColors(
        @ColorInt val background: Int,
        @ColorInt val text: Int,
        @ColorInt val disabledBackground: Int?,
        @ColorInt val disabledText: Int?,
    )

    /** How the Pay button is drawn in one state: its fill, its title's (and spinner's) colour and its opacity. */
    data class ButtonLook(@ColorInt val background: Int, @ColorInt val text: Int, val alpha: Float)

    /** The Pay button's colours for [style]: see [ButtonColors]. */
    fun buttonColors(style: PayriffPaymentViewStyle): ButtonColors {
        val background = style.buttonColor ?: BUTTON_COLOR
        val text = buttonText(style).color
        val custom = style.buttonDisabledColor != null || style.buttonDisabledTextColor != null
        return ButtonColors(
            background = background,
            text = text,
            disabledBackground = if (custom) style.buttonDisabledColor ?: background else null,
            disabledText = if (custom) style.buttonDisabledTextColor ?: text else null,
        )
    }

    /**
     * The Pay button in a state: [disabled] (also while paying) at full opacity in the disabled colours when the style sets
     * them, else at 50%; [pressed] at 80%; otherwise as it is (CONTRACT §16.14, §18.3).
     */
    fun buttonLook(colors: ButtonColors, disabled: Boolean, pressed: Boolean): ButtonLook = when {
        disabled && colors.disabledBackground != null && colors.disabledText != null ->
            ButtonLook(colors.disabledBackground, colors.disabledText, 1f)
        disabled -> ButtonLook(colors.background, colors.text, DISABLED_ALPHA)
        pressed -> ButtonLook(colors.background, colors.text, PRESSED_ALPHA)
        else -> ButtonLook(colors.background, colors.text, 1f)
    }

    /** The corner smoothing of the Pay button and the badges, 0…1 (CONTRACT §18.2). */
    fun cornerSmoothing(style: PayriffPaymentViewStyle): Float = Squircle.clamp(style.cornerSmoothing)

    /** The colour of the badges' frames (CONTRACT §18.4). */
    @ColorInt
    fun badgeBorderColor(style: PayriffPaymentViewStyle): Int = style.badgeBorderColor ?: BADGE_BORDER_COLOR

    /** What the view's form takes from [style] where its own style leaves it out (CONTRACT §18.2, §18.4). */
    fun formDefaults(style: PayriffPaymentViewStyle): FormDefaults = FormDefaults(cornerSmoothing(style), style.badgeBorderColor)

    /** The Pay button's title style: [PayriffPaymentViewStyle.buttonTextStyle] merged over 16 sp semibold, white. */
    fun buttonText(style: PayriffPaymentViewStyle): ResolvedTextStyle = CardFormStyles.resolveText(
        style.buttonTextStyle,
        ResolvedTextStyle(BUTTON_TEXT_SIZE_SP, PayriffTextStyle.SEMIBOLD, null, BUTTON_TEXT_COLOR),
    )

    /** An amount as on Payriff's payment page: 2 decimals, `.` as the separator, no grouping, rounded half up. */
    fun amountText(amount: BigDecimal): String = amount.setScale(2, RoundingMode.HALF_UP).toPlainString()

    /** The error line of an order that loaded but cannot be paid by card: `view.notPayable` in [language]. */
    fun notPayableMessage(language: PayriffLanguage): String = CardStrings.view(language, CardStrings.VIEW_NOT_PAYABLE)

    /** The error line on a compromised device (CONTRACT §16.16): `view.deviceNotSupported` in [language]. */
    fun deviceNotSupportedMessage(language: PayriffLanguage): String = CardStrings.view(language, CardStrings.VIEW_DEVICE_NOT_SUPPORTED)

    /**
     * `view.poweredBy` split at "Payriff", where the logo goes: the text before and after it, for example
     * ("Powered by ", "") or ("", " tərəfindən hazırlanıb"). `null` when the copy has no "Payriff".
     */
    fun poweredByParts(language: PayriffLanguage): Pair<String, String>? {
        val text = CardStrings.view(language, CardStrings.VIEW_POWERED_BY)
        return if (WORDMARK in text) poweredByParts(text) else null
    }

    /**
     * The footer's [text] split at its first "Payriff", where the logo goes (CONTRACT §18.6, `strings_override.json` →
     * `poweredBy`). A text without "Payriff" is followed by a space and the logo: ("Secure payment ", "").
     */
    fun poweredByParts(text: String): Pair<String, String> {
        val at = text.indexOf(WORDMARK)
        if (at < 0) return "$text " to ""
        return text.substring(0, at) to text.substring(at + WORDMARK.length)
    }
}

/**
 * The padlock before the secure line of [com.payriff.android.PayriffPaymentView], drawn in code: a shackle, a body with
 * rounded corners and a keyhole dot. Every value is a fraction of the side of the square the lock is drawn in. Pure
 * Kotlin, so the JVM unit tests check the geometry.
 */
internal object LockGlyph {

    /** The colour of every part. */
    const val COLOR = 0xFF56BE3D.toInt()

    /** The width of the shackle's and the body's lines, with round caps and joins. Thin, for the small size. */
    const val STROKE_WIDTH = 0.085f

    /**
     * The shackle: a line up from ([SHACKLE_LEFT], [SHACKLE_BOTTOM]) to [SHACKLE_CENTRE_Y], a half circle of
     * [SHACKLE_RADIUS] around ([SHACKLE_CENTRE_X], [SHACKLE_CENTRE_Y]) over the top, and a line down to
     * ([SHACKLE_RIGHT], [SHACKLE_BOTTOM]).
     */
    const val SHACKLE_LEFT = 0.32f
    const val SHACKLE_RIGHT = 0.68f
    const val SHACKLE_BOTTOM = 0.45f
    const val SHACKLE_CENTRE_X = 0.5f
    const val SHACKLE_CENTRE_Y = 0.31f
    const val SHACKLE_RADIUS = 0.18f

    /** The body: an outlined rectangle with rounded corners. */
    const val BODY_LEFT = 0.17f
    const val BODY_TOP = 0.45f
    const val BODY_RIGHT = 0.83f
    const val BODY_BOTTOM = 0.92f
    const val BODY_CORNER_RADIUS = 0.13f

    /** The keyhole: a filled dot inside the body. */
    const val KEYHOLE_X = 0.5f
    const val KEYHOLE_Y = 0.665f
    const val KEYHOLE_RADIUS = 0.075f
}

/**
 * The images of [com.payriff.android.PayriffPaymentView] (CONTRACT §16.14), from `spec/assets/payment-view`: the 1x, 2x
 * and 3x PNGs as `drawable-mdpi`, `-xhdpi` and `-xxhdpi`, and a 1.5x `drawable-hdpi` PNG made from the 3x one. The
 * badges are the frameless marks (`visa-mark`, `mastercard-mark`) on the canvas of the old framed badges; the view draws
 * their frames in code (CONTRACT §18.4).
 *
 * @property resourceName The drawable's name, the same in every density folder.
 * @property widthDp The size in dp (the mdpi file's size in pixels).
 * @property accessibilityLabel What accessibility services read, or `null` for decoration.
 */
internal enum class PaymentViewAsset(
    @DrawableRes val resId: Int,
    val resourceName: String,
    val widthDp: Int,
    val heightDp: Int,
    val accessibilityLabel: String?,
) {
    MASTERCARD(R.drawable.payriff_badge_mastercard_mark, "payriff_badge_mastercard_mark", 43, 28, "Mastercard"),
    VISA(R.drawable.payriff_badge_visa_mark, "payriff_badge_visa_mark", 42, 28, "Visa"),

    /** The "Powered by" logo in the light theme, from the hosted page. */
    LOGO(R.drawable.payriff_logo, "payriff_logo", 82, 16, "Payriff"),

    /** The same logo for the dark theme: the wordmark in `#E1E4E8` instead of `#005687`, the mark unchanged. */
    LOGO_DARK(R.drawable.payriff_logo_dark, "payriff_logo_dark", 82, 16, "Payriff"),
    ;

    companion object {
        /**
         * The badge row: the brands the SDK accepts. The hosted page's other methods are left out, and so is its PCI
         * logo: PCI SSC does not allow the "PCI ✓" mark (CONTRACT §16.14).
         */
        val BADGES: List<PaymentViewAsset> = listOf(MASTERCARD, VISA)

        /** The footer's logo: [LOGO] in the light theme, [LOGO_DARK] in the dark one ([night]). */
        fun logo(night: Boolean): PaymentViewAsset = if (night) LOGO_DARK else LOGO
    }
}

/**
 * The `ActivityResultRegistry` keys of the payment views' launchers (3-D Secure and Google Pay) and of the standalone
 * Google Pay buttons. A view's key comes from its id, so the view with the same id in a recreated activity receives the
 * result; two views attached to one registry at the same time never share a key. Main thread only.
 */
internal object PaymentViewKeys {

    /** The prefix of [com.payriff.android.PayriffPaymentView]'s keys. */
    const val PAYMENT_VIEW = "com.payriff.android.PayriffPaymentView:"

    /** The prefix of [com.payriff.android.PayriffGooglePayButton]'s keys. */
    const val GOOGLE_PAY_BUTTON = "com.payriff.android.PayriffGooglePayButton:"

    private const val NO_ID = -1

    private val inUse = WeakHashMap<Any, MutableSet<String>>()

    /** The key of the view with [viewId] (`View.NO_ID` without one), with the [prefix] of its kind of view. */
    fun base(viewId: Int, prefix: String = PAYMENT_VIEW): String = prefix + if (viewId == NO_ID) "default" else viewId.toString()

    /** Takes [base] in [registry], or `base#2`, `base#3` … while another attached view holds it. */
    fun acquire(registry: Any, base: String): String {
        val keys = inUse.getOrPut(registry) { HashSet() }
        var key = base
        var suffix = 2
        while (key in keys) key = "$base#${suffix++}"
        keys += key
        return key
    }

    /** Gives [key] back when its view leaves the window. */
    fun release(registry: Any, key: String) {
        val keys = inUse[registry] ?: return
        keys -= key
        if (keys.isEmpty()) inUse.remove(registry)
    }
}

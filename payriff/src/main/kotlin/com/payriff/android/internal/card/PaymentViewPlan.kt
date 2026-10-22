package com.payriff.android.internal.card

import androidx.annotation.DrawableRes
import com.payriff.android.PayriffClientOrder
import com.payriff.android.PayriffLanguage
import com.payriff.android.PayriffPaymentViewStyle
import com.payriff.android.R
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.WeakHashMap

/**
 * What [com.payriff.android.PayriffPaymentView] shows, top to bottom, and its copy (CONTRACT §16.14). Pure Kotlin, so
 * the JVM unit tests check the structure the view is built from.
 */
internal object PaymentViewPlan {

    /** A part of the view. */
    enum class Part { FORM, PAY_BUTTON, ERROR, BADGES, SECURE_NOTE, POWERED_BY }

    /** A part and the space above it, in dp. */
    data class Item(val part: Part, val gapAboveDp: Int)

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

    /** Between the lock and the secure text. */
    const val LOCK_GAP_DP = 6

    /** The lock before the secure text, 15×15, drawn in code ([LockGlyph]). */
    const val LOCK_SIZE_DP = 15

    /** The Pay button's spinner and the space around its title. */
    const val SPINNER_SIZE_DP = 20
    const val BUTTON_PADDING_HORIZONTAL_DP = 16
    const val BUTTON_PADDING_VERTICAL_DP = 8

    /** The page behind everything. */
    const val BACKGROUND_LIGHT = 0xFFFCFCFD.toInt()
    const val BACKGROUND_DARK = 0xFF222224.toInt()

    /** The Pay button: Payriff's brand colour and a white title. */
    const val BUTTON_COLOR = CardFormStyles.FOCUS
    const val BUTTON_TEXT_COLOR = 0xFFFFFFFF.toInt()

    /** The secure line and the footer: 12 sp regular in `#76787F`. */
    const val SECONDARY_TEXT_COLOR = 0xFF76787F.toInt()
    const val SECONDARY_TEXT_SIZE_SP = 12f

    /** The Pay button's opacity while disabled, and while pressed. */
    const val DISABLED_ALPHA = 0.5f
    const val PRESSED_ALPHA = 0.8f

    /** The error line of an order that loaded but cannot be paid by card. */
    const val NOT_PAYABLE_MESSAGE = "This order cannot be paid"

    /** The word of `view.poweredBy` that the Payriff logo replaces. */
    const val WORDMARK = "Payriff"

    /**
     * The content top to bottom with the space above each part: the form, the Pay button, the error line (only while
     * there is one), the badges and the secure line, unless the style hides them. The footer is laid out apart from
     * it ([footer]).
     */
    fun content(style: PayriffPaymentViewStyle, showsError: Boolean): List<Item> = buildList {
        add(Item(Part.FORM, 0))
        add(Item(Part.PAY_BUTTON, BUTTON_GAP_DP))
        if (showsError) add(Item(Part.ERROR, ERROR_GAP_DP))
        if (style.showBadges) add(Item(Part.BADGES, BADGES_GAP_DP))
        if (style.showSecureNote) {
            add(Item(Part.SECURE_NOTE, if (style.showBadges) SECURE_NOTE_GAP_DP else SECURE_NOTE_GAP_WITHOUT_BADGES_DP))
        }
    }

    /** The "Powered by Payriff" footer, or `null` when the style hides it. */
    fun footer(style: PayriffPaymentViewStyle): Item? = if (style.showPoweredBy) Item(Part.POWERED_BY, FOOTER_GAP_DP) else null

    /**
     * The Pay button's title: `view.pay`, then the amount with 2 decimals and `.` as the separator, then the currency,
     * for example "Pay 1.02 AZN". Only `view.pay` while the amount is unknown.
     */
    fun payTitle(language: PayriffLanguage, order: PayriffClientOrder?): String {
        val pay = CardStrings.view(language, CardStrings.VIEW_PAY)
        val amount = order?.amount ?: return pay
        val currency = order.currency?.trim().orEmpty()
        return listOf(pay, amountText(amount), currency).filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** An amount as on Payriff's payment page: 2 decimals, `.` as the separator, no grouping, rounded half up. */
    fun amountText(amount: BigDecimal): String = amount.setScale(2, RoundingMode.HALF_UP).toPlainString()

    /**
     * `view.poweredBy` split at "Payriff", where the logo goes: the text before and after it, for example
     * ("Powered by ", "") or ("", " tərəfindən hazırlanıb"). `null` when the copy has no "Payriff".
     */
    fun poweredByParts(language: PayriffLanguage): Pair<String, String>? {
        val text = CardStrings.view(language, CardStrings.VIEW_POWERED_BY)
        val at = text.indexOf(WORDMARK)
        if (at < 0) return null
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
 * The images of [com.payriff.android.PayriffPaymentView] (CONTRACT §16.14), from Payriff's hosted payment page:
 * `spec/assets/payment-view` as `drawable-mdpi`, `-xhdpi` and `-xxhdpi` PNGs (1x, 2x, 3x).
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
    MASTERCARD(R.drawable.payriff_badge_mastercard, "payriff_badge_mastercard", 43, 28, "Mastercard"),
    VISA(R.drawable.payriff_badge_visa, "payriff_badge_visa", 42, 28, "Visa"),
    LOGO(R.drawable.payriff_logo, "payriff_logo", 82, 16, "Payriff"),
    ;

    companion object {
        /**
         * The badge row: the brands the SDK accepts. The hosted page's other methods are left out, and so is its PCI
         * logo: PCI SSC does not allow the "PCI ✓" mark (CONTRACT §16.14).
         */
        val BADGES: List<PaymentViewAsset> = listOf(MASTERCARD, VISA)
    }
}

/**
 * The `ActivityResultRegistry` keys of the payment views' 3-D Secure launchers. A view's key comes from its id, so the
 * view with the same id in a recreated activity receives the result; two views attached to one registry at the same
 * time never share a key. Main thread only.
 */
internal object PaymentViewKeys {

    private const val PREFIX = "com.payriff.android.PayriffPaymentView:"
    private const val NO_ID = -1

    private val inUse = WeakHashMap<Any, MutableSet<String>>()

    /** The key of the view with [viewId] (`View.NO_ID` without one). */
    fun base(viewId: Int): String = PREFIX + if (viewId == NO_ID) "default" else viewId.toString()

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

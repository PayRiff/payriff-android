package com.payriff.android.internal.card

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContract
import com.payriff.android.PayriffLanguage
import com.payriff.android.PayriffResult
import com.payriff.android.PayriffStrings
import com.payriff.android.internal.BundleKeyValues
import com.payriff.android.internal.Languages
import com.payriff.android.internal.PaymentIntents
import com.payriff.android.internal.PayriffActivity

/**
 * What the 3DS sheet needs to run, and to resume with a status check after process death: the order id and its
 * `clientSecret`. The next action itself (with the bank's `creq` or `threeDSMethodData`) never enters the
 * Intent; it stays in memory ([CardHandoffs]). Card data never reaches the sheet at all.
 *
 * @property strings The texts of the payment view that opens the sheet ([com.payriff.android.PayriffPaymentView.strings],
 *   CONTRACT §18.6), or `null`; the sheet resolves them over [com.payriff.android.PayriffConfig.strings].
 * @property rtl Whether the screen that opens the sheet is right-to-left (CONTRACT §18.7), or `null` when unknown; the
 *   sheet then follows its own configuration.
 */
internal class CardSheetRequest(
    val flowId: String,
    val orderId: String,
    val clientSecret: String,
    val language: PayriffLanguage?,
    val strings: PayriffStrings? = null,
    val rtl: Boolean? = null,
) {
    /**
     * The sheet's layout direction in its effective [language] (CONTRACT §18.7, §19.3): right-to-left when the screen
     * that opened it is, or in Arabic; else that screen's left-to-right, or the activity's own when it is unknown.
     */
    fun layoutDirection(language: PayriffLanguage): Int = when {
        rtl == true || Languages.isRightToLeft(language) -> View.LAYOUT_DIRECTION_RTL
        rtl == false -> View.LAYOUT_DIRECTION_LTR
        else -> View.LAYOUT_DIRECTION_INHERIT
    }

    override fun toString(): String = "CardSheetRequest(flowId=$flowId, orderId=$orderId)"
}

/** Intents of [PayriffActivity], the 3DS sheet. */
internal object CardSheetIntents {
    private const val EXTRA_MODE = "payriff.mode"
    private const val MODE_CARD_3DS = "card3ds"
    private const val EXTRA_ORDER_ID = "payriff.card.orderId"
    private const val EXTRA_CLIENT_SECRET = "payriff.card.clientSecret"
    private const val EXTRA_LANGUAGE = "payriff.card.language"
    private const val EXTRA_LAYOUT_DIRECTION = "payriff.card.layoutDirection"
    private const val RTL = "rtl"
    private const val LTR = "ltr"

    fun create(context: Context, request: CardSheetRequest): Intent {
        val extras = Bundle()
        extras.putString(EXTRA_MODE, MODE_CARD_3DS)
        extras.putString(PaymentIntents.EXTRA_FLOW_ID, request.flowId)
        extras.putString(EXTRA_ORDER_ID, request.orderId)
        extras.putString(EXTRA_CLIENT_SECRET, request.clientSecret)
        extras.putString(EXTRA_LANGUAGE, request.language?.name)
        request.rtl?.let { extras.putString(EXTRA_LAYOUT_DIRECTION, if (it) RTL else LTR) }
        request.strings?.let { SheetStringsCodec.write(it, BundleKeyValues(extras)) }
        return Intent(context, PayriffActivity::class.java).putExtras(extras)
    }

    /** The card request of [intent], or `null` when it is not a 3DS-mode intent. */
    fun read(intent: Intent?): CardSheetRequest? {
        val extras = intent?.extras ?: return null
        if (extras.getString(EXTRA_MODE) != MODE_CARD_3DS) return null
        return CardSheetRequest(
            flowId = extras.getString(PaymentIntents.EXTRA_FLOW_ID) ?: return null,
            orderId = extras.getString(EXTRA_ORDER_ID) ?: return null,
            clientSecret = extras.getString(EXTRA_CLIENT_SECRET) ?: return null,
            language = extras.getString(EXTRA_LANGUAGE)?.let { name -> PayriffLanguage.entries.firstOrNull { it.name == name } },
            strings = SheetStringsCodec.read(BundleKeyValues(extras)),
            rtl = when (extras.getString(EXTRA_LAYOUT_DIRECTION)) {
                RTL -> true
                LTR -> false
                else -> null
            },
        )
    }
}

/** Next actions handed from the launcher to the 3DS sheet, in memory only: process death drops them on purpose. */
internal object CardHandoffs {
    private val actions = HashMap<String, NextAction>()

    @Synchronized
    fun put(flowId: String, action: NextAction) {
        actions[flowId] = action
    }

    @Synchronized
    fun take(flowId: String): NextAction? = actions.remove(flowId)
}

/** Starts the 3DS sheet; `null` when it ended without a result (the launcher then reports `pending(notFinal)`). */
internal class CardSheetContract : ActivityResultContract<CardSheetRequest, PayriffResult?>() {
    override fun createIntent(context: Context, input: CardSheetRequest): Intent = CardSheetIntents.create(context, input)

    override fun parseResult(resultCode: Int, intent: Intent?): PayriffResult? =
        if (resultCode == Activity.RESULT_OK) PaymentIntents.readResult(intent) else null
}

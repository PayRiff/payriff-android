package com.payriff.android.internal.card

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContract
import com.payriff.android.PayriffLanguage
import com.payriff.android.PayriffResult
import com.payriff.android.internal.PaymentIntents
import com.payriff.android.internal.PayriffActivity

/**
 * What the 3DS sheet needs to run, and to resume with a status check after process death: the order id and its
 * `clientSecret`. The next action itself (with the bank's `creq` or `threeDSMethodData`) never enters the
 * Intent; it stays in memory ([CardHandoffs]). Card data never reaches the sheet at all.
 */
internal class CardSheetRequest(
    val flowId: String,
    val orderId: String,
    val clientSecret: String,
    val language: PayriffLanguage?,
) {
    override fun toString(): String = "CardSheetRequest(flowId=$flowId, orderId=$orderId)"
}

/** Intents of [PayriffActivity], the 3DS sheet. */
internal object CardSheetIntents {
    private const val EXTRA_MODE = "payriff.mode"
    private const val MODE_CARD_3DS = "card3ds"
    private const val EXTRA_ORDER_ID = "payriff.card.orderId"
    private const val EXTRA_CLIENT_SECRET = "payriff.card.clientSecret"
    private const val EXTRA_LANGUAGE = "payriff.card.language"

    fun create(context: Context, request: CardSheetRequest): Intent {
        val extras = Bundle()
        extras.putString(EXTRA_MODE, MODE_CARD_3DS)
        extras.putString(PaymentIntents.EXTRA_FLOW_ID, request.flowId)
        extras.putString(EXTRA_ORDER_ID, request.orderId)
        extras.putString(EXTRA_CLIENT_SECRET, request.clientSecret)
        extras.putString(EXTRA_LANGUAGE, request.language?.name)
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

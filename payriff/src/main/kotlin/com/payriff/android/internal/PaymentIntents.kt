package com.payriff.android.internal

import android.content.Intent
import android.os.Bundle
import com.payriff.android.PayriffResult

/** Extras shared by the 3-D Secure sheet's intents ([PayriffActivity]) and its result intent. */
internal object PaymentIntents {

    const val EXTRA_FLOW_ID = "payriff.flowId"

    fun resultIntent(result: PayriffResult): Intent {
        val extras = Bundle()
        ResultCodec.write(result, BundleKeyValues(extras))
        return Intent().putExtras(extras)
    }

    fun readResult(intent: Intent?): PayriffResult? =
        intent?.extras?.let { ResultCodec.read(BundleKeyValues(it)) }
}

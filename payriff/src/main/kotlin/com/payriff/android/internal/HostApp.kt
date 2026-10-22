package com.payriff.android.internal

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ApplicationInfo
import android.database.Cursor
import android.net.Uri
import androidx.annotation.VisibleForTesting
import com.payriff.android.Payriff
import com.payriff.android.PayriffConfig

/**
 * Facts about the host app that the SDK needs before a payment starts:
 * - whether the app is debuggable: the HTTP details of the logs (CONTRACT §11) and the debug-only opt-out of screen
 *   protection (§16.0 item 4);
 * - its package name: the `X-Client-App-Id` of the Client API (§16.2).
 *
 * [PayriffInitProvider] records them when the app process starts, before `Application.onCreate`, so
 * [Payriff.retrieveOrder] knows them without a `Context`. Every card payment records them again, which also covers
 * apps that removed the provider from their merged manifest.
 */
internal object HostApp {

    /** Whether the host app is debuggable (`ApplicationInfo.FLAG_DEBUGGABLE`), or `null` while no `Context` was seen. */
    @Volatile
    var debuggable: Boolean? = null
        private set

    /** The host app's package name, or `null` while no `Context` was seen. */
    @Volatile
    var packageName: String? = null
        private set

    /** Records the facts of the app that owns [context]. */
    fun update(context: Context) {
        val app = context.applicationContext ?: context
        packageName = app.packageName
        debuggable = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    /** Replaces the recorded facts. Only for JVM unit tests, where no `Context` exists. */
    @VisibleForTesting
    fun setForTests(debuggable: Boolean?, packageName: String? = null) {
        this.debuggable = debuggable
        this.packageName = packageName
    }
}

/**
 * CONTRACT §16.0 item 4: whether the SDK's windows are protected with `FLAG_SECURE`. Only a debuggable app may opt
 * out, with [PayriffConfig.allowScreenCaptureInDebug]; an app of unknown debuggability counts as a release build.
 */
internal fun screenProtectionOn(config: PayriffConfig, debuggable: Boolean? = HostApp.debuggable): Boolean =
    !(debuggable == true && config.allowScreenCaptureInDebug)

/**
 * Records [HostApp] when the app process starts (content providers are created before `Application.onCreate`).
 * It is not exported, stores nothing and answers every request with nothing. If an app removes it from the merged
 * manifest, the facts are recorded at the first card payment instead; until then the SDK treats the app as a
 * release build.
 */
internal class PayriffInitProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        try {
            context?.let(Payriff::onContextAvailable)
        } catch (ignored: RuntimeException) {
            // Never break the host app's start-up; the facts are recorded again at the first card payment.
        }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

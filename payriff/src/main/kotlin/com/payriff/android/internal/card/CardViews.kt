package com.payriff.android.internal.card

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.view.AbsSavedState
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.autofill.AutofillValue
import android.widget.EditText
import androidx.annotation.RequiresApi
import androidx.core.view.get
import androidx.core.view.size
import java.util.WeakHashMap

/**
 * An input of [com.payriff.android.PayriffCardForm] (CONTRACT §16.5). It never saves its text in the instance state
 * (card data must not reach the system's saved state), and with [blocksCopy] it refuses copy, cut, share, text
 * services and drag-out, while paste stays possible.
 */
internal class CardEditText(context: Context) : EditText(context) {

    /** Number and CVV: no copy, cut, share, text-processing actions or dragging the selection out. */
    var blocksCopy: Boolean = false
        set(value) {
            field = value
            customSelectionActionModeCallback = if (value) PasteOnlyActionMode else null
            customInsertionActionModeCallback = if (value) PasteOnlyActionMode else null
        }

    /** `true` while the autofill framework fills the field; such edits never auto-advance (CONTRACT §16.4). */
    var isAutofilling: Boolean = false
        private set

    init {
        isSaveEnabled = false
    }

    override fun getFreezesText(): Boolean = false

    override fun onSaveInstanceState(): Parcelable? {
        // The framework never calls this (saving is disabled); this keeps any explicit call free of card data too.
        super.onSaveInstanceState()
        return AbsSavedState.EMPTY_STATE
    }

    override fun onTextContextMenuItem(id: Int): Boolean {
        // Also covers keyboard shortcuts (Ctrl+C / Ctrl+X), which bypass the action mode.
        if (blocksCopy && id != android.R.id.paste && id != android.R.id.pasteAsPlainText && id != android.R.id.selectAll) {
            return true
        }
        return super.onTextContextMenuItem(id)
    }

    override fun performLongClick(): Boolean {
        // A long press on a selection starts a drag that can carry the text into another app.
        if (blocksCopy && hasSelection()) return true
        return super.performLongClick()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun autofill(value: AutofillValue) {
        isAutofilling = true
        try {
            super.autofill(value)
        } finally {
            isAutofilling = false
        }
    }

    /** Keeps only paste and select-all in the selection and insertion menus. */
    private object PasteOnlyActionMode : ActionMode.Callback {
        private val ALLOWED = setOf(android.R.id.paste, android.R.id.pasteAsPlainText, android.R.id.selectAll)

        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean = strip(menu)

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = strip(menu)

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean = item.itemId !in ALLOWED

        override fun onDestroyActionMode(mode: ActionMode) = Unit

        private fun strip(menu: Menu): Boolean {
            for (i in 0 until menu.size) {
                val item = menu[i]
                if (item.itemId !in ALLOWED) {
                    item.isVisible = false
                    item.isEnabled = false
                }
            }
            return true
        }
    }
}

/**
 * A secure flag counted per window (CONTRACT §16.0 item 4): set while at least one holder needs it, and removed again
 * only where it was added, so a window the app had already made secure stays secure. Main thread only.
 */
internal class SecureFlagHolds<K : Any>(
    private val isSecure: (K) -> Boolean,
    private val setSecure: (K, Boolean) -> Unit,
) {
    private class Hold(var count: Int, val added: Boolean)

    private val holds = WeakHashMap<K, Hold>()

    fun acquire(key: K) {
        holds[key]?.let {
            it.count++
            return
        }
        val added = !isSecure(key)
        if (added) setSecure(key, true)
        holds[key] = Hold(1, added)
    }

    fun release(key: K) {
        val hold = holds[key] ?: return
        hold.count--
        if (hold.count > 0) return
        holds.remove(key)
        if (hold.added) setSecure(key, false)
    }

    fun isHeld(key: K): Boolean = holds.containsKey(key)
}

/**
 * `FLAG_SECURE` while at least one card form is attached (CONTRACT §16.0 item 4), on every window that shows it:
 * - the window of the form's activity (screenshots, recordings and the recents thumbnail);
 * - the form's own window when that is another one: a dialog, a bottom sheet, a popup. Each window has its own flag, so
 *   the activity's flag alone leaves a form in a `BottomSheetDialogFragment` open to screenshots and screen sharing.
 *
 * The flag is removed again only where the SDK added it.
 */
internal object SecureWindows {

    /** What [protect] turned on; pass it to [release]. */
    class Protection(val window: Window?, val root: View?)

    private val windows = SecureFlagHolds<Window>(
        isSecure = { (it.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0 },
        setSecure = { window, on ->
            if (on) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        },
    )

    /** Root views of windows that are not an activity's (dialogs, bottom sheets, popups). */
    private val roots = SecureFlagHolds<View>(
        isSecure = { root ->
            val flags = (root.layoutParams as? WindowManager.LayoutParams)?.flags ?: 0
            (flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
        },
        setSecure = { root, on -> if (on) updateRoot(root, secure = true) else clearRootLater(root) },
    )

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** Protects the windows that show [view], which must be attached. */
    fun protect(view: View): Protection {
        val window = windowOf(view.context)
        val root = view.rootView.takeIf { it.layoutParams is WindowManager.LayoutParams && it !== window?.peekDecorView() }
        window?.let(windows::acquire)
        root?.let(roots::acquire)
        return Protection(window, root)
    }

    fun release(protection: Protection) {
        protection.window?.let(windows::release)
        protection.root?.let(roots::release)
    }

    /**
     * The form left a dialog or popup window. Usually that window is being removed right now; it is updated on the next
     * main-loop turn, and only if it is still on screen and no other form took it meanwhile.
     */
    private fun clearRootLater(root: View) {
        mainHandler.post {
            if (root.isAttachedToWindow && !roots.isHeld(root)) updateRoot(root, secure = false)
        }
    }

    private fun updateRoot(root: View, secure: Boolean) {
        val params = root.layoutParams as? WindowManager.LayoutParams ?: return
        val flags = if (secure) {
            params.flags or WindowManager.LayoutParams.FLAG_SECURE
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
        }
        if (flags == params.flags) return
        params.flags = flags
        try {
            (root.context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.updateViewLayout(root, params)
        } catch (e: RuntimeException) {
            // The window went away meanwhile: nothing left to protect or restore.
        }
    }

    /** The window of the activity that hosts [context], if any. */
    fun windowOf(context: Context): Window? {
        var current: Context? = context
        while (current != null) {
            if (current is Activity) return current.window
            current = (current as? ContextWrapper)?.baseContext
        }
        return null
    }
}

package com.payriff.example.android

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/** Pads [root] for the system bars, display cutout and keyboard (the example draws edge to edge). */
fun applySystemInsets(root: View) {
    val start = root.paddingLeft
    val top = root.paddingTop
    val end = root.paddingRight
    val bottom = root.paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime(),
        )
        view.updatePadding(left = start + bars.left, top = top + bars.top, right = end + bars.right, bottom = bottom + bars.bottom)
        WindowInsetsCompat.CONSUMED
    }
}

/**
 * Stops the window insets at [view]. A bottom sheet or a dialog makes room for the system bars and the keyboard itself,
 * so the payment view inside it must not add them again.
 */
fun consumeSystemInsets(view: View) {
    ViewCompat.setOnApplyWindowInsetsListener(view) { _, _ -> WindowInsetsCompat.CONSUMED }
}

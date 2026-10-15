package com.unrealstar.live

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Adds the status-bar inset on top of a view's own padding.
 *
 * Only correct when the window is actually edge-to-edge. Without
 * `enableEdgeToEdge()` the platform already lays the window out below the
 * status bar, so adding the inset again would double-count it.
 */
fun View.applyTopSystemInset() {
    val basePadding = paddingTop
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        view.updatePadding(top = basePadding + bars.top)
        insets
    }
}
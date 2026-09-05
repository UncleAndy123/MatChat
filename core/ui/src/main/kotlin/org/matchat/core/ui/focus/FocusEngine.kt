package org.matchat.core.ui.focus

import android.view.View
import android.view.ViewGroup

/**
 * Deterministic focus helpers used by every screen and asserted by the key-only
 * traversal test (PLAN.md §8.1). Focus order follows XML order; a screen sets
 * initial focus in [onContentViewCreated] and restores it after returning from a
 * child screen (AGENTS.md §4).
 */
object FocusEngine {

    /** Give [target] focus now, or on the next layout pass if it is not yet laid out. */
    fun requestInitialFocus(target: View?) {
        target ?: return
        if (!target.requestFocus()) {
            target.post { target.requestFocus() }
        }
    }

    /**
     * Every focusable descendant, in traversal (XML) order. The traversal test
     * walks this list; a screen whose declared interactive elements are not all
     * reachable fails it.
     */
    fun focusableChildren(root: ViewGroup): List<View> {
        val out = ArrayList<View>()
        fun walk(v: View) {
            if (v.isFocusable && v.visibility == View.VISIBLE) out += v
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(root)
        return out
    }

    /** True when nothing focusable has a zero size — a focus trap the test forbids. */
    fun noZeroSizeFocusables(root: ViewGroup): Boolean =
        focusableChildren(root).none { it.width == 0 || it.height == 0 }
}

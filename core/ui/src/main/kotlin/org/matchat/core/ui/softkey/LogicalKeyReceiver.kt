package org.matchat.core.ui.softkey

import org.matchat.core.ui.key.LogicalKey

/**
 * Implemented by whatever currently owns the screen (a [SoftkeyFragment]). The
 * single global key dispatcher in :app maps raw events to [LogicalKey] and hands
 * them here; return true when consumed.
 */
interface LogicalKeyReceiver {
    fun onLogicalKey(key: LogicalKey): Boolean
}

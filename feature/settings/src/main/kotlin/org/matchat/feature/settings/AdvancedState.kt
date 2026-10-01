package org.matchat.feature.settings

import org.matchat.core.model.background.HelperStatus

/** Settings > Advanced (S25; docs/adr/0007, 0008). The softkey swap toggle, then
 *  the two system-granted settings that decide how MatChat stays connected:
 *  "Run in background" (battery exemption) and the background helper. */
data class AdvancedState(
    val softkeysSwapped: Boolean = false,
    val batteryExempt: Boolean = false,
    val helperStatus: HelperStatus = HelperStatus.OFF,
)

sealed interface AdvancedAction {
    data object ToggleSoftkeysSwapped : AdvancedAction

    /** The screen is visible again (e.g. back from a system screen): re-read
     *  the system-granted settings. */
    data object Refresh : AdvancedAction
}

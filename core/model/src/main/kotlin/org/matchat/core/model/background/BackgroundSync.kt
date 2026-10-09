package org.matchat.core.model.background

import kotlinx.coroutines.flow.StateFlow

/**
 * What a screen may know about how MatChat stays connected in the background
 * (docs/adr/0008). Implemented in `:app`, which owns the sync hosts; screens only
 * read it, so a ViewModel never touches `PowerManager` or a Service.
 *
 * Neither input is something the app can set for itself: the battery exemption
 * is granted in a system dialog, the background helper (the accessibility
 * service) in system Accessibility settings. Battery exemption has no change
 * broadcast, so screens call [refresh] when they return to the foreground.
 */
interface BackgroundSync {
    val status: StateFlow<BackgroundSyncStatus>

    /** Re-read the battery-exemption state (after returning from a system screen). */
    fun refresh()
}

/**
 * @property batteryExempt the user allowed MatChat to run in the background
 *   (battery optimization exemption).
 * @property helperConnected MatChat's accessibility service is enabled and bound.
 */
data class BackgroundSyncStatus(
    val batteryExempt: Boolean = false,
    val helperConnected: Boolean = false,
) {
    /** Both conditions hold: sync runs in the helper, with no "MatChat is
     *  running" notification. */
    val runsWithoutNotification: Boolean get() = batteryExempt && helperConnected

    /** The one-line status screens show for the background helper. */
    val helperStatus: HelperStatus
        get() = when {
            runsWithoutNotification -> HelperStatus.ON
            helperConnected -> HelperStatus.NEEDS_BATTERY
            else -> HelperStatus.OFF
        }
}

/** Background helper status as shown on S25/S27 (UX-SPEC). */
enum class HelperStatus {
    /** Helper on and background allowed: no "MatChat is running" notification. */
    ON,

    /** Helper on, but "Run in background" not allowed: the notification stays. */
    NEEDS_BATTERY,

    /** Helper off: the notification stays. */
    OFF,
}

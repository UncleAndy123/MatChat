package org.matchat.client

/**
 * Whether coming back to MatChat should land on the room list (home), dropping
 * whatever screen was last open. The user's rule: opening the app always shows
 * the room list, unless it was opened from a notification. Pure, so every
 * branch is unit-tested; MainActivity gathers the inputs.
 *
 * "Opening the app" means the user left it themselves (Home/End —
 * `onUserLeaveHint`) and came back. It does not mean:
 *  - returning from a screen MatChat itself opened (a gallery, ringtone or
 *    file picker, a system settings page, a share sheet) — [launchedExternal];
 *  - the flip closing and opening again — the screen was off when the app
 *    stopped ([screenOnWhenStopped] false), and no leave hint is given;
 *  - a notification tap — it navigates to its own room or call
 *    ([fromNotification]);
 *  - an ongoing call — its screen stays ([inCall]).
 */
internal fun shouldGoHome(
    userLeft: Boolean,
    launchedExternal: Boolean,
    screenOnWhenStopped: Boolean,
    fromNotification: Boolean,
    inCall: Boolean,
    hasSession: Boolean,
): Boolean = userLeft && !launchedExternal && screenOnWhenStopped && !fromNotification && !inCall && hasSession

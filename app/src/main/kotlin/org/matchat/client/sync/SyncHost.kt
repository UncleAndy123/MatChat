package org.matchat.client.sync

/**
 * What keeps the process alive while it syncs (docs/adr/0008). Exactly one host
 * owns sync at a time, through [SyncOwner].
 */
enum class SyncHost {
    /** [SyncForegroundService]: works everywhere, shows "MatChat is running". */
    FOREGROUND_SERVICE,

    /** The background helper (MatChatKeyAccessibilityService): the system keeps
     *  an enabled accessibility service bound, so no foreground service — and
     *  no notification — is needed. */
    ACCESSIBILITY,
}

/** The pure host choice, kept free of Android so it is unit-testable. */
object SyncHostPolicy {
    /**
     * The accessibility host needs the battery exemption too: without it, Doze
     * and app standby cut network access to a process that isn't running a
     * foreground service, and messages would stop arriving while the phone is
     * idle. Null means nothing should sync (signed out).
     */
    fun choose(signedIn: Boolean, helperConnected: Boolean, batteryExempt: Boolean): SyncHost? = when {
        !signedIn -> null
        helperConnected && batteryExempt -> SyncHost.ACCESSIBILITY
        else -> SyncHost.FOREGROUND_SERVICE
    }
}

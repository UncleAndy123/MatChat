package org.matchat.client.sync

import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import org.matchat.core.matrix.MatrixSessionStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Picks and starts the [SyncHost] (docs/adr/0008). Every "make sure we're
 * syncing" path goes through [ensureRunning]: MainActivity on resume and after
 * sign-in, the boot receiver, the [SyncWorker] watchdog, and the background
 * helper connecting or disconnecting. It is idempotent.
 *
 * The background helper (MatChatKeyAccessibilityService) registers its scope
 * here with [onHelperConnected]; the helper itself stays a key-handling service
 * that merely *hosts* sync.
 */
@Singleton
class SyncHosts @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionStore: MatrixSessionStore,
    private val owner: SyncOwner,
    private val status: BackgroundSyncStatusProvider,
) {
    @Volatile private var helperScope: CoroutineScope? = null

    /**
     * Ensure the right host owns sync. Returns true when a host owns it or the
     * foreground service start was accepted; false when nothing could be
     * started (signed out, or Android refused a background FGS start — the
     * watchdog then runs a catch-up instead).
     */
    fun ensureRunning(): Boolean {
        if (!sessionStore.hasSession()) {
            stopAll()
            return false
        }
        SyncWorker.schedule(context)
        status.refresh()
        val s = status.status.value
        val host = SyncHostPolicy.choose(
            signedIn = true,
            helperConnected = s.helperConnected,
            batteryExempt = s.batteryExempt,
        )
        val scope = helperScope
        return if (host == SyncHost.ACCESSIBILITY && scope != null) {
            owner.attach(SyncHost.ACCESSIBILITY, scope)
            // The helper owns sync now: drop the foreground service and with it
            // the "MatChat is running" notification.
            context.stopService(Intent(context, SyncForegroundService::class.java))
            true
        } else {
            owner.detach(SyncHost.ACCESSIBILITY)
            SyncForegroundService.start(context)
        }
    }

    /** The helper was enabled/bound by the system; it may now host sync. */
    fun onHelperConnected(scope: CoroutineScope) {
        helperScope = scope
        status.setHelperConnected(true)
        ensureRunning()
    }

    /** The helper was disabled or unbound: hand sync back to the foreground
     *  service right away so there is no gap. */
    fun onHelperDisconnected() {
        helperScope = null
        status.setHelperConnected(false)
        owner.detach(SyncHost.ACCESSIBILITY)
        if (sessionStore.hasSession()) ensureRunning()
    }

    /** Signed out: no host, no watchdog, no notification. */
    fun stopAll() {
        owner.detach(SyncHost.ACCESSIBILITY)
        owner.detach(SyncHost.FOREGROUND_SERVICE)
        context.stopService(Intent(context, SyncForegroundService::class.java))
        SyncWorker.cancel(context)
        Log.i(TAG, "sync hosts stopped (signed out)")
    }

    private companion object {
        const val TAG = "SyncHosts"
    }
}

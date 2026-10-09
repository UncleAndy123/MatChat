package org.matchat.client.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.matchat.client.R
import org.matchat.core.matrix.MatrixSession
import javax.inject.Inject

/**
 * The default [SyncHost] (docs/adr/0004, 0008): a foreground service with a
 * persistent low-priority notification, since there is no FCM on these devices
 * (PLAN.md §6.6). The sync logic itself lives in [SyncOwner]; this class only
 * keeps the process alive and hands [SyncOwner] its scope. When the background
 * helper hosts sync instead, [SyncHosts] stops this service and the notification
 * goes with it.
 *
 * Started through [SyncHosts.ensureRunning] (MainActivity, the boot receiver,
 * the [SyncWorker] watchdog). START_STICKY brings it back after a low-memory
 * kill; [SyncOwner] then restores the session without any Activity running.
 *
 * Android 15 caps a dataSync FGS at ~6h/24h; [onTimeout] hands sync off to the
 * [SyncWorker] catch-up runs when that ceiling is hit, and [onStartCommand]
 * reclaims it once the app is foregrounded (docs/adr/0004).
 */
@AndroidEntryPoint
class SyncForegroundService : LifecycleService() {

    @Inject lateinit var session: MatrixSession

    @Inject lateinit var owner: SyncOwner

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Must come first on every start (the OS requires it after
        // startForegroundService). It throws when Android won't allow a dataSync
        // FGS right now — e.g. the Android 15 budget is spent, or a BOOT_COMPLETED
        // start on API 35. Then leave it to the SyncWorker catch-up runs.
        val promoted = runCatching { startForeground(NOTIFICATION_ID, buildNotification()) }
            .onFailure { Log.w(TAG, "could not enter the foreground; using the catch-up fallback: ${it.message}") }
            .isSuccess
        if (!promoted) {
            timedOut = true
            SyncWorker.schedule(applicationContext)
            stopSelf()
            return START_NOT_STICKY
        }
        if (owner.activeHost.value == SyncHost.ACCESSIBILITY) {
            // The background helper already hosts sync; no notification needed.
            stopSelf()
            return START_NOT_STICKY
        }
        // Foregrounding resets the Android 15 dataSync budget, so the loop gets
        // a fresh window and the watchdog stops doing catch-ups.
        timedOut = false
        owner.attach(SyncHost.FOREGROUND_SERVICE, lifecycleScope)
        return START_STICKY
    }

    override fun onDestroy() {
        owner.detach(SyncHost.FOREGROUND_SERVICE)
        super.onDestroy()
    }

    /**
     * Android 15 caps a `dataSync` foreground service at ~6h per 24h; when we
     * cross it the OS calls this and we must stop promptly (ADR 0004). Pause the
     * loop (keeping the client alive for a fast foreground resume) and let the
     * always-scheduled [SyncWorker] run catch-ups so messages keep arriving,
     * delayed, until the app is foregrounded and [onStartCommand] reclaims sync.
     * Both `onTimeout` overloads route here: API 34 calls the one-arg form, API
     * 35+ the two-arg.
     */
    override fun onTimeout(startId: Int) = handleTimeout()

    override fun onTimeout(startId: Int, fgsType: Int) = handleTimeout()

    private fun handleTimeout() {
        Log.i(TAG, "dataSync FGS timed out; handing sync off to the WorkManager fallback")
        timedOut = true
        owner.detach(SyncHost.FOREGROUND_SERVICE)
        SyncWorker.schedule(applicationContext)
        // Pause before stopSelf(): stopSelf() ends the service and cancels
        // lifecycleScope, so ordering the pause first (svc.stop() is quick)
        // guarantees the loop is actually paused rather than left running in the
        // background until the worker's first catch-up. Well within the few
        // seconds the OS allows after onTimeout.
        lifecycleScope.launch {
            runCatching { session.pauseSync() }
            stopSelf()
        }
    }

    private fun buildNotification(): Notification {
        ensureChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.sync_notification_running))
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService<NotificationManager>() ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        runCatching { manager.deleteNotificationChannel("matchat.sync") } // drop the badged v1
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.sync_channel_name),
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                // The ongoing sync notification must not put a dot on the launcher
                // icon — only real incoming messages should badge.
                setShowBadge(false)
            },
        )
    }

    companion object {
        // v2: recreated with setShowBadge(false). A channel's badge setting is
        // locked after creation, so a new id is needed to drop the launcher dot
        // without a reinstall.
        private const val CHANNEL_ID = "matchat.sync.v2"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "SyncForegroundService"

        /** True after the OS refused or ended our foreground time (the Android 15
         *  dataSync cap). The [SyncWorker] watchdog then runs bounded catch-ups
         *  instead of restarting this service; cleared when a start succeeds. */
        @Volatile var timedOut: Boolean = false
            private set

        /** Use [SyncHosts.ensureRunning] instead — it picks the host. Returns
         *  false when Android refuses the start (background-start limits on
         *  API 31+ without the battery exemption, a BOOT_COMPLETED start on 35). */
        internal fun start(context: Context): Boolean {
            val intent = Intent(context, SyncForegroundService::class.java)
            // startForegroundService exists only on API 26+ (minSdk is 24); on
            // older AOSP flips, startService + startForeground works without the
            // 5-second promotion window.
            return runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { Log.w(TAG, "foreground service start refused: ${it.message}") }
                .isSuccess
        }
    }
}

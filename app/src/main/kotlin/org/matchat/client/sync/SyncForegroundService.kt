package org.matchat.client.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.LifecycleService
import dagger.hilt.android.AndroidEntryPoint
import org.matchat.client.R

/**
 * The single owner of the SDK sync loop (ARCHITECTURE.md "Sync lifecycle").
 * Screens observe; they never start or stop sync. No Play Services means no FCM,
 * so sync is a foreground service with a persistent low-priority notification
 * (PLAN.md §6.6, docs/adr/0004).
 *
 * M0: the service runs and holds the notification so the foreground-service and
 * notification plumbing is real on-device; M1 attaches the SDK SyncService here.
 * The Android 15 dataSync 6h/24h cap fallback to WorkManager is M1 work, tracked
 * in docs/adr/0004 — not a silent gap.
 */
@AndroidEntryPoint
class SyncForegroundService : LifecycleService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForeground(NOTIFICATION_ID, buildNotification())
        // M1: start the SDK SyncService here and mirror its state into a Flow.
        return START_STICKY
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
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.sync_channel_name),
                NotificationManager.IMPORTANCE_MIN,
            ),
        )
    }

    companion object {
        private const val CHANNEL_ID = "matchat.sync"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SyncForegroundService::class.java))
        }
    }
}

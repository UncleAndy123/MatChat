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
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.matchat.client.R
import org.matchat.client.notify.MessageNotifier
import org.matchat.core.matrix.MatrixSession
import org.matchat.core.model.RoomSummary
import javax.inject.Inject

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

    @Inject lateinit var session: MatrixSession

    private val lastUnread = HashMap<String, Int>()
    private var seeded = false
    private var observing = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForeground(NOTIFICATION_ID, buildNotification())
        observeRoomsForNotifications()
        return START_STICKY
    }

    /** Watch joined-room unread counts and raise a per-room notification when one
     *  climbs (a new incoming message), cancelling it when the room is read. The
     *  first emission only seeds the baseline so existing history never alerts. */
    private fun observeRoomsForNotifications() {
        if (observing) return
        observing = true
        lifecycleScope.launch {
            session.rooms.collect { rooms -> onRooms(rooms) }
        }
    }

    private fun onRooms(rooms: List<RoomSummary>) {
        if (!seeded) {
            rooms.forEach { lastUnread[it.id.value] = it.unreadCount }
            seeded = true
            return
        }
        rooms.forEach { room ->
            val prev = lastUnread[room.id.value] ?: 0
            val now = room.unreadCount
            when {
                now > prev && now > 0 ->
                    MessageNotifier.show(this, room.id, room.name.ifBlank { room.id.value }, now)
                now == 0 && prev > 0 -> MessageNotifier.cancel(this, room.id)
            }
            lastUnread[room.id.value] = now
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
            val intent = Intent(context, SyncForegroundService::class.java)
            // startForegroundService exists only on API 26+ (minSdk is 24); on
            // older AOSP flips, startService + startForeground works without the
            // 5-second promotion window.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}

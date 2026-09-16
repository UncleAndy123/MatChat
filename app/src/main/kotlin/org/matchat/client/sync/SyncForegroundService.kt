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
import org.matchat.client.notify.CallNotifier
import org.matchat.client.notify.MessageNotifier
import org.matchat.core.matrix.MatrixAuth
import org.matchat.core.matrix.MatrixSession
import org.matchat.core.matrix.MatrixSessionStore
import org.matchat.core.model.RoomSummary
import org.matchat.core.rtc.CallController
import org.matchat.core.rtc.CallPhase
import org.matchat.core.rtc.IncomingCall
import org.matchat.core.ui.prefs.UserPreferences
import javax.inject.Inject

/**
 * The single owner of the SDK sync loop (ARCHITECTURE.md "Sync lifecycle").
 * Screens observe; they never start or stop sync. No Play Services means no FCM,
 * so sync is a foreground service with a persistent low-priority notification
 * (PLAN.md §6.6, docs/adr/0004).
 *
 * The client/SyncService itself is normally started by MainActivity (sign-in or
 * its cold-start restore); [ensureSessionRestored] below is this service's own
 * fallback so a service-only relaunch (after the whole process was killed and
 * START_STICKY brings just this service back, no Activity involved) restarts
 * sync too, rather than leaving the "MatChat is running" notification up over
 * a dead sync loop.
 * The Android 15 dataSync 6h/24h cap fallback to WorkManager is still tracked
 * in docs/adr/0004 — not a silent gap.
 */
@AndroidEntryPoint
class SyncForegroundService : LifecycleService() {

    @Inject lateinit var session: MatrixSession

    @Inject lateinit var auth: MatrixAuth

    @Inject lateinit var sessionStore: MatrixSessionStore

    @Inject lateinit var userPreferences: UserPreferences

    @Inject lateinit var callController: CallController

    private val lastUnread = HashMap<String, Int>()
    private val lastCall = HashMap<String, Boolean>()

    /** Rooms we raised a ring for, roomId -> caller; cleared on answer or end. */
    private val ringingRooms = HashMap<String, String>()

    /** True while we are in any call (our own or a ring we raised) — suppresses a
     *  second ring and our own outgoing call from ringing us. */
    private var ownCallActive = false
    private var seeded = false
    private var observing = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForeground(NOTIFICATION_ID, buildNotification())
        ensureSessionRestored()
        observeRoomsForNotifications()
        return START_STICKY
    }

    /**
     * Bug fix: the SDK's sync loop is only ever started from MainActivity
     * (sign-in, or its own cold-start restore) — this service never started
     * it itself, only rode the already-live client's [session.rooms] flow.
     * A low-memory kill takes the whole process, this singleton client
     * included; START_STICKY then relaunches *this service* without ever
     * running MainActivity.onCreate(), so the client was never rebuilt and
     * the persistent "MatChat is running" notification kept showing while
     * sync had actually died — the on-device "not reliably syncing, won't
     * show new messages" report. Restoring here too means any process that
     * gets this service running also has a live sync loop, regardless of
     * whether an Activity ever ran in it. isActive() guards against
     * rebuilding a client that's already live (restore() is not a no-op —
     * it tears down and reconnects).
     */
    private fun ensureSessionRestored() {
        if (session.isActive() || !sessionStore.hasSession()) return
        lifecycleScope.launch { auth.restoreSession() }
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
        // Track our call phase: once a ringing call connects it's answered (drop
        // it from the missed-call set); isActive gates a second ring.
        lifecycleScope.launch {
            callController.session.collect { s ->
                ownCallActive = s.isActive
                if (s.phase == CallPhase.CONNECTED) s.roomId?.let { ringingRooms.remove(it.value) }
            }
        }
    }

    private suspend fun onRooms(rooms: List<RoomSummary>) {
        if (!seeded) {
            // Seed both baselines so existing unread history and an already-ongoing
            // call (app just launched into it) never alert.
            rooms.forEach {
                lastUnread[it.id.value] = it.unreadCount
                lastCall[it.id.value] = it.hasActiveCall
            }
            seeded = true
            return
        }
        rooms.forEach { room ->
            val hadCall = lastCall[room.id.value] ?: false
            when {
                room.hasActiveCall && !hadCall -> onCallAppeared(room)
                !room.hasActiveCall && hadCall -> onCallDisappeared(room)
            }
            lastCall[room.id.value] = room.hasActiveCall
            val prev = lastUnread[room.id.value] ?: 0
            val now = room.unreadCount
            when {
                now > prev && now > 0 -> if (userPreferences.notificationsEnabled.value) {
                    MessageNotifier.show(
                        this,
                        room.id,
                        room.name.ifBlank { room.id.value },
                        now,
                        channelVersion = userPreferences.notificationChannelVersion.value,
                        soundUri = userPreferences.notificationSoundUri.value,
                    )
                }
                now == 0 && prev > 0 -> MessageNotifier.cancel(this, room.id)
            }
            lastUnread[room.id.value] = now
        }
    }

    /** A call appeared in a room: ring, unless we are already in a call (our own
     *  outgoing call lights up the same room, and we don't ring ourselves). */
    private fun onCallAppeared(room: RoomSummary) {
        if (ownCallActive) return
        val caller = room.name.ifBlank { room.id.value }
        ringingRooms[room.id.value] = caller
        callController.onIncomingCall(IncomingCall(room.id, caller))
        CallNotifier.showIncoming(this, room.id, caller)
    }

    /** A call ended: drop the ring. If we raised it and never answered (still in
     *  ringingRooms — the session collector removes answered ones), show missed. */
    private fun onCallDisappeared(room: RoomSummary) {
        CallNotifier.cancel(this)
        val caller = ringingRooms.remove(room.id.value) ?: return
        CallNotifier.showMissed(this, room.id, caller)
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

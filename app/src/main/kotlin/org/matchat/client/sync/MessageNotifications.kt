package org.matchat.client.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.matchat.client.notify.MessageNotifier
import org.matchat.core.model.RoomSummary
import org.matchat.core.ui.prefs.UserPreferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns a joined-room list into per-room "new messages" notifications, raising
 * one when a room's unread count climbs and cancelling it when the room is read.
 *
 * A [Singleton] so its unread baseline survives the [SyncForegroundService] ->
 * [SyncWorker] handoff when the Android 15 `dataSync` cap forces it (ADR 0004):
 * both observers feed the same instance, so a message counted while the
 * foreground service was alive is not re-alerted by the first fallback sync.
 * The first emission after the process starts only seeds the baseline, so
 * existing unread history never alerts — the same rule the foreground service
 * has always applied on a cold start (the accepted "delayed messages" cost of
 * ADR 0004 is a message that arrives in a window where no observer is running).
 */
@Singleton
class MessageNotifications @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userPreferences: UserPreferences,
) {
    private val lastUnread = HashMap<String, Int>()
    private var seeded = false

    suspend fun onRooms(rooms: List<RoomSummary>) {
        if (!seeded) {
            rooms.forEach { lastUnread[it.id.value] = it.unreadCount }
            seeded = true
            return
        }
        rooms.forEach { room ->
            val prev = lastUnread[room.id.value] ?: 0
            val now = room.unreadCount
            when {
                now > prev && now > 0 -> if (userPreferences.notificationsEnabled.value) {
                    MessageNotifier.show(
                        context,
                        room.id,
                        room.name.ifBlank { room.id.value },
                        now,
                        channelVersion = userPreferences.notificationChannelVersion.value,
                        soundUri = userPreferences.notificationSoundUri.value,
                    )
                }
                now == 0 && prev > 0 -> MessageNotifier.cancel(context, room.id)
            }
            lastUnread[room.id.value] = now
        }
    }
}

package org.matchat.client.notify

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import org.matchat.client.MainActivity
import org.matchat.client.R
import org.matchat.core.model.RoomId

/**
 * Posts one notification per room for incoming messages (guided by
 * DPAD-Messaging's NotificationHelper). Each has:
 *   • Tap    → open the room (MainActivity deep-link)
 *   • Reply  → inline RemoteInput → [MessageReplyReceiver]
 *   • Read   → [MarkReadReceiver]
 *
 * No message body is shown yet (the latest-event preview is a follow-up), so the
 * text is a generic count — which doubles as lock-screen privacy.
 */
object MessageNotifier {

    const val CHANNEL_MESSAGES = "matchat.messages"
    const val REPLY_KEY = "matchat.reply"
    const val EXTRA_ROOM_ID = "org.matchat.client.ROOM_ID"
    const val EXTRA_NOTIF_ID = "org.matchat.client.NOTIF_ID"

    private const val REQ_REPLY = 1_000
    private const val REQ_READ = 2_000

    fun notifId(roomId: RoomId): Int = roomId.value.hashCode()

    fun show(context: Context, roomId: RoomId, title: String, unread: Int) {
        val id = notifId(roomId)

        val openPI = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_ROOM_ID, roomId.value)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val remoteInput = RemoteInput.Builder(REPLY_KEY)
            .setLabel(context.getString(R.string.notif_reply))
            .build()
        val replyPI = PendingIntent.getBroadcast(
            context,
            id + REQ_REPLY,
            Intent(context, MessageReplyReceiver::class.java).apply {
                putExtra(EXTRA_ROOM_ID, roomId.value)
                putExtra(EXTRA_NOTIF_ID, id)
            },
            // MUTABLE is required for RemoteInput to fill in the reply text.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val replyAction = NotificationCompat.Action.Builder(
            R.drawable.ic_stat_sync,
            context.getString(R.string.notif_reply),
            replyPI,
        ).addRemoteInput(remoteInput).build()

        val readPI = PendingIntent.getBroadcast(
            context,
            id + REQ_READ,
            Intent(context, MarkReadReceiver::class.java).apply {
                putExtra(EXTRA_ROOM_ID, roomId.value)
                putExtra(EXTRA_NOTIF_ID, id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val readAction = NotificationCompat.Action(
            0,
            context.getString(R.string.notif_mark_read),
            readPI,
        )

        val text = context.resources.getQuantityString(R.plurals.notif_new_messages, unread, unread)
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openPI)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(replyAction)
            .addAction(readAction)
            .build()

        manager(context).notify(id, notification)
    }

    fun cancel(context: Context, roomId: RoomId) = manager(context).cancel(notifId(roomId))

    fun cancelById(context: Context, notifId: Int) = manager(context).cancel(notifId)

    private fun manager(context: Context): NotificationManager =
        context.getSystemService(NotificationManager::class.java)
}

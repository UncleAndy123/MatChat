package org.matchat.client.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import org.matchat.client.MainActivity
import org.matchat.client.R
import org.matchat.core.model.RoomId

/**
 * The incoming-call ring (docs/VOICE.md §5). No FCM, so the foreground sync
 * service raises this when it sees a room gain an active call. A full-screen
 * intent on a high-importance channel so it wakes the screen and shows over the
 * lock screen / closed flip. Tap or Answer opens the call screen (Answer also
 * accepts); Decline hangs up via [CallActionReceiver].
 */
object CallNotifier {

    const val CHANNEL_CALLS = "matchat.calls"
    const val EXTRA_CALL_ROOM = "org.matchat.client.CALL_ROOM"
    const val EXTRA_CALL_CALLER = "org.matchat.client.CALL_CALLER"
    const val EXTRA_CALL_ANSWER = "org.matchat.client.CALL_ANSWER"
    const val ACTION_DECLINE = "org.matchat.client.action.DECLINE_CALL"

    private const val RING_ID = 7_000
    private const val MISSED_ID = 7_001
    private const val REQ_FULLSCREEN = 7_100
    private const val REQ_ANSWER = 7_101
    private const val REQ_DECLINE = 7_102

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService<NotificationManager>() ?: return
        if (manager.getNotificationChannel(CHANNEL_CALLS) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CALLS,
                context.getString(R.string.call_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.call_channel_desc)
                enableVibration(true)
                setBypassDnd(true)
            },
        )
    }

    fun showIncoming(context: Context, roomId: RoomId, caller: String) {
        ensureChannel(context)
        val open = activityIntent(context, roomId, caller, answer = false)
        val fullScreen = PendingIntent.getActivity(
            context, REQ_FULLSCREEN, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val answer = PendingIntent.getActivity(
            context, REQ_ANSWER, activityIntent(context, roomId, caller, answer = true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val decline = PendingIntent.getBroadcast(
            context, REQ_DECLINE,
            Intent(context, CallActionReceiver::class.java).apply {
                action = ACTION_DECLINE
                putExtra(EXTRA_CALL_ROOM, roomId.value)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setContentTitle(caller)
            .setContentText(context.getString(R.string.call_incoming))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .addAction(0, context.getString(R.string.call_answer), answer)
            .addAction(0, context.getString(R.string.call_decline), decline)
            .build()
        context.getSystemService<NotificationManager>()?.notify(RING_ID, notification)
    }

    /** Replace the ring with a "missed call" note (call vanished unanswered). */
    fun showMissed(context: Context, roomId: RoomId, caller: String) {
        cancel(context)
        val open = PendingIntent.getActivity(
            context, REQ_FULLSCREEN, activityIntent(context, roomId, caller, answer = false),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setContentTitle(caller)
            .setContentText(context.getString(R.string.call_missed))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        context.getSystemService<NotificationManager>()?.notify(MISSED_ID, notification)
    }

    fun cancel(context: Context) {
        context.getSystemService<NotificationManager>()?.cancel(RING_ID)
    }

    private fun activityIntent(context: Context, roomId: RoomId, caller: String, answer: Boolean): Intent =
        Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_CALL_ROOM, roomId.value)
            putExtra(EXTRA_CALL_CALLER, caller)
            putExtra(EXTRA_CALL_ANSWER, answer)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
}

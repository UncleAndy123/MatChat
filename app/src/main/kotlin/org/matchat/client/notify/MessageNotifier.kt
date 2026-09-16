package org.matchat.client.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.content.getSystemService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.matchat.client.MainActivity
import org.matchat.client.R
import org.matchat.core.model.RoomId
import org.matchat.core.ui.prefs.SILENT_NOTIFICATION_SOUND

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

    const val REPLY_KEY = "matchat.reply"
    const val EXTRA_ROOM_ID = "org.matchat.client.ROOM_ID"
    const val EXTRA_NOTIF_ID = "org.matchat.client.NOTIF_ID"

    private const val REQ_REPLY = 1_000
    private const val REQ_READ = 2_000
    private const val CHANNEL_PREFIX = "matchat.messages.s"

    /** A fixed, always-default-sound fallback channel — never versioned, never
     *  deleted — used only when posting against the user's chosen channel
     *  throws (see [show]'s retry). Exists so a broken stored sound
     *  preference degrades to "wrong sound" rather than "no notification at
     *  all, forever." */
    private const val SAFE_CHANNEL_ID = "matchat.messages.safe"
    private const val TAG = "MessageNotifier"

    fun notifId(roomId: RoomId): Int = roomId.value.hashCode()

    /** The channel id for a given UserPreferences.notificationChannelVersion
     *  value — versioned because a channel's sound is immutable once created
     *  (Settings > Notifications round; same wall SyncForegroundService's own
     *  CHANNEL_ID = "matchat.sync.v2" already hit). */
    fun channelId(version: Int): String = "$CHANNEL_PREFIX$version"

    /**
     * Creates the channel for [version] (with [soundUri], or the system default
     * when null) if it doesn't already exist, and deletes the previous version's
     * channel so a changed sound doesn't leave an orphaned duplicate in system
     * Settings. Idempotent and safe to call on every notification post and at
     * app startup — a no-op once the current version's channel already exists.
     */
    suspend fun ensureChannel(context: Context, version: Int, soundUri: String?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService<NotificationManager>() ?: return
        val id = channelId(version)
        if (manager.getNotificationChannel(id) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                id,
                context.getString(R.string.messages_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                enableVibration(true)
                setSound(leadInSoundUri(context, soundUri), notificationAudioAttributes())
            },
        )
        if (version > 0) runCatching { manager.deleteNotificationChannel(channelId(version - 1)) }
    }

    /** The [SAFE_CHANNEL_ID] fallback channel — always the system default
     *  sound, created once and never deleted/versioned. */
    private suspend fun ensureSafeChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService<NotificationManager>() ?: return
        if (manager.getNotificationChannel(SAFE_CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                SAFE_CHANNEL_ID,
                context.getString(R.string.messages_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                enableVibration(true)
                setSound(leadInSoundUri(context, null), notificationAudioAttributes())
            },
        )
    }

    private fun notificationAudioAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /** null → system default; [SILENT_NOTIFICATION_SOUND] → no sound (passing
     *  a null Uri to NotificationChannel/NotificationCompat.setSound disables
     *  playback entirely, per platform docs); anything else → that URI. */
    private fun resolveSoundUri(soundUri: String?): Uri? = when (soundUri) {
        null -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        SILENT_NOTIFICATION_SOUND -> null
        else -> Uri.parse(soundUri)
    }

    /** [resolveSoundUri], then (off the calling thread) prepends the
     *  Bluetooth wake-up silent lead-in (SilentLeadInSound) — the silent
     *  choice (null) is returned as-is, nothing to process. Only reached
     *  from channel creation, which happens once per sound choice
     *  (ensureChannel/ensureSafeChannel both no-op once their channel
     *  already exists), so the decode cost here is rare, not per-notification. */
    private suspend fun leadInSoundUri(context: Context, soundUri: String?): Uri? {
        val resolved = resolveSoundUri(soundUri) ?: return null
        return withContext(Dispatchers.IO) { SilentLeadInSound.process(context, resolved) }
    }

    suspend fun show(
        context: Context,
        roomId: RoomId,
        title: String,
        unread: Int,
        channelVersion: Int = 0,
        soundUri: String? = null,
    ) {
        ensureChannel(context, channelVersion, soundUri)
        val id = notifId(roomId)
        val notification = buildNotification(context, roomId, id, title, unread, channelId(channelVersion), soundUri)

        // Crash fix (kept): a notification whose sound URI the app no longer
        // holds a read grant for (observed on-device: a custom sound picked
        // via RingtoneManager, content://media/...) makes notify() throw
        // SecurityException. This runs inside SyncForegroundService's
        // session.rooms collector — uncaught, it kills the whole app on every
        // incoming message.
        //
        // Bug fix: the crash fix alone used to swallow that failure silently
        // and completely — not just the sound, the *entire* notification
        // (icon, title, text, everything) never posted, forever, since one
        // atomic notify() call builds all of it together. On-device report:
        // "notification sound and the notification symbol don't seem to
        // actually output" — this was the actual cause, not a separate
        // icon bug. Now: log it (diagnosable), and retry once against a
        // fixed, always-default-sound channel, so a broken stored sound
        // preference degrades to "wrong sound" rather than "no notification."
        val posted = runCatching { manager(context).notify(id, notification) }
        if (posted.isFailure) {
            Log.w(
                TAG,
                "notify() failed on channel ${channelId(channelVersion)}; retrying with the default sound",
                posted.exceptionOrNull(),
            )
            ensureSafeChannel(context)
            val fallback = buildNotification(context, roomId, id, title, unread, SAFE_CHANNEL_ID, soundUri = null)
            runCatching { manager(context).notify(id, fallback) }
                .onFailure { e -> Log.e(TAG, "fallback notify() also failed; giving up on this notification", e) }
        }
    }

    private fun buildNotification(
        context: Context,
        roomId: RoomId,
        id: Int,
        title: String,
        unread: Int,
        channelId: String,
        soundUri: String?,
    ): Notification {
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
            R.drawable.ic_stat_message,
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
        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openPI)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // Below O the channel doesn't carry the sound — set it directly here.
            // On O+ this is ignored in favor of the channel's own sound.
            .setSound(resolveSoundUri(soundUri))
            .addAction(replyAction)
            .addAction(readAction)
            .build()
    }

    fun cancel(context: Context, roomId: RoomId) = manager(context).cancel(notifId(roomId))

    fun cancelById(context: Context, notifId: Int) = manager(context).cancel(notifId)

    private fun manager(context: Context): NotificationManager =
        context.getSystemService(NotificationManager::class.java)
}

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
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.content.getSystemService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.matchat.client.MainActivity
import org.matchat.client.R
import org.matchat.core.model.MediaKind
import org.matchat.core.model.RoomId
import org.matchat.core.model.notify.RoomSound
import org.matchat.core.ui.prefs.SILENT_NOTIFICATION_SOUND

/**
 * Posts one notification per room for incoming messages (guided by
 * DPAD-Messaging's NotificationHelper). Each has:
 *   • Tap    → open the room (MainActivity deep-link)
 *   • Reply  → inline RemoteInput → [MessageReplyReceiver]
 *   • Read   → [MarkReadReceiver]
 *
 * The text is the latest message ("Ann: see you at six"), or "2 new messages"
 * when it can't be shown ([NotificationText]). The lock-screen version is the
 * count only, so a phone set to hide sensitive content never shows the text.
 * The LED is on for as long as the notification is up — MessageNotifications
 * cancels it when the room is read (UX-SPEC S15).
 *
 * Channels (Android 8+; a channel's sound and lights can't change after it is
 * created, hence the versions and the `l` = lights generation):
 *   • `matchat.messages.l<version>` — the app-wide sound (Settings > Notifications)
 *   • `matchat.messages.r.<room>.l<version>` — a room's own sound (Room info)
 *   • [SAFE_CHANNEL_ID] — default sound, used only if posting on the others fails
 */
object MessageNotifier {

    const val REPLY_KEY = "matchat.reply"
    const val EXTRA_ROOM_ID = "org.matchat.client.ROOM_ID"
    const val EXTRA_NOTIF_ID = "org.matchat.client.NOTIF_ID"

    /** Kyocera cover-screen mirror (docs/COVER-DISPLAY.md). InfoSign's
     *  NotificationListener mirrors a notification to the front screen when
     *  this extra equals [SUBLCD_MESSAGING] (or "email") — it keys on the
     *  extra, not the package, so this is all an unrooted app needs to light
     *  the cover for a waiting message. Harmless everywhere else: a device
     *  without InfoSign just ignores an unknown extra. */
    const val EXTRA_SUBLCD = "sublcd_notification"
    const val SUBLCD_MESSAGING = "messaging"

    /** One logcat tag for the whole cover-screen pipeline, so it can be watched
     *  alongside InfoSign's own tag:
     *  `adb shell logcat -s MatChatCover kc_infosign`. Diagnostics only — no PII
     *  (docs/COVER-DISPLAY.md, AGENTS.md §9). */
    const val COVER_TAG = "MatChatCover"

    private const val REQ_REPLY = 1_000
    private const val REQ_READ = 2_000
    private const val CHANNEL_PREFIX = "matchat.messages.l"

    /** The channels from before the LED change (no lights); deleted on the
     *  first post so system Settings doesn't list stale duplicates. */
    private const val OLD_CHANNEL_PREFIX = "matchat.messages.s"
    private const val OLD_SAFE_CHANNEL_ID = "matchat.messages.safe"
    private const val ROOM_CHANNEL_PREFIX = "matchat.messages.r."

    /** A fixed, always-default-sound fallback channel — never versioned, never
     *  deleted — used only when posting against the user's chosen channel
     *  throws (see [show]'s retry). Exists so a broken stored sound
     *  preference degrades to "wrong sound" rather than "no notification at
     *  all, forever." */
    private const val SAFE_CHANNEL_ID = "matchat.messages.safe.l"
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
            messageChannel(context, id, context.getString(R.string.messages_channel_name), soundUri),
        )
        if (version > 0) runCatching { manager.deleteNotificationChannel(channelId(version - 1)) }
        // Pre-LED channels: the same versions under the old prefix, and the old safe one.
        runCatching { manager.deleteNotificationChannel("$OLD_CHANNEL_PREFIX$version") }
        if (version > 0) runCatching { manager.deleteNotificationChannel("$OLD_CHANNEL_PREFIX${version - 1}") }
        runCatching { manager.deleteNotificationChannel(OLD_SAFE_CHANNEL_ID) }
    }

    /** All of one room's channels share this prefix, whatever the version. */
    private fun roomChannelPrefix(roomId: RoomId): String =
        "$ROOM_CHANNEL_PREFIX${Integer.toHexString(roomId.value.hashCode())}."

    fun roomChannelId(roomId: RoomId, version: Int): String = "${roomChannelPrefix(roomId)}l$version"

    /**
     * A room's own channel, with its [sound] (UX-SPEC S12), named after the
     * room so it reads sensibly in system Settings. Deletes the room's other
     * channel versions. Idempotent.
     */
    suspend fun ensureRoomChannel(context: Context, roomId: RoomId, roomName: String, sound: RoomSound) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService<NotificationManager>() ?: return
        val id = roomChannelId(roomId, sound.version)
        if (manager.getNotificationChannel(id) == null) {
            val name = context.getString(R.string.messages_room_channel_name, roomName)
            manager.createNotificationChannel(messageChannel(context, id, name, sound.uri))
        }
        deleteRoomChannels(manager, roomId, keep = id)
    }

    /** The room went back to the app sound: drop its own channels. */
    fun deleteRoomChannels(context: Context, roomId: RoomId) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService<NotificationManager>() ?: return
        deleteRoomChannels(manager, roomId, keep = null)
    }

    private fun deleteRoomChannels(manager: NotificationManager, roomId: RoomId, keep: String?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val prefix = roomChannelPrefix(roomId)
        runCatching {
            manager.notificationChannels
                .filter { it.id.startsWith(prefix) && it.id != keep }
                .forEach { manager.deleteNotificationChannel(it.id) }
        }
    }

    /** Every message channel: high importance, vibration, the LED (device
     *  default color), and [soundUri] with the Bluetooth lead-in. */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private suspend fun messageChannel(context: Context, id: String, name: String, soundUri: String?) =
        NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true)
            enableLights(true)
            setSound(leadInSoundUri(context, soundUri), notificationAudioAttributes())
        }

    /** The [SAFE_CHANNEL_ID] fallback channel — always the system default
     *  sound, created once and never deleted/versioned. */
    private suspend fun ensureSafeChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService<NotificationManager>() ?: return
        if (manager.getNotificationChannel(SAFE_CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            messageChannel(context, SAFE_CHANNEL_ID, context.getString(R.string.messages_channel_name), null),
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

    /**
     * Posts (or updates) [roomId]'s notification. With [roomSound] set, it goes
     * on the room's own channel with that sound; otherwise on the app-wide
     * channel ([channelVersion], [soundUri]).
     */
    @Suppress("LongParameterList")
    suspend fun show(
        context: Context,
        roomId: RoomId,
        content: NotificationContent,
        channelVersion: Int = 0,
        soundUri: String? = null,
        roomSound: RoomSound? = null,
    ) {
        val channel: String
        val sound: String?
        if (roomSound != null) {
            ensureRoomChannel(context, roomId, content.roomName, roomSound)
            channel = roomChannelId(roomId, roomSound.version)
            sound = roomSound.uri
        } else {
            ensureChannel(context, channelVersion, soundUri)
            deleteRoomChannels(context, roomId)
            channel = channelId(channelVersion)
            sound = soundUri
        }
        val id = notifId(roomId)
        val notification = buildNotification(context, roomId, id, content, channel, sound)

        // Cover-screen diagnostics (docs/COVER-DISPLAY.md). Reads the extra
        // back off the *built* Notification so we can see whether the
        // `sublcd_notification` key actually survived NotificationCompat into
        // notification.extras — the thing InfoSign reads. No PII: ids, the
        // count, the channel and the category only, never room name or body.
        Log.d(
            COVER_TAG,
            "show id=$id unread=${content.unread} channel=$channel roomSound=${roomSound != null} " +
                "sublcd=${notification.extras?.getString(EXTRA_SUBLCD)} category=${notification.category}",
        )

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
        if (posted.isSuccess) {
            Log.d(COVER_TAG, "notify ok id=$id — InfoSign should now mirror it if sublcd=messaging above")
        }
        if (posted.isFailure) {
            Log.w(
                TAG,
                "notify() failed on channel $channel; retrying with the default sound",
                posted.exceptionOrNull(),
            )
            Log.d(COVER_TAG, "notify FAILED id=$id on channel $channel; retrying on safe channel")
            ensureSafeChannel(context)
            val fallback = buildNotification(context, roomId, id, content, SAFE_CHANNEL_ID, soundUri = null)
            runCatching { manager(context).notify(id, fallback) }
                .onSuccess { Log.d(COVER_TAG, "fallback notify ok id=$id") }
                .onFailure { e -> Log.e(TAG, "fallback notify() also failed; giving up on this notification", e) }
        }

        // Also raise a transient card on the Kyocera cover screen (sub-LCD) so a
        // waiting message is visible with the flip closed (docs/COVER-DISPLAY.md).
        // Independent of the status-bar notify() above; no-ops off Kyocera. The
        // title (room name), not the body, keeps the lock-screen privacy posture.
        CoverScreenNotifier.notify(context, id, content.roomName)
    }

    @Suppress("LongMethod")
    private fun buildNotification(
        context: Context,
        roomId: RoomId,
        id: Int,
        content: NotificationContent,
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

        val title = content.roomName
        val count = context.resources.getQuantityString(R.plurals.notif_new_messages, content.unread, content.unread)
        val text = messageText(context, content) ?: count

        // What the lock screen / outer display shows when the phone hides
        // sensitive content: the room and the count, never the text.
        val publicVersion = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(title)
            .setContentText(count)
            .build()

        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .apply { if (content.unread > 1 && text != count) setSubText(count) }
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            // Below O the LED is set per notification (on O+ the channel's
            // enableLights decides). Blinks until the notification is cleared.
            .setDefaults(NotificationCompat.DEFAULT_LIGHTS)
            .setContentIntent(openPI)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            // Lights the Kyocera cover screen (docs/COVER-DISPLAY.md). Rides
            // this notification's existing lifecycle: posted on a new message,
            // cancelled on read, so the cover indicator tracks unread for free.
            .addExtras(Bundle().apply { putString(EXTRA_SUBLCD, SUBLCD_MESSAGING) })
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // Below O the channel doesn't carry the sound — set it directly here.
            // On O+ this is ignored in favor of the channel's own sound.
            .setSound(resolveSoundUri(soundUri))
            .addAction(replyAction)
            .addAction(readAction)
            .build()
    }

    /** "Ann: see you at six", "Photo", or null when there's nothing to show
     *  (the caller then uses the count). */
    private fun messageText(context: Context, content: NotificationContent): String? {
        val body = when (val b = NotificationText.body(content)) {
            is NotificationText.Body.Text -> b.text
            is NotificationText.Body.Media -> context.getString(mediaLabel(b.kind))
            NotificationText.Body.CountOnly -> return null
        }
        val sender = NotificationText.senderPrefix(content) ?: return body
        return context.getString(R.string.notif_sender_text, sender, body)
    }

    private fun mediaLabel(kind: MediaKind): Int = when (kind) {
        MediaKind.IMAGE -> R.string.notif_media_photo
        MediaKind.VIDEO -> R.string.notif_media_video
        MediaKind.AUDIO -> R.string.notif_media_audio
        MediaKind.VOICE -> R.string.notif_media_voice
        MediaKind.FILE -> R.string.notif_media_file
    }

    fun cancel(context: Context, roomId: RoomId) {
        val id = notifId(roomId)
        manager(context).cancel(id)
        CoverScreenNotifier.cancel(context, id) // clear the cover card too
    }

    fun cancelById(context: Context, notifId: Int) {
        manager(context).cancel(notifId)
        CoverScreenNotifier.cancel(context, notifId)
    }

    private fun manager(context: Context): NotificationManager =
        context.getSystemService(NotificationManager::class.java)
}

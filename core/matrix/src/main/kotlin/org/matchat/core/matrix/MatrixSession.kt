package org.matchat.core.matrix

import kotlinx.coroutines.flow.Flow
import org.matchat.core.model.CallState
import org.matchat.core.model.DeviceTrust
import org.matchat.core.model.EventId
import org.matchat.core.model.InviteSummary
import org.matchat.core.model.MediaKind
import org.matchat.core.model.Profile
import org.matchat.core.model.RoomDetails
import org.matchat.core.model.RoomId
import org.matchat.core.model.RoomMemberSummary
import org.matchat.core.model.RoomSummary
import org.matchat.core.model.SyncStateSource
import org.matchat.core.model.TimelineItem
import org.matchat.core.model.UserId

/**
 * The whole contract between the app and the Matrix Rust SDK (PLAN.md §5).
 * Everything here returns :core:model types — no SDK type ever crosses this line
 * (AGENTS.md §2). All heavy work happens on Dispatchers.IO inside the
 * implementation; callers just collect Flows and await suspend functions.
 *
 * Extends [SyncStateSource] (Online indicator round) so :core:ui can observe
 * [syncState] via that :core:model-level contract without depending on this
 * module directly — MatrixModule binds the same implementation both ways.
 */
interface MatrixSession : SyncStateSource {
    val rooms: Flow<List<RoomSummary>> // joined rooms only
    val invites: Flow<List<InviteSummary>> // membership == Invited
    val ownDevice: Flow<DeviceTrust>

    /** Whether a live SDK client (and its sync loop) exists right now. False
     *  right after a process restart until something restores the session —
     *  callers that can outlive Activity (SyncForegroundService) use this to
     *  know sync needs re-establishing, not just observing. */
    fun isActive(): Boolean

    /** Ensure the sync loop is running (idempotent). The foreground service
     *  calls this whenever it (re)starts, so a loop the WorkManager fallback
     *  left stopped — or one restored but not yet syncing — resumes. No-op when
     *  there is no live client. */
    suspend fun ensureSyncing()

    /** Stop the sync loop but keep the client (and its crypto store) alive, so a
     *  later [ensureSyncing] resumes without a full restore. Used when the
     *  foreground service hits the Android 15 `dataSync` runtime cap (ADR 0004)
     *  and hands sync off to WorkManager. */
    suspend fun pauseSync()

    /** Run the sync loop for [windowMillis] to catch up, then stop it. The
     *  WorkManager fallback worker uses this to deliver (delayed) messages while
     *  the foreground service is barred from running (ADR 0004). Restores nothing
     *  — the caller ensures the session is active first. */
    suspend fun catchUpSync(windowMillis: Long)

    fun timeline(roomId: RoomId): RoomTimeline

    suspend fun acceptInvite(roomId: RoomId): Result<Unit>
    suspend fun declineInvite(roomId: RoomId, ignoreSender: Boolean): Result<Unit>

    /** Our own Matrix user id, or null before login (used for call membership). */
    suspend fun ownUserId(): UserId?

    /** This session's device id (call membership state key + LiveKit token). */
    suspend fun deviceId(): String?

    /** A fresh Matrix OpenID token to exchange for a LiveKit JWT (docs/VOICE.md §3). */
    suspend fun openIdToken(): org.matchat.core.model.MatrixOpenIdToken?

    /** Lookup of a known address to show a name before sending — not a search. */
    suspend fun lookupProfile(address: UserId): Result<Profile>

    /** Create-room with is_direct + trusted-private-chat preset + encryption on. */
    suspend fun startDirectChat(address: UserId): Result<RoomId>

    // --- Room Info (S12) ---------------------------------------------------

    /** Read-only room header (name, topic, encryption, member count). */
    suspend fun roomDetails(roomId: RoomId): RoomDetails?

    /** All room members (joined + invited), self flagged. */
    suspend fun roomMembers(roomId: RoomId): List<RoomMemberSummary>

    suspend fun setRoomName(roomId: RoomId, name: String): Result<Unit>
    suspend fun setRoomTopic(roomId: RoomId, topic: String): Result<Unit>
    suspend fun inviteMember(roomId: RoomId, address: UserId): Result<Unit>

    /** Remove (kick) a member from the room. */
    suspend fun removeMember(roomId: RoomId, userId: UserId): Result<Unit>
    suspend fun leaveRoom(roomId: RoomId): Result<Unit>

    // --- MatrixRTC signalling primitives (docs/VOICE.md, ADR 0006) ---------
    // These are the SDK-backed raw send/observe calls spike 1 confirmed the FFI
    // supports (Room.sendStateEventRaw / sendRaw / RoomInfo.hasRoomCall). The RTC
    // protocol logic (m.call.member content, membership lifecycle, LiveKit focus)
    // lives in :core:rtc and is built on these — the SDK stays confined here.

    /** Send an arbitrary state event (e.g. `m.call.member`); returns its event id. */
    suspend fun sendStateEvent(
        roomId: RoomId,
        eventType: String,
        stateKey: String,
        jsonContent: String,
    ): Result<String>

    /** Send an arbitrary message event (e.g. the RTC ring notification). */
    suspend fun sendRawEvent(roomId: RoomId, eventType: String, jsonContent: String): Result<Unit>

    /** Whether [roomId] has an active call right now, and who is in it. */
    suspend fun activeCall(roomId: RoomId): CallState

    /**
     * Restore encryption keys from an admin-issued recovery key (S7). On success
     * the device gains the key backup + cross-signing, so previously
     * unable-to-decrypt history becomes readable (PLAN.md §6.2).
     */
    /** Fire-and-forget text send to a room, without opening a live timeline —
     *  for the notification inline-reply action (PLAN.md §6.6). */
    suspend fun sendMessage(roomId: RoomId, body: String)

    /** Mark a room read (its latest event) — for the notification action and
     *  when a notification is dismissed by opening the room. */
    suspend fun markRoomRead(roomId: RoomId)

    suspend fun recoverEncryption(recoveryKey: String): Result<Unit>

    /** Publish our own presence. The SDK only supports *setting* presence
     *  (Client.setPresence); reading other users' presence is not exposed in this
     *  SDK version, so there are no peer presence dots. Best-effort — a no-op when
     *  there is no live client. */
    suspend fun setPresence(online: Boolean)

    /** Download a media message's bytes by event id (image/video/audio/voice/file),
     *  decrypting if needed. Returns null when the source is unknown or fails. */
    suspend fun loadMedia(eventId: EventId): ByteArray?

    /** Download an avatar's bytes by its `mxc://` URI (room, member, or
     *  message-sender avatar — all the same underlying media). Returns null
     *  when the URI is invalid or the download fails; callers cache by URI
     *  (core/ui's AvatarCache) since the same avatar repeats across many
     *  rows. */
    suspend fun loadAvatar(mxcUrl: String): ByteArray?

    suspend fun logout()
}

/** A single room's timeline. The SDK paginates and dedupes; we do not. */
interface RoomTimeline {
    val items: Flow<List<TimelineItem>> // already paginated + deduped

    /** User ids currently typing in this room, excluding ourselves. */
    val typing: Flow<List<UserId>>

    /** Loads [count] older events. Returns false when the start is reached. */
    suspend fun paginateBack(count: Int = 20): Boolean

    suspend fun send(body: String)

    /** Replace the body of a previously-sent message (Timeline.edit). Only the
     *  sender can edit; the SDK rejects others. */
    suspend fun editMessage(eventId: EventId, newBody: String)

    /** Publish our typing state in this room (Room.typingNotice). */
    suspend fun sendTyping(isTyping: Boolean)

    /**
     * Uploads a local file at [path] and sends it as a media message (S9 send,
     * gated upstream by policy.mediaSend). [kind] picks the msgtype; [caption] is
     * an optional text shown with the attachment. The SDK does the encryption and
     * upload; only a filesystem path crosses this line, never an Android Uri.
     */
    suspend fun sendMedia(path: String, mimeType: String, kind: MediaKind, caption: String?)

    /** Uploads a recorded clip at [path] and sends it as a voice message (MSC3245):
     *  an audio event tagged as voice, carrying [durationMs] and a [waveform]
     *  (amplitudes 0..1) so clients render the voice bar. */
    suspend fun sendVoice(path: String, mimeType: String, durationMs: Long, waveform: List<Float>)

    suspend fun markRead(eventId: EventId)

    /** Adds [key] (an emoji) as our reaction to [eventId], or removes it if we
     *  already reacted with it — Timeline.toggleReaction is itself a toggle
     *  (Reactions round). */
    suspend fun toggleReaction(eventId: EventId, key: String)

    /**
     * Adds or removes [eventId] from the room's `m.room.pinned_events` list
     * (Pinned messages round). The SDK has no dedicated pin/unpin call (none
     * of Room's public methods is pin-named) — this reads the current list,
     * edits it, and writes it back as a plain state event, the same way
     * core/rtc's MatrixRTC signalling already does for `m.call.member`.
     *
     * Returns whether the write actually succeeded. `m.room.pinned_events`
     * needs `state_default` power level (moderator+ by default) to send —
     * a plain member's attempt is rejected by the homeserver, and this is
     * the only way the caller finds out rather than the change silently not
     * sticking (the bug this return value fixes).
     */
    suspend fun setPinned(eventId: EventId, pinned: Boolean): Boolean
}

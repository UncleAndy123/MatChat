package org.matchat.core.matrix

import kotlinx.coroutines.flow.Flow
import org.matchat.core.model.DeviceTrust
import org.matchat.core.model.EventId
import org.matchat.core.model.InviteSummary
import org.matchat.core.model.MediaKind
import org.matchat.core.model.Profile
import org.matchat.core.model.RoomId
import org.matchat.core.model.RoomSummary
import org.matchat.core.model.SyncState
import org.matchat.core.model.TimelineItem
import org.matchat.core.model.UserId

/**
 * The whole contract between the app and the Matrix Rust SDK (PLAN.md §5).
 * Everything here returns :core:model types — no SDK type ever crosses this line
 * (AGENTS.md §2). All heavy work happens on Dispatchers.IO inside the
 * implementation; callers just collect Flows and await suspend functions.
 */
interface MatrixSession {
    val rooms: Flow<List<RoomSummary>> // joined rooms only
    val invites: Flow<List<InviteSummary>> // membership == Invited
    val syncState: Flow<SyncState>
    val ownDevice: Flow<DeviceTrust>

    fun timeline(roomId: RoomId): RoomTimeline

    suspend fun acceptInvite(roomId: RoomId): Result<Unit>
    suspend fun declineInvite(roomId: RoomId, ignoreSender: Boolean): Result<Unit>

    /** Lookup of a known address to show a name before sending — not a search. */
    suspend fun lookupProfile(address: UserId): Result<Profile>

    /** Create-room with is_direct + trusted-private-chat preset + encryption on. */
    suspend fun startDirectChat(address: UserId): Result<RoomId>

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

    /** Download a media message's bytes by event id (image/video/audio/voice/file),
     *  decrypting if needed. Returns null when the source is unknown or fails. */
    suspend fun loadMedia(eventId: EventId): ByteArray?

    suspend fun logout()
}

/** A single room's timeline. The SDK paginates and dedupes; we do not. */
interface RoomTimeline {
    val items: Flow<List<TimelineItem>> // already paginated + deduped

    /** Loads [count] older events. Returns false when the start is reached. */
    suspend fun paginateBack(count: Int = 20): Boolean

    suspend fun send(body: String)

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
}

package org.matchat.core.matrix

import kotlinx.coroutines.flow.StateFlow
import org.matchat.core.model.MediaKind
import org.matchat.core.model.RoomId

/**
 * A per-room draft: unsent compose text and/or a staged attachment, kept so
 * leaving a room (or the whole app closing) doesn't silently lose either
 * (on-device report: "when exiting the thread when there is a partially
 * drafted message, save it to resume later").
 */
data class Draft(
    val text: String = "",
    val attachment: DraftAttachment? = null,
) {
    val isEmpty: Boolean get() = text.isBlank() && attachment == null
}

/**
 * Enough of a staged attachment to resume it later — mirrors
 * `feature/timeline`'s `PendingAttachment` field-for-field, but this module
 * can't depend on a feature module, so it's its own small struct;
 * `TimelineViewModel` maps between the two at the boundary. The actual bytes
 * stay wherever they already are (the app's cache dir); only the path +
 * metadata are persisted here. If the OS reclaims that cache file before the
 * room is reopened, [DraftStore.getDraft] drops the attachment (keeping the
 * text) rather than resuming a dangling path.
 */
data class DraftAttachment(
    val path: String,
    val mimeType: String,
    val kind: MediaKind,
    val displayName: String,
    val durationMs: Long? = null,
    val waveform: List<Float>? = null,
)

/**
 * Persists [Draft]s across app restarts, keyed by [RoomId]. Lives in
 * `:core:matrix` (not `:core:ui`, `UserPreferences`'s home) because both
 * `TimelineViewModel` and `RoomListViewModel` read/write it directly
 * (AGENTS.md §3: a ViewModel never depends on `:core:ui`).
 */
interface DraftStore {
    /** Every room with a live (non-empty) draft right now, keyed by
     *  [RoomId.value] — `RoomListViewModel` combines this with
     *  `MatrixSession.rooms` to show a "Draft: …" preview instead of the
     *  room's real last message. */
    val drafts: StateFlow<Map<String, Draft>>

    /** The saved draft for [roomId], or null if there is none. */
    fun getDraft(roomId: RoomId): Draft?

    /** Persist [draft] for [roomId], replacing any previous one. */
    suspend fun setDraft(roomId: RoomId, draft: Draft)

    /** Remove any saved draft for [roomId] (sent, or the compose box and any
     *  staged attachment were both cleared). */
    suspend fun clearDraft(roomId: RoomId)

    /** Wipe every saved draft — called on sign-out so no message content from
     *  the previous account survives into the next login (holder.logout()). */
    suspend fun clearAll()
}

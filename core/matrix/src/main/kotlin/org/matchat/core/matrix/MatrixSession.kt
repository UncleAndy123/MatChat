package org.matchat.core.matrix

import kotlinx.coroutines.flow.Flow
import org.matchat.core.model.DeviceTrust
import org.matchat.core.model.EventId
import org.matchat.core.model.InviteSummary
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

    suspend fun logout()
}

/** A single room's timeline. The SDK paginates and dedupes; we do not. */
interface RoomTimeline {
    val items: Flow<List<TimelineItem>> // already paginated + deduped

    /** Loads [count] older events. Returns false when the start is reached. */
    suspend fun paginateBack(count: Int = 20): Boolean

    suspend fun send(body: String)

    suspend fun markRead(eventId: EventId)
}

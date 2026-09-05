package org.matchat.core.matrix.internal

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.matchat.core.matrix.MatrixSession
import org.matchat.core.matrix.RoomTimeline
import org.matchat.core.model.DeviceTrust
import org.matchat.core.model.EventId
import org.matchat.core.model.InviteSummary
import org.matchat.core.model.Profile
import org.matchat.core.model.RoomId
import org.matchat.core.model.RoomSummary
import org.matchat.core.model.SyncState
import org.matchat.core.model.TimelineItem
import org.matchat.core.model.UserId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M0 placeholder: empty, non-crashing session so every stub screen is reachable
 * and renders its empty state on the reference device (M0 definition of done).
 * Replaced in M1 by the real SDK-backed session in this same package; nothing
 * outside :core:matrix changes when it is, because the contract is unchanged.
 */
@Singleton
internal class StubMatrixSession @Inject constructor() : MatrixSession {
    override val rooms: Flow<List<RoomSummary>> = flowOf(emptyList())
    override val invites: Flow<List<InviteSummary>> = flowOf(emptyList())
    override val syncState: Flow<SyncState> = MutableStateFlow(SyncState.IDLE)
    override val ownDevice: Flow<DeviceTrust> = MutableStateFlow(DeviceTrust.UNVERIFIED)

    override fun timeline(roomId: RoomId): RoomTimeline = StubRoomTimeline()

    override suspend fun acceptInvite(roomId: RoomId): Result<Unit> = Result.success(Unit)

    override suspend fun declineInvite(roomId: RoomId, ignoreSender: Boolean): Result<Unit> =
        Result.success(Unit)

    override suspend fun lookupProfile(address: UserId): Result<Profile> =
        Result.success(Profile(address, displayName = null))

    override suspend fun startDirectChat(address: UserId): Result<RoomId> =
        Result.success(RoomId("!stub:local"))

    override suspend fun logout() = Unit
}

internal class StubRoomTimeline : RoomTimeline {
    override val items: Flow<List<TimelineItem>> = flowOf(emptyList())
    override suspend fun paginateBack(count: Int): Boolean = false
    override suspend fun send(body: String) = Unit
    override suspend fun markRead(eventId: EventId) = Unit
}

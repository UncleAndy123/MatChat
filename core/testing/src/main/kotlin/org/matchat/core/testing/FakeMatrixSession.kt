package org.matchat.core.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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

/**
 * A [MatrixSession] tests can drive by mutating the state flows. Assert the state
 * that results, never that a method was called N times (AGENTS.md §6).
 */
class FakeMatrixSession(
    val roomsFlow: MutableStateFlow<List<RoomSummary>> = MutableStateFlow(emptyList()),
    val invitesFlow: MutableStateFlow<List<InviteSummary>> = MutableStateFlow(emptyList()),
    val syncFlow: MutableStateFlow<SyncState> = MutableStateFlow(SyncState.IDLE),
    val deviceFlow: MutableStateFlow<DeviceTrust> = MutableStateFlow(DeviceTrust.VERIFIED),
) : MatrixSession {

    override val rooms: Flow<List<RoomSummary>> = roomsFlow
    override val invites: Flow<List<InviteSummary>> = invitesFlow
    override val syncState: Flow<SyncState> = syncFlow
    override val ownDevice: Flow<DeviceTrust> = deviceFlow

    val timelines = mutableMapOf<RoomId, FakeTimeline>()
    var profileResult: (UserId) -> Result<Profile> = { Result.success(Profile(it, null)) }
    var startDirectChatResult: (UserId) -> Result<RoomId> = { Result.success(RoomId("!new:local")) }

    override fun timeline(roomId: RoomId): RoomTimeline =
        timelines.getOrPut(roomId) { FakeTimeline() }

    override suspend fun acceptInvite(roomId: RoomId): Result<Unit> {
        invitesFlow.value = invitesFlow.value.filterNot { it.roomId == roomId }
        return Result.success(Unit)
    }

    override suspend fun declineInvite(roomId: RoomId, ignoreSender: Boolean): Result<Unit> {
        invitesFlow.value = invitesFlow.value.filterNot { it.roomId == roomId }
        return Result.success(Unit)
    }

    override suspend fun lookupProfile(address: UserId): Result<Profile> = profileResult(address)

    override suspend fun startDirectChat(address: UserId): Result<RoomId> =
        startDirectChatResult(address)

    var recoverResult: Result<Unit> = Result.success(Unit)
    var lastRecoveryKey: String? = null

    override suspend fun recoverEncryption(recoveryKey: String): Result<Unit> {
        lastRecoveryKey = recoveryKey
        return recoverResult
    }

    override suspend fun logout() = Unit
}

/** A [RoomTimeline] backed by a mutable list; [emit] pushes new item lists. */
class FakeTimeline(
    val itemsFlow: MutableStateFlow<List<TimelineItem>> = MutableStateFlow(emptyList()),
) : RoomTimeline {
    override val items: Flow<List<TimelineItem>> = itemsFlow

    val sent = mutableListOf<String>()
    var canPaginate = false

    override suspend fun paginateBack(count: Int): Boolean = canPaginate
    override suspend fun send(body: String) { sent += body }
    override suspend fun markRead(eventId: EventId) = Unit

    fun emit(items: List<TimelineItem>) { itemsFlow.value = items }
}

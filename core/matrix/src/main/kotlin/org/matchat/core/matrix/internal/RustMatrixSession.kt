package org.matchat.core.matrix.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
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
import org.matchat.core.model.UserId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app-facing session, backed by [RustMatrixClientHolder]. Joined rooms and
 * sync state come straight from the holder's flows. Invitations, DM creation and
 * device trust are staged follow-ups within M1 (marked below); the joined room
 * list and timelines are wired first.
 */
@Singleton
internal class RustMatrixSession @Inject constructor(
    private val holder: RustMatrixClientHolder,
) : MatrixSession {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val rooms: Flow<List<RoomSummary>> = holder.rooms
    override val syncState: Flow<SyncState> = holder.syncState

    // FFI follow-up: invites = rooms filtered by Invited membership; device trust
    // arrives with the M4 verification wiring.
    override val invites: Flow<List<InviteSummary>> = flowOf(emptyList())
    override val ownDevice: Flow<DeviceTrust> = MutableStateFlow(DeviceTrust.VERIFIED)

    override fun timeline(roomId: RoomId): RoomTimeline =
        RustRoomTimeline(holder.roomFor(roomId), scope)

    // FFI follow-up: wire to the invited-room join()/leave() once the invites
    // flow above is populated. Invites are empty in this M1 step, so these are
    // not yet reachable from the UI.
    override suspend fun acceptInvite(roomId: RoomId): Result<Unit> = Result.success(Unit)

    override suspend fun declineInvite(roomId: RoomId, ignoreSender: Boolean): Result<Unit> =
        Result.success(Unit)

    override suspend fun lookupProfile(address: UserId): Result<Profile> = runCatching {
        // A lookup of a known address, never a search (AGENTS.md §0).
        val profile = holder.requireClient().getProfile(address.value)
        Profile(address, profile.displayName)
    }.recoverCatching {
        // A server may not publish profiles — surface the address, not an error.
        Profile(address, null)
    }

    // FFI follow-up: createRoom(CreateRoomParameters(isDirect, encrypted, preset,
    // invite=[address])). Staged after the read path lands.
    override suspend fun startDirectChat(address: UserId): Result<RoomId> =
        Result.failure(NotImplementedError("DM creation lands in the next M1 step"))

    override suspend fun recoverEncryption(recoveryKey: String): Result<Unit> =
        runCatching { holder.recover(recoveryKey.trim()) }

    override suspend fun loadMedia(eventId: EventId): ByteArray? = withContext(Dispatchers.IO) {
        val source = MediaRegistry.get(eventId.value) ?: return@withContext null
        runCatching { holder.requireClient().getMediaContent(source) }.getOrNull()
    }

    override suspend fun logout() = holder.logout()
}

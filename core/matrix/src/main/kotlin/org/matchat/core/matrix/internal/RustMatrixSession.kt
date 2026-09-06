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
import org.matrix.rustcomponents.sdk.CreateRoomParameters
import org.matrix.rustcomponents.sdk.RoomPreset
import org.matrix.rustcomponents.sdk.RoomVisibility
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
        RustRoomTimeline(holder.roomFor(roomId), scope, holder.ownUserId())

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

    /** Create (or reuse) an encrypted 1:1 room and invite [address] (S21). */
    override suspend fun startDirectChat(address: UserId): Result<RoomId> =
        withContext(Dispatchers.IO) {
            runCatching {
                val client = holder.requireClient()
                // Reuse the existing DM with this person if the server has one.
                val existing = runCatching { client.getDmRoom(address.value)?.id() }.getOrNull()
                val roomId = existing ?: client.createRoom(
                    CreateRoomParameters(
                        name = null,
                        topic = null,
                        isEncrypted = true,
                        isDirect = true,
                        visibility = RoomVisibility.Private,
                        preset = RoomPreset.TRUSTED_PRIVATE_CHAT,
                        invite = listOf(address.value),
                        avatar = null,
                        powerLevelContentOverride = null,
                        joinRuleOverride = null,
                        historyVisibilityOverride = null,
                        canonicalAlias = null,
                        isSpace = false,
                    ),
                )
                RoomId(roomId)
            }
        }

    override suspend fun sendMessage(roomId: RoomId, body: String) = withContext(Dispatchers.IO) {
        val room = holder.roomFor(roomId) ?: return@withContext
        // A transient timeline (no listener attached) is enough for a one-shot send.
        val tl = room.timeline()
        runCatching {
            tl.send(org.matrix.rustcomponents.sdk.messageEventContentFromMarkdown(body))
        }
        Unit
    }

    override suspend fun markRoomRead(roomId: RoomId) = withContext(Dispatchers.IO) {
        val room = holder.roomFor(roomId) ?: return@withContext
        val tl = room.timeline()
        runCatching { tl.markAsRead(org.matrix.rustcomponents.sdk.ReceiptType.READ) }
        Unit
    }

    override suspend fun setPresence(online: Boolean) = withContext(Dispatchers.IO) {
        // FFI: setPresence(state, bool). The trailing flag is version-specific; false
        // is the safe default. No-op (via runCatching) when there is no live client.
        val state = if (online) {
            org.matrix.rustcomponents.sdk.PresenceState.ONLINE
        } else {
            org.matrix.rustcomponents.sdk.PresenceState.UNAVAILABLE
        }
        runCatching { holder.requireClient().setPresence(state, false) }
        Unit
    }

    override suspend fun recoverEncryption(recoveryKey: String): Result<Unit> =
        runCatching { holder.recover(recoveryKey.trim()) }

    override suspend fun loadMedia(eventId: EventId): ByteArray? = withContext(Dispatchers.IO) {
        val source = MediaRegistry.get(eventId.value) ?: return@withContext null
        runCatching { holder.requireClient().getMediaContent(source) }.getOrNull()
    }

    override suspend fun logout() = holder.logout()
}

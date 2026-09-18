package org.matchat.core.matrix.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import org.matchat.core.matrix.MatrixSession
import org.matchat.core.matrix.RoomTimeline
import org.matchat.core.model.CallState
import org.matchat.core.model.DeviceTrust
import org.matchat.core.model.EventId
import org.matchat.core.model.InviteSummary
import org.matchat.core.model.Membership
import org.matchat.core.model.Profile
import org.matchat.core.model.RoomDetails
import org.matchat.core.model.RoomId
import org.matchat.core.model.RoomMemberSummary
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

    override fun isActive(): Boolean = holder.isActive()

    override suspend fun ensureSyncing() = holder.startSync()

    override suspend fun pauseSync() = holder.stopSync()

    override suspend fun catchUpSync(windowMillis: Long) = holder.catchUpSync(windowMillis)

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

    override suspend fun declineInvite(roomId: RoomId, ignoreSender: Boolean): Result<Unit> = Result.success(Unit)

    override suspend fun ownUserId(): UserId? = withContext(Dispatchers.IO) { holder.ownUserId()?.let { UserId(it) } }

    override suspend fun deviceId(): String? =
        withContext(Dispatchers.IO) { runCatching { holder.requireClient().deviceId() }.getOrNull() }

    override suspend fun openIdToken(): org.matchat.core.model.MatrixOpenIdToken? =
        withContext(Dispatchers.IO) {
            runCatching {
                val t = holder.requireClient().requestOpenidToken()
                org.matchat.core.model.MatrixOpenIdToken(
                    accessToken = t.accessToken,
                    tokenType = t.tokenType,
                    matrixServerName = t.matrixServerName,
                    expiresInSeconds = t.expiresInSeconds.toLong(),
                )
            }.getOrNull()
        }

    override suspend fun lookupProfile(address: UserId): Result<Profile> = runCatching {
        // A lookup of a known address, never a search (AGENTS.md §0).
        val profile = holder.requireClient().getProfile(address.value)
        Profile(address, profile.displayName)
    }.recoverCatching {
        // A server may not publish profiles — surface the address, not an error.
        Profile(address, null)
    }

    /** Create (or reuse) an encrypted 1:1 room and invite [address] (S21). */
    override suspend fun startDirectChat(address: UserId): Result<RoomId> = withContext(Dispatchers.IO) {
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

    override suspend fun roomDetails(roomId: RoomId): RoomDetails? = withContext(Dispatchers.IO) {
        val room = holder.roomFor(roomId) ?: return@withContext null
        val info = runCatching { room.roomInfo() }.getOrNull()
        RoomDetails(
            id = roomId,
            name = info?.displayName ?: room.displayName() ?: room.id(),
            topic = runCatching { info?.topic }.getOrNull(),
            isEncrypted = runCatching { room.isEncrypted() }.getOrDefault(true),
            isDirect = runCatching { info?.isDirect ?: false }.getOrDefault(false),
            memberCount = runCatching { info?.joinedMembersCount?.toInt() }.getOrNull() ?: 0,
        )
    }

    override suspend fun roomMembers(roomId: RoomId): List<RoomMemberSummary> = withContext(Dispatchers.IO) {
        val room = holder.roomFor(roomId) ?: return@withContext emptyList()
        val own = holder.ownUserId()
        val out = mutableListOf<RoomMemberSummary>()
        runCatching {
            val iterator = room.members()
            while (true) {
                val chunk = iterator.nextChunk(MEMBER_PAGE_SIZE) ?: break
                if (chunk.isEmpty()) break
                chunk.forEach { m ->
                    out += RoomMemberSummary(
                        userId = UserId(m.userId),
                        displayName = m.displayName,
                        membership = membershipOf(m.membership),
                        isSelf = m.userId == own,
                        avatarUrl = m.avatarUrl,
                    )
                }
            }
            iterator.close()
        }
        out
    }

    override suspend fun setRoomName(roomId: RoomId, name: String): Result<Unit> = roomOp(roomId) { it.setName(name) }

    override suspend fun setRoomTopic(roomId: RoomId, topic: String): Result<Unit> =
        roomOp(roomId) { it.setTopic(topic) }

    override suspend fun inviteMember(roomId: RoomId, address: UserId): Result<Unit> =
        roomOp(roomId) { it.inviteUserById(address.value) }

    override suspend fun removeMember(roomId: RoomId, userId: UserId): Result<Unit> =
        roomOp(roomId) { it.kickUser(userId.value, null) }

    override suspend fun leaveRoom(roomId: RoomId): Result<Unit> = roomOp(roomId) { it.leave() }

    override suspend fun sendStateEvent(
        roomId: RoomId,
        eventType: String,
        stateKey: String,
        jsonContent: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        val room = holder.roomFor(roomId)
            ?: return@withContext Result.failure(IllegalStateException("room not found"))
        runCatching { room.sendStateEventRaw(eventType, stateKey, jsonContent) }
    }

    override suspend fun sendRawEvent(roomId: RoomId, eventType: String, jsonContent: String): Result<Unit> =
        roomOp(roomId) { it.sendRaw(eventType, jsonContent) }

    override suspend fun activeCall(roomId: RoomId): CallState = withContext(Dispatchers.IO) {
        val room = holder.roomFor(roomId) ?: return@withContext CallState.NONE
        val info = runCatching { room.roomInfo() }.getOrNull() ?: return@withContext CallState.NONE
        CallState(
            hasActiveCall = runCatching { info.hasRoomCall }.getOrDefault(false),
            participantIds = runCatching { info.activeRoomCallParticipants }.getOrNull()
                .orEmpty().map { UserId(it) },
        )
    }

    private suspend fun roomOp(
        roomId: RoomId,
        block: suspend (org.matrix.rustcomponents.sdk.Room) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val room = holder.roomFor(roomId)
            ?: return@withContext Result.failure(IllegalStateException("room not found"))
        runCatching { block(room) }
    }

    private fun membershipOf(state: org.matrix.rustcomponents.sdk.MembershipState): Membership = when (state) {
        is org.matrix.rustcomponents.sdk.MembershipState.Join -> Membership.JOINED
        is org.matrix.rustcomponents.sdk.MembershipState.Invite -> Membership.INVITED
        is org.matrix.rustcomponents.sdk.MembershipState.Leave -> Membership.LEFT
        is org.matrix.rustcomponents.sdk.MembershipState.Ban -> Membership.BANNED
        is org.matrix.rustcomponents.sdk.MembershipState.Knock -> Membership.KNOCKING
        else -> Membership.OTHER
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

    // Avatars (Avatars round): an avatarUrl from RoomMember/RoomInfo/Room
    // arrives as a bare `mxc://` string, not a pre-wrapped MediaSource like
    // message attachments get in Mappers.mediaOf — MediaSource.fromUrl bridges
    // it. Deliberately getMediaContent, not getMediaThumbnail: the thumbnail
    // API's exact signature couldn't be verified against the pinned SDK build
    // in this sandbox (no local copy — see the plan's SDK-research caveat),
    // while getMediaContent is already proven by loadMedia above; AvatarCache
    // (core:ui) downsamples client-side instead.
    override suspend fun loadAvatar(mxcUrl: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val source = org.matrix.rustcomponents.sdk.MediaSource.fromUrl(mxcUrl)
            holder.requireClient().getMediaContent(source)
        }.getOrNull()
    }

    override suspend fun logout() = holder.logout()

    private companion object {
        const val MEMBER_PAGE_SIZE: UInt = 50u
    }
}

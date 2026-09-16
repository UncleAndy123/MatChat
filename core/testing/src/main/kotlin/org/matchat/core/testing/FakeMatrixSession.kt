package org.matchat.core.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.matchat.core.matrix.MatrixSession
import org.matchat.core.matrix.RoomTimeline
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

    var active: Boolean = true
    override fun isActive(): Boolean = active

    val timelines = mutableMapOf<RoomId, FakeTimeline>()
    var profileResult: (UserId) -> Result<Profile> = { Result.success(Profile(it, null)) }
    var startDirectChatResult: (UserId) -> Result<RoomId> = { Result.success(RoomId("!new:local")) }

    override fun timeline(roomId: RoomId): RoomTimeline = timelines.getOrPut(roomId) { FakeTimeline() }

    override suspend fun acceptInvite(roomId: RoomId): Result<Unit> {
        invitesFlow.value = invitesFlow.value.filterNot { it.roomId == roomId }
        return Result.success(Unit)
    }

    override suspend fun declineInvite(roomId: RoomId, ignoreSender: Boolean): Result<Unit> {
        invitesFlow.value = invitesFlow.value.filterNot { it.roomId == roomId }
        return Result.success(Unit)
    }

    var ownUser: UserId? = UserId("@me:local")
    override suspend fun ownUserId(): UserId? = ownUser

    var deviceIdValue: String? = "DEVICE"
    var openIdTokenValue: org.matchat.core.model.MatrixOpenIdToken? = null
    override suspend fun deviceId(): String? = deviceIdValue
    override suspend fun openIdToken(): org.matchat.core.model.MatrixOpenIdToken? = openIdTokenValue

    override suspend fun lookupProfile(address: UserId): Result<Profile> = profileResult(address)

    override suspend fun startDirectChat(address: UserId): Result<RoomId> = startDirectChatResult(address)

    var recoverResult: Result<Unit> = Result.success(Unit)
    var lastRecoveryKey: String? = null

    override suspend fun recoverEncryption(recoveryKey: String): Result<Unit> {
        lastRecoveryKey = recoveryKey
        return recoverResult
    }

    var mediaBytes: ByteArray? = null

    override suspend fun loadMedia(eventId: EventId): ByteArray? = mediaBytes

    var avatarBytes: ByteArray? = null
    val avatarLoads = mutableListOf<String>()

    override suspend fun loadAvatar(mxcUrl: String): ByteArray? {
        avatarLoads += mxcUrl
        return avatarBytes
    }

    val sentMessages = mutableListOf<Pair<RoomId, String>>()
    val readRooms = mutableListOf<RoomId>()
    var lastPresenceOnline: Boolean? = null

    override suspend fun sendMessage(roomId: RoomId, body: String) {
        sentMessages += roomId to body
    }

    override suspend fun markRoomRead(roomId: RoomId) {
        readRooms += roomId
    }

    override suspend fun setPresence(online: Boolean) {
        lastPresenceOnline = online
    }

    var roomDetailsResult: RoomDetails? = null
    var members: List<RoomMemberSummary> = emptyList()
    val invited = mutableListOf<Pair<RoomId, UserId>>()
    val removed = mutableListOf<Pair<RoomId, UserId>>()
    val leftRooms = mutableListOf<RoomId>()
    val nameChanges = mutableListOf<Pair<RoomId, String>>()
    val topicChanges = mutableListOf<Pair<RoomId, String>>()

    override suspend fun roomDetails(roomId: RoomId): RoomDetails? = roomDetailsResult
    override suspend fun roomMembers(roomId: RoomId): List<RoomMemberSummary> = members

    override suspend fun setRoomName(roomId: RoomId, name: String): Result<Unit> {
        nameChanges += roomId to name
        return Result.success(Unit)
    }

    override suspend fun setRoomTopic(roomId: RoomId, topic: String): Result<Unit> {
        topicChanges += roomId to topic
        return Result.success(Unit)
    }

    override suspend fun inviteMember(roomId: RoomId, address: UserId): Result<Unit> {
        invited += roomId to address
        return Result.success(Unit)
    }

    override suspend fun removeMember(roomId: RoomId, userId: UserId): Result<Unit> {
        removed += roomId to userId
        return Result.success(Unit)
    }

    override suspend fun leaveRoom(roomId: RoomId): Result<Unit> {
        leftRooms += roomId
        return Result.success(Unit)
    }

    val stateEvents = mutableListOf<Triple<RoomId, String, String>>()
    val rawEvents = mutableListOf<Triple<RoomId, String, String>>()
    var callState: CallState = CallState.NONE

    override suspend fun sendStateEvent(
        roomId: RoomId,
        eventType: String,
        stateKey: String,
        jsonContent: String,
    ): Result<String> {
        stateEvents += Triple(roomId, eventType, jsonContent)
        return Result.success("\$evt")
    }

    override suspend fun sendRawEvent(roomId: RoomId, eventType: String, jsonContent: String): Result<Unit> {
        rawEvents += Triple(roomId, eventType, jsonContent)
        return Result.success(Unit)
    }

    override suspend fun activeCall(roomId: RoomId): CallState = callState

    override suspend fun logout() = Unit
}

/** A [RoomTimeline] backed by a mutable list; [emit] pushes new item lists. */
class FakeTimeline(
    val itemsFlow: MutableStateFlow<List<TimelineItem>> = MutableStateFlow(emptyList()),
    val typingFlow: MutableStateFlow<List<UserId>> = MutableStateFlow(emptyList()),
) : RoomTimeline {
    override val items: Flow<List<TimelineItem>> = itemsFlow
    override val typing: Flow<List<UserId>> = typingFlow

    val sent = mutableListOf<String>()

    /** Every sendMedia call in full — path alone isn't enough to assert a
     *  caption reached the timeline (Attachment staging round). */
    data class SentMedia(val path: String, val mimeType: String, val kind: MediaKind, val caption: String?)
    val sentMediaCalls = mutableListOf<SentMedia>()

    /** Paths only, kept for callers that don't care about the rest. */
    val sentMedia: List<String> get() = sentMediaCalls.map { it.path }

    data class SentVoice(val path: String, val mimeType: String, val durationMs: Long, val waveform: List<Float>)
    val sentVoiceCalls = mutableListOf<SentVoice>()
    val sentVoice: List<String> get() = sentVoiceCalls.map { it.path }
    val edits = mutableListOf<Pair<EventId, String>>()
    val typingNotices = mutableListOf<Boolean>()
    var canPaginate = false

    override suspend fun paginateBack(count: Int): Boolean = canPaginate
    override suspend fun send(body: String) {
        sent += body
    }
    override suspend fun editMessage(eventId: EventId, newBody: String) {
        edits += eventId to newBody
    }
    override suspend fun sendTyping(isTyping: Boolean) {
        typingNotices += isTyping
    }

    override suspend fun sendMedia(path: String, mimeType: String, kind: MediaKind, caption: String?) {
        sentMediaCalls += SentMedia(path, mimeType, kind, caption)
    }

    override suspend fun sendVoice(path: String, mimeType: String, durationMs: Long, waveform: List<Float>) {
        sentVoiceCalls += SentVoice(path, mimeType, durationMs, waveform)
    }

    override suspend fun markRead(eventId: EventId) = Unit

    val toggledReactions = mutableListOf<Pair<EventId, String>>()

    override suspend fun toggleReaction(eventId: EventId, key: String) {
        toggledReactions += eventId to key
    }

    val pinnedChanges = mutableListOf<Pair<EventId, Boolean>>()
    var pinnedResult: Boolean = true

    override suspend fun setPinned(eventId: EventId, pinned: Boolean): Boolean {
        pinnedChanges += eventId to pinned
        return pinnedResult
    }

    fun emit(items: List<TimelineItem>) { itemsFlow.value = items }
    fun emitTyping(users: List<UserId>) { typingFlow.value = users }
}

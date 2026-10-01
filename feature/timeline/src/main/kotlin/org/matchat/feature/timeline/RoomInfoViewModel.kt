package org.matchat.feature.timeline

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.matchat.core.matrix.MatrixSession
import org.matchat.core.model.Membership
import org.matchat.core.model.RoomDetails
import org.matchat.core.model.RoomId
import org.matchat.core.model.RoomMemberSummary
import org.matchat.core.model.UserId
import org.matchat.core.model.notify.BundledSoundInstaller
import org.matchat.core.model.notify.RoomNotificationSounds
import org.matchat.core.model.notify.RoomSoundChoice
import org.matchat.core.model.notify.RoomSoundPick
import javax.inject.Inject

/** S12 Room Info: shows name/topic/encryption/members and applies basic edits. */
@HiltViewModel
class RoomInfoViewModel @Inject constructor(
    private val session: MatrixSession,
    private val roomSounds: RoomNotificationSounds,
    private val bundledSounds: BundledSoundInstaller,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val roomId = RoomId(requireNotNull(savedStateHandle["roomId"]))

    private val _state = MutableStateFlow(RoomInfoState())
    val state: StateFlow<RoomInfoState> = _state.asStateFlow()

    private val navChannel = Channel<RoomInfoNav>(Channel.BUFFERED)
    val navEvents: Flow<RoomInfoNav> = navChannel.receiveAsFlow()

    // Last loaded room data, so a sound change re-renders without a reload.
    private var details: RoomDetails? = null
    private var members: List<RoomMemberSummary> = emptyList()

    init {
        reload()
        viewModelScope.launch {
            roomSounds.overrides.collect { _state.update { it.copy(rows = rows(details, members)) } }
        }
    }

    fun reload() {
        viewModelScope.launch {
            details = session.roomDetails(roomId)
            members = session.roomMembers(roomId)
            _state.update { it.copy(title = details?.name.orEmpty(), rows = rows(details, members)) }
        }
    }

    /** CENTER on the Notification sound row: copy MatChat's bundled sounds
     *  out first (asking for storage access on Android 7–9), then the picker. */
    fun openSoundPicker() {
        viewModelScope.launch {
            if (bundledSounds.needsStoragePermission) {
                navChannel.send(RoomInfoNav.RequestStoragePermission)
            } else {
                launchPicker()
            }
        }
    }

    /** The storage-permission answer. Refused: say so, open the picker anyway. */
    fun onStoragePermissionResult(granted: Boolean) {
        viewModelScope.launch {
            if (!granted) navChannel.send(RoomInfoNav.Toast(ToastKey.SOUNDS_NEED_ACCESS))
            launchPicker()
        }
    }

    private suspend fun launchPicker() {
        bundledSounds.install()
        navChannel.send(RoomInfoNav.OpenSoundPicker(currentSoundUri()))
    }

    /** This room's own sound uri, for pre-selecting the picker; null = app sound. */
    private fun currentSoundUri(): String? = roomSounds.overrides.value[roomId]?.uri

    /** The picker's answer ([pickedUri] null = Silent), see [RoomSoundPick]. */
    fun onSoundPicked(pickedUri: String?, cancelled: Boolean, systemDefaultUri: String) {
        val current = currentSoundUri()
        val next = RoomSoundPick.resolve(pickedUri, cancelled, current, systemDefaultUri)
        if (cancelled || next == current) return
        viewModelScope.launch { roomSounds.set(roomId, next) }
    }

    fun setName(name: String) = edit { session.setRoomName(roomId, name.trim()) }
    fun setTopic(topic: String) = edit { session.setRoomTopic(roomId, topic.trim()) }

    fun addMember(rawAddress: String) {
        val address = rawAddress.trim()
        if (!address.startsWith("@") || !address.contains(':')) {
            emit(RoomInfoNav.Toast(ToastKey.BAD_ADDRESS))
            return
        }
        edit { session.inviteMember(roomId, UserId(address)) }
    }

    fun removeMember(userId: UserId) = edit { session.removeMember(roomId, userId) }

    fun leave() {
        _state.update { it.copy(isBusy = true) }
        viewModelScope.launch {
            session.leaveRoom(roomId)
            navChannel.send(RoomInfoNav.Left)
        }
    }

    /** Runs [op], then reloads so the UI reflects the server state. */
    private fun edit(op: suspend () -> Result<Unit>) {
        _state.update { it.copy(isBusy = true) }
        viewModelScope.launch {
            val result = op()
            _state.update { it.copy(isBusy = false) }
            if (result.isFailure) emit(RoomInfoNav.Toast(ToastKey.EDIT_FAILED)) else reload()
        }
    }

    private fun emit(nav: RoomInfoNav) {
        viewModelScope.launch { navChannel.send(nav) }
    }

    /** Download an avatar's bytes by its `mxc://` URI (Avatars round). */
    suspend fun loadAvatar(mxcUrl: String): ByteArray? = session.loadAvatar(mxcUrl)

    fun roomId(): RoomId = roomId

    private fun rows(details: RoomDetails?, members: List<RoomMemberSummary>): List<RoomInfoRow> {
        val rows = mutableListOf<RoomInfoRow>()
        rows += RoomInfoRow.Field(KEY_NAME, "Name", details?.name.orEmpty())
        rows += RoomInfoRow.Field(KEY_TOPIC, "Topic", details?.topic.orEmpty())
        rows += RoomInfoRow.Info("Encryption", if (details?.isEncrypted == true) "On" else "Off")
        rows += RoomInfoRow.Sound(RoomSoundChoice.of(roomSounds.overrides.value[roomId]))
        rows += RoomInfoRow.Section("Members (${members.count { it.membership == Membership.JOINED }})")
        members.filter { it.membership == Membership.JOINED || it.membership == Membership.INVITED }
            .sortedBy { it.label.lowercase() }
            .forEach { m ->
                val sub = when {
                    m.isSelf -> "You"
                    m.membership == Membership.INVITED -> "Invited"
                    else -> m.userId.value
                }
                rows += RoomInfoRow.Member(m.userId, m.label, sub, m.isSelf, m.avatarUrl)
            }
        rows += RoomInfoRow.Action(KEY_PINNED, "Pinned messages")
        rows += RoomInfoRow.Action(KEY_ADD, "Add member")
        rows += RoomInfoRow.Action(KEY_LEAVE, "Leave room")
        return rows
    }

    companion object {
        const val KEY_NAME = "name"
        const val KEY_TOPIC = "topic"
        const val KEY_PINNED = "pinned"
        const val KEY_ADD = "add"
        const val KEY_LEAVE = "leave"
    }
}

sealed interface RoomInfoNav {
    data object Left : RoomInfoNav
    data class Toast(val key: ToastKey) : RoomInfoNav

    /** Android 7–9: ask for storage access to copy the bundled sounds out. */
    data object RequestStoragePermission : RoomInfoNav

    /** Open the system sound picker, preselecting [currentUri] (null = app sound). */
    data class OpenSoundPicker(val currentUri: String?) : RoomInfoNav
}

enum class ToastKey { BAD_ADDRESS, EDIT_FAILED, SOUNDS_NEED_ACCESS }

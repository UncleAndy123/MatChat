package org.matchat.feature.invites

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.matchat.core.matrix.MatrixSession
import org.matchat.core.model.JoinOutcome
import org.matchat.core.model.JoinRule
import org.matchat.core.model.PreviewMembership
import org.matchat.core.model.RoomId
import org.matchat.core.model.RoomLink
import org.matchat.core.model.RoomPreview
import org.matchat.core.policy.PolicyProvider
import javax.inject.Inject

/**
 * S28. Previews the linked room, then joins it, or — when the room needs an
 * invitation and allows knocking — asks to join (the 403 "You are not invited
 * to this room" case). The link names one known room; nothing here searches
 * (AGENTS.md §0). Policy is observed, not read once: a push that blocks the
 * room's server hides the button immediately.
 */
@HiltViewModel
class JoinRoomViewModel @Inject constructor(
    private val session: MatrixSession,
    policyProvider: PolicyProvider,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val link: RoomLink? = savedStateHandle.get<String>("link")?.let(RoomLink::parse)
    private var roomId: RoomId? = null
    private var isSpace = false

    private val base = MutableStateFlow(
        JoinRoomState(name = link?.idOrAlias.orEmpty(), address = link?.idOrAlias.orEmpty()),
    )

    val state: StateFlow<JoinRoomState> =
        combine(base, policyProvider.policy) { s, policy ->
            val domain = link?.domain
            if (domain != null && !policy.allowsDomain(domain)) {
                s.copy(blockedDomain = domain, button = JoinButton.NONE, note = null, error = null)
            } else {
                s
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, base.value)

    private val navChannel = Channel<JoinRoomNav>(Channel.BUFFERED)
    val navEvents: Flow<JoinRoomNav> = navChannel.receiveAsFlow()

    init {
        if (link == null) {
            base.value = JoinRoomState(isLoading = false, note = JoinNote.BAD_LINK)
        } else {
            viewModelScope.launch { load(link) }
        }
    }

    fun onAction(action: JoinRoomAction) {
        when (action) {
            JoinRoomAction.Activate -> activate()
            is JoinRoomAction.SendRequest -> sendRequest(action.message)
        }
    }

    private suspend fun load(link: RoomLink) {
        session.previewRoom(link).fold(
            onSuccess = { preview ->
                roomId = preview.roomId
                isSpace = preview.isSpace
                base.update { it.withPreview(preview) }
            },
            // No preview (the server may not offer one): still let the user try.
            onFailure = {
                base.update { it.copy(isLoading = false, note = JoinNote.NO_PREVIEW, button = JoinButton.JOIN) }
            },
        )
    }

    private fun activate() {
        val current = state.value
        if (link == null || current.isBusy || current.blockedDomain != null) return
        when (current.button) {
            JoinButton.JOIN -> join(link)
            JoinButton.ASK -> viewModelScope.launch { navChannel.send(JoinRoomNav.PromptForMessage) }
            JoinButton.OPEN -> roomId?.let { viewModelScope.launch { navChannel.send(openNav(it)) } }
            JoinButton.NONE -> Unit
        }
    }

    private fun join(link: RoomLink) {
        base.update { it.copy(isBusy = true, error = null) }
        viewModelScope.launch {
            when (val outcome = session.joinRoom(link)) {
                is JoinOutcome.Joined -> {
                    // Wait (briefly) for sync to list the room or space, so the
                    // next screen opens on something the session knows about.
                    withTimeoutOrNull(ROOM_WAIT_MS) {
                        combine(session.rooms, session.spaces) { rooms, spaces ->
                            rooms.any { it.id == outcome.roomId } || spaces.any { it.id == outcome.roomId }
                        }.first { it }
                    }
                    base.update { it.copy(isBusy = false) }
                    navChannel.send(openNav(outcome.roomId))
                }
                JoinOutcome.NeedsInvite -> base.update {
                    it.copy(isBusy = false, note = JoinNote.NEEDS_INVITE, button = JoinButton.ASK)
                }
                JoinOutcome.Failed -> base.update { it.copy(isBusy = false, error = JoinError.JOIN_FAILED) }
            }
        }
    }

    private fun sendRequest(message: String) {
        val current = state.value
        if (link == null || current.isBusy || current.blockedDomain != null || current.button != JoinButton.ASK) return
        base.update { it.copy(isBusy = true, error = null) }
        viewModelScope.launch {
            session.knockRoom(link, message.trim().ifEmpty { null }).fold(
                onSuccess = {
                    base.update {
                        it.copy(isBusy = false, note = JoinNote.REQUEST_SENT, button = JoinButton.NONE)
                    }
                },
                onFailure = { base.update { it.copy(isBusy = false, error = JoinError.KNOCK_FAILED) } },
            )
        }
    }

    private fun openNav(id: RoomId): JoinRoomNav = if (isSpace) JoinRoomNav.OpenSpace(id) else JoinRoomNav.OpenRoom(id)

    private fun JoinRoomState.withPreview(preview: RoomPreview): JoinRoomState {
        val (note, button) = when (preview.membership) {
            PreviewMembership.JOINED -> JoinNote.ALREADY_JOINED to JoinButton.OPEN
            PreviewMembership.INVITED -> JoinNote.INVITED to JoinButton.JOIN
            PreviewMembership.KNOCKED -> JoinNote.REQUEST_SENT to JoinButton.NONE
            PreviewMembership.BANNED -> JoinNote.BANNED to JoinButton.NONE
            PreviewMembership.NONE, PreviewMembership.LEFT -> when (preview.joinRule) {
                JoinRule.PUBLIC, JoinRule.UNKNOWN -> null to JoinButton.JOIN
                JoinRule.KNOCK -> JoinNote.KNOCK_ROOM to JoinButton.ASK
                JoinRule.INVITE_ONLY -> JoinNote.INVITE_ONLY to JoinButton.NONE
            }
        }
        return copy(
            name = preview.name?.takeIf { it.isNotBlank() } ?: address,
            topic = preview.topic?.takeIf { it.isNotBlank() },
            memberCount = preview.memberCount,
            isLoading = false,
            note = note,
            button = button,
        )
    }

    private companion object {
        const val ROOM_WAIT_MS = 10_000L
    }
}

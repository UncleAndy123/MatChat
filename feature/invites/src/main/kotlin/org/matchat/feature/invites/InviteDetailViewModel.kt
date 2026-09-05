package org.matchat.feature.invites

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.matchat.core.matrix.MatrixSession
import org.matchat.core.model.ErrorText
import org.matchat.core.model.InviteSummary
import org.matchat.core.model.RoomId
import javax.inject.Inject

@HiltViewModel
class InviteDetailViewModel @Inject constructor(
    private val session: MatrixSession,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val roomId = RoomId(requireNotNull(savedStateHandle["roomId"]))

    private val _state = MutableStateFlow(InviteDetailState())
    val state: StateFlow<InviteDetailState> = _state.asStateFlow()

    private val navChannel = Channel<InviteDetailNav>(Channel.BUFFERED)
    val navEvents: Flow<InviteDetailNav> = navChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            val invite = session.invites.first().firstOrNull { it.roomId == roomId }
            if (invite != null) _state.value = invite.toDetail()
        }
    }

    fun onAction(action: InviteDetailAction) {
        when (action) {
            InviteDetailAction.Accept -> if (!state.value.blocked) accept()
            InviteDetailAction.Decline -> decline(ignoreSender = false)
            InviteDetailAction.DeclineAndIgnore -> decline(ignoreSender = true)
        }
    }

    private fun accept() {
        _state.update { it.copy(isBusy = true, error = null) }
        viewModelScope.launch {
            session.acceptInvite(roomId).fold(
                onSuccess = { navChannel.send(InviteDetailNav.Dismissed) },
                onFailure = {
                    _state.update {
                        it.copy(isBusy = false, error = ErrorText(ErrorText.Key.INVITE_GONE, retryable = true))
                    }
                },
            )
        }
    }

    private fun decline(ignoreSender: Boolean) {
        _state.update { it.copy(isBusy = true, error = null) }
        viewModelScope.launch {
            session.declineInvite(roomId, ignoreSender)
            navChannel.send(InviteDetailNav.Dismissed)
        }
    }

    private fun InviteSummary.toDetail() = InviteDetailState(
        name = roomName,
        invitedBy = inviterName ?: inviter.value,
        address = inviter.value,
        server = senderDomain,
        isEncrypted = isEncrypted,
        blocked = !allowedByPolicy,
        blockedDomain = senderDomain,
    )
}

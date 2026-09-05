package org.matchat.feature.invites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.matchat.core.matrix.MatrixSession
import org.matchat.core.model.InviteSummary
import javax.inject.Inject

@HiltViewModel
class InvitesViewModel @Inject constructor(
    session: MatrixSession,
) : ViewModel() {

    val state: StateFlow<InvitesState> =
        session.invites
            .map { invites -> InvitesState(invites = invites.map { it.toRow() }, isLoading = false) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), InvitesState())

    private fun InviteSummary.toRow() = InviteRow(
        roomId = roomId,
        name = roomName,
        from = inviter.value,
        blocked = !allowedByPolicy,
    )

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

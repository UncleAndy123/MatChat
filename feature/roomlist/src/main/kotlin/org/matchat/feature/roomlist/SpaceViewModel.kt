package org.matchat.feature.roomlist

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
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.matchat.core.matrix.DraftStore
import org.matchat.core.matrix.MatrixSession
import org.matchat.core.model.MillisClock
import org.matchat.core.model.RoomId
import org.matchat.core.model.SpaceTree
import javax.inject.Inject

/** S29 logic: the folder contents come from [SpaceTree], the same definition
 *  the room list uses, so a room is always in exactly one visible place. */
@HiltViewModel
class SpaceViewModel @Inject constructor(
    private val session: MatrixSession,
    private val clock: MillisClock,
    draftStore: DraftStore,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val spaceId = RoomId(requireNotNull(savedStateHandle["spaceId"]))
    private val leave = MutableStateFlow(LeaveStatus())

    // Gone only once it was seen: right after a cold start the space list can
    // briefly be empty, and that must not bounce the user out of the folder.
    private var seen = false

    private data class LeaveStatus(val inProgress: Boolean = false, val failed: Boolean = false)

    private val navChannel = Channel<SpaceNav>(Channel.BUFFERED)
    val navEvents: Flow<SpaceNav> = navChannel.receiveAsFlow()

    val state: StateFlow<SpaceState> =
        combine(session.rooms, session.spaces, draftStore.drafts, leave) { rooms, spaces, drafts, leave ->
            val tree = SpaceTree(rooms, spaces)
            val space = tree.space(spaceId)
            if (space != null) seen = true
            SpaceState(
                title = space?.name.orEmpty(),
                rows = if (space == null) emptyList() else tree.rowsIn(spaceId, drafts, clock.now()),
                isLoading = space == null && !seen,
                isGone = space == null && seen,
                isLeaving = leave.inProgress,
                leaveFailed = leave.failed,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SpaceState())

    fun onAction(action: SpaceAction) {
        when (action) {
            is SpaceAction.OpenRow -> emit(
                if (state.value.rows.any { it.id == action.id && it.isSpace }) {
                    SpaceNav.Space(action.id)
                } else {
                    SpaceNav.Room(action.id)
                },
            )
            SpaceAction.Leave -> leaveSpace()
        }
    }

    private fun leaveSpace() {
        if (leave.value.inProgress) return
        leave.value = LeaveStatus(inProgress = true)
        viewModelScope.launch {
            session.leaveRoom(spaceId).fold(
                onSuccess = {
                    leave.update { LeaveStatus() }
                    navChannel.send(SpaceNav.Back)
                },
                onFailure = { leave.value = LeaveStatus(failed = true) },
            )
        }
    }

    private fun emit(nav: SpaceNav) {
        viewModelScope.launch { navChannel.send(nav) }
    }

    /** Download an avatar's bytes by its `mxc://` URI (Avatars round). */
    suspend fun loadAvatar(mxcUrl: String): ByteArray? = session.loadAvatar(mxcUrl)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

package org.matchat.feature.roomlist

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
import kotlinx.coroutines.launch
import org.matchat.core.matrix.MatrixSession
import org.matchat.core.model.InviteSummary
import org.matchat.core.model.MillisClock
import org.matchat.core.model.RoomId
import org.matchat.core.model.RoomSummary
import org.matchat.core.model.SyncState
import org.matchat.core.model.format.RelativeTime
import org.matchat.core.policy.PolicyProvider
import javax.inject.Inject

/**
 * S8 logic. All formatting and the loading/empty/offline decisions live here so
 * they are unit-testable against a [org.matchat.core.testing.FakeMatrixSession]
 * (AGENTS.md §6). Navigation is emitted as one-shot events the Fragment collects.
 */
@HiltViewModel
class RoomListViewModel @Inject constructor(
    session: MatrixSession,
    policyProvider: PolicyProvider,
    private val clock: MillisClock,
) : ViewModel() {

    private val focusedIndex = MutableStateFlow(0)
    private val navChannel = Channel<RoomListNav>(Channel.BUFFERED)
    val navEvents: Flow<RoomListNav> = navChannel.receiveAsFlow()

    val state: StateFlow<RoomListState> =
        combine(
            session.rooms,
            session.invites,
            session.syncState,
            policyProvider.policy,
            focusedIndex,
        ) { rooms, invites, sync, policy, focus ->
            RoomListState(
                isLoading = sync == SyncState.SYNCING && rooms.isEmpty(),
                rooms = rooms.map { it.toRow() },
                inviteBand = invites.toBand(),
                isOffline = sync == SyncState.OFFLINE || sync == SyncState.ERROR,
                focusedIndex = focus,
                newMessageEnabled = policy.allowDirectChat,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), RoomListState())

    fun onAction(action: RoomListAction) {
        when (action) {
            is RoomListAction.RoomFocused -> focusedIndex.value = action.index
            is RoomListAction.OpenRoom -> emit(RoomListNav.Room(action.roomId))
            RoomListAction.OpenInvites -> emit(RoomListNav.Invites)
            RoomListAction.NewMessage -> emit(RoomListNav.NewChat)
            RoomListAction.OpenSettings -> emit(RoomListNav.Settings)
            RoomListAction.OpenHelp -> emit(RoomListNav.Help)
            RoomListAction.SignOut -> emit(RoomListNav.SignOut)
            RoomListAction.MarkAllRead -> Unit // handled by the SDK read-marker call (M2)
            RoomListAction.NextUnread -> jumpToNextUnread()
        }
    }

    private fun jumpToNextUnread() {
        val rooms = state.value.rooms
        val start = focusedIndex.value
        val next = (1..rooms.size).map { (start + it) % rooms.size }
            .firstOrNull { rooms[it].isUnread } ?: return
        focusedIndex.value = next
    }

    private fun emit(nav: RoomListNav) {
        viewModelScope.launch { navChannel.send(nav) }
    }

    private fun RoomSummary.toRow() = RoomRow(
        id = id,
        name = name,
        preview = lastMessage.orEmpty(),
        time = lastActivityEpochMs?.let { RelativeTime.roomListLabel(it, clock.now()) }.orEmpty(),
        unreadCount = unreadCount,
    )

    private fun List<InviteSummary>.toBand(): InviteBand? =
        if (isEmpty()) null else InviteBand(size)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** One-shot navigation intents from the room list. */
sealed interface RoomListNav {
    data class Room(val roomId: RoomId) : RoomListNav
    data object Invites : RoomListNav
    data object NewChat : RoomListNav
    data object Settings : RoomListNav
    data object Help : RoomListNav
    data object SignOut : RoomListNav
}

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
import org.matchat.core.matrix.DraftStore
import org.matchat.core.matrix.MatrixSession
import org.matchat.core.model.InviteSummary
import org.matchat.core.model.MillisClock
import org.matchat.core.model.RoomId
import org.matchat.core.model.RoomSummary
import org.matchat.core.model.SpaceTree
import org.matchat.core.model.SyncState
import javax.inject.Inject

/**
 * S8 logic. All formatting and the loading/empty/offline decisions live here so
 * they are unit-testable against a [org.matchat.core.testing.FakeMatrixSession]
 * (AGENTS.md §6). Navigation is emitted as one-shot events the Fragment collects.
 */
@HiltViewModel
class RoomListViewModel @Inject constructor(
    private val session: MatrixSession,
    private val clock: MillisClock,
    private val draftStore: DraftStore,
) : ViewModel() {

    private val focusedIndex = MutableStateFlow(0)
    private val navChannel = Channel<RoomListNav>(Channel.BUFFERED)
    val navEvents: Flow<RoomListNav> = navChannel.receiveAsFlow()

    /** Everything [state] needs except [DraftStore.drafts] — its own type
     *  purely so the two combine steps below stay readable (Kotlin's
     *  fixed-arity `combine` tops out at 5 flows; mirrors TimelineViewModel's
     *  own ComposeContext/pendingAttachment nesting). */
    private data class RoomListContext(
        val rooms: List<RoomSummary>,
        val invites: List<InviteSummary>,
        val sync: SyncState,
        val focus: Int,
    )

    val state: StateFlow<RoomListState> =
        combine(
            combine(
                session.rooms,
                session.invites,
                session.syncState,
                focusedIndex,
            ) { rooms, invites, sync, focus ->
                RoomListContext(rooms, invites, sync, focus)
            },
            draftStore.drafts,
            session.spaces,
        ) { ctx, drafts, spaces ->
            RoomListState(
                isLoading = ctx.sync == SyncState.SYNCING && ctx.rooms.isEmpty() && spaces.isEmpty(),
                // Rooms in a joined space live inside its folder (S29), not here.
                rooms = SpaceTree(ctx.rooms, spaces).rowsIn(null, drafts, clock.now()),
                inviteBand = ctx.invites.toBand(),
                isOffline = ctx.sync == SyncState.OFFLINE || ctx.sync == SyncState.ERROR,
                focusedIndex = ctx.focus,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), RoomListState())

    fun onAction(action: RoomListAction) {
        when (action) {
            is RoomListAction.RoomFocused -> focusedIndex.value = action.index
            is RoomListAction.OpenRoom -> emit(
                if (state.value.rooms.any { it.id == action.roomId && it.isSpace }) {
                    RoomListNav.Space(action.roomId)
                } else {
                    RoomListNav.Room(action.roomId)
                },
            )
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

    /** Download an avatar's bytes by its `mxc://` URI (Avatars round). */
    suspend fun loadAvatar(mxcUrl: String): ByteArray? = session.loadAvatar(mxcUrl)

    private fun List<InviteSummary>.toBand(): InviteBand? = if (isEmpty()) null else InviteBand(size)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** One-shot navigation intents from the room list. */
sealed interface RoomListNav {
    data class Room(val roomId: RoomId) : RoomListNav
    data class Space(val spaceId: RoomId) : RoomListNav
    data object Invites : RoomListNav
    data object NewChat : RoomListNav
    data object Settings : RoomListNav
    data object Help : RoomListNav
    data object SignOut : RoomListNav
}

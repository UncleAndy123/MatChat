package org.matchat.feature.timeline

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.matchat.core.matrix.MatrixSession
import org.matchat.core.model.EventId
import org.matchat.core.model.ReactionSummary
import org.matchat.core.model.RoomId
import org.matchat.core.model.TimelineItem
import javax.inject.Inject

/**
 * Backs Message info's "who reacted" list (a follow-up to the Reactions
 * round) — the static sender/timestamp/event-id rows stay driven by nav
 * args in the Fragment as before; only reactions need a live lookup, since
 * they can change while this screen is open. Opens its own RoomTimeline
 * for the room (same precedent as PinnedMessagesViewModel — this app
 * doesn't cache/share RoomTimeline instances per room anywhere).
 */
@HiltViewModel
class MessageInfoViewModel @Inject constructor(
    session: MatrixSession,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val roomId = RoomId(requireNotNull(savedStateHandle["roomId"]))
    private val eventId = EventId(requireNotNull(savedStateHandle["eventId"]))
    private val timeline = session.timeline(roomId)

    /** Empty if the event isn't in the currently-loaded window — a flagged,
     *  honest scope cut (this screen only ever reads history the room's
     *  timeline has already paginated into), not a crash. */
    val reactions: StateFlow<List<ReactionSummary>> =
        timeline.items
            .map { it.reactionsOf(eventId) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    private fun List<TimelineItem>.reactionsOf(id: EventId): List<ReactionSummary> = firstNotNullOfOrNull { item ->
        when {
            item is TimelineItem.Message && item.eventId == id -> item.reactions
            item is TimelineItem.Media && item.eventId == id -> item.reactions
            else -> null
        }
    }.orEmpty()

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

package org.matchat.feature.timeline

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
import org.matchat.core.matrix.MatrixSession
import org.matchat.core.model.MillisClock
import org.matchat.core.model.RoomId
import org.matchat.core.model.TimelineItem
import org.matchat.core.model.format.RelativeTime
import javax.inject.Inject

@HiltViewModel
class TimelineViewModel @Inject constructor(
    private val session: MatrixSession,
    private val clock: MillisClock,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val roomId = RoomId(requireNotNull(savedStateHandle["roomId"]))
    private val timeline = session.timeline(roomId)

    private val composeFocused = MutableStateFlow(false)
    private val loadingEarlier = MutableStateFlow(false)

    private val navChannel = Channel<TimelineNav>(Channel.BUFFERED)
    val navEvents: Flow<TimelineNav> = navChannel.receiveAsFlow()

    val state: StateFlow<TimelineState> =
        combine(
            timeline.items,
            session.rooms,
            composeFocused,
            loadingEarlier,
        ) { items, rooms, composing, loading ->
            val room = rooms.firstOrNull { it.id == roomId }
            TimelineState(
                title = room?.name.orEmpty(),
                rows = items.toRows(),
                isEncrypted = room?.isEncrypted ?: true,
                isComposeFocused = composing,
                isLoadingEarlier = loading,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TimelineState())

    fun onAction(action: TimelineAction) {
        when (action) {
            is TimelineAction.Send -> send(action.body)
            TimelineAction.ReachedTop -> paginateBack()
            is TimelineAction.ComposeFocusChanged -> composeFocused.value = action.focused
            is TimelineAction.FixEncryption -> emit(TimelineNav.Verification)
            is TimelineAction.MessageFocused -> Unit // focus tracking only
        }
    }

    private fun send(body: String) {
        val text = body.trim()
        if (text.isEmpty()) return // empty send is a no-op, not an error (S10)
        viewModelScope.launch { timeline.send(text) }
    }

    private fun paginateBack() {
        if (loadingEarlier.value) return
        loadingEarlier.update { true }
        viewModelScope.launch {
            try {
                timeline.paginateBack(PAGE)
            } finally {
                loadingEarlier.update { false }
            }
        }
    }

    private fun emit(nav: TimelineNav) {
        viewModelScope.launch { navChannel.send(nav) }
    }

    /** Sender name is emitted only when it changes from the previous message (S9). */
    private fun List<TimelineItem>.toRows(): List<TimelineRow> {
        var lastSender: String? = null
        return map { item ->
            when (item) {
                is TimelineItem.Message -> {
                    val showName = item.senderName != lastSender
                    lastSender = item.senderName
                    TimelineRow.Message(
                        eventId = item.eventId,
                        senderName = if (showName) item.senderName else null,
                        body = item.body,
                        time = RelativeTime.clockTime(item.timestampEpochMs),
                        isOwn = item.isOwn,
                        sendGlyph = if (item.isOwn) TimelineState.glyph(item.sendState) else "",
                    )
                }
                is TimelineItem.DaySeparator -> {
                    lastSender = null
                    TimelineRow.DaySeparator(item.label)
                }
                is TimelineItem.UnableToDecrypt -> {
                    lastSender = null
                    TimelineRow.UnableToDecrypt(item.eventId)
                }
                is TimelineItem.StateChange -> {
                    lastSender = null
                    TimelineRow.State(item.text)
                }
            }
        }
    }

    private companion object {
        const val PAGE = 20
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

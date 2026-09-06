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
import org.matchat.core.model.EventId
import org.matchat.core.model.MediaKind
import org.matchat.core.model.MillisClock
import org.matchat.core.model.RoomId
import org.matchat.core.model.TimelineItem
import org.matchat.core.model.format.RelativeTime
import org.matchat.core.policy.PolicyProvider
import javax.inject.Inject

@HiltViewModel
class TimelineViewModel @Inject constructor(
    private val session: MatrixSession,
    private val clock: MillisClock,
    private val policyProvider: PolicyProvider,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val roomId = RoomId(requireNotNull(savedStateHandle["roomId"]))
    private val timeline = session.timeline(roomId)

    private val composeFocused = MutableStateFlow(false)
    private val loadingEarlier = MutableStateFlow(false)

    /** Whether attaching media is permitted on this (possibly managed) device. */
    val canSendMedia: Boolean get() = policyProvider.policy.value.mediaSend

    private val navChannel = Channel<TimelineNav>(Channel.BUFFERED)
    val navEvents: Flow<TimelineNav> = navChannel.receiveAsFlow()

    val state: StateFlow<TimelineState> =
        combine(
            timeline.items,
            session.rooms,
            composeFocused,
            loadingEarlier,
            timeline.typing,
        ) { items, rooms, composing, loading, typing ->
            val room = rooms.firstOrNull { it.id == roomId }
            TimelineState(
                title = room?.name.orEmpty(),
                rows = items.toRows(),
                isEncrypted = room?.isEncrypted ?: true,
                isComposeFocused = composing,
                isLoadingEarlier = loading,
                typingText = typingLine(typing),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TimelineState())

    /** Localpart-based label; peer display names are a follow-up (S9). */
    private fun typingLine(typing: List<org.matchat.core.model.UserId>): String? = when {
        typing.isEmpty() -> null
        typing.size == 1 -> "${typing.first().value.removePrefix("@").substringBefore(':')} is typing…"
        else -> "Several people are typing…"
    }

    private var typingActive = false
    private var typingStopJob: kotlinx.coroutines.Job? = null

    fun onAction(action: TimelineAction) {
        when (action) {
            is TimelineAction.Send -> send(action.body)
            TimelineAction.ReachedTop -> paginateBack()
            is TimelineAction.ComposeFocusChanged -> composeFocused.value = action.focused
            is TimelineAction.FixEncryption -> emit(TimelineNav.Verification)
            is TimelineAction.MessageFocused -> Unit // focus tracking only
            TimelineAction.MarkRead -> markRead()
        }
    }

    private fun markRead() {
        // The eventId is ignored by the SDK-backed timeline, which marks the
        // latest event read; the SDK dedupes repeated identical receipts.
        viewModelScope.launch { runCatching { timeline.markRead(EventId("")) } }
    }

    private fun send(body: String) {
        val text = body.trim()
        if (text.isEmpty()) return // empty send is a no-op, not an error (S10)
        stopTyping()
        viewModelScope.launch { timeline.send(text) }
    }

    /** Called on every compose edit: start a typing notice on the first keystroke
     *  and refresh a 4s auto-stop so a paused user stops appearing as typing. */
    fun onComposeTextChanged(text: String) {
        if (text.isBlank()) {
            stopTyping()
            return
        }
        if (!typingActive) {
            typingActive = true
            viewModelScope.launch { timeline.sendTyping(true) }
        }
        typingStopJob?.cancel()
        typingStopJob = viewModelScope.launch {
            kotlinx.coroutines.delay(TYPING_IDLE_MS)
            stopTyping()
        }
    }

    private fun stopTyping() {
        typingStopJob?.cancel()
        typingStopJob = null
        if (!typingActive) return
        typingActive = false
        viewModelScope.launch { timeline.sendTyping(false) }
    }

    override fun onCleared() {
        stopTyping()
        super.onCleared()
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
                        sendGlyph = when {
                            !item.isOwn -> ""
                            item.isRead -> READ_GLYPH
                            else -> TimelineState.glyph(item.sendState)
                        },
                    )
                }
                is TimelineItem.Media -> {
                    val showName = item.senderName != lastSender
                    lastSender = item.senderName
                    mediaRow(item, if (showName) item.senderName else null)
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

    /** Download a media message's bytes for the UI to render/open/play. */
    suspend fun loadMedia(eventId: EventId): ByteArray? = session.loadMedia(eventId)

    /** Send a picked file as media (S9). Blocked if the policy forbids it — the UI
     *  hides the entry, but re-check here so a stale menu cannot slip through. */
    fun sendMedia(path: String, mimeType: String, kind: MediaKind, caption: String?) {
        if (!policyProvider.policy.value.mediaSend) return
        viewModelScope.launch { timeline.sendMedia(path, mimeType, kind, caption) }
    }

    /** Send a recorded voice note (gated by policy, re-checked here). */
    fun sendVoice(path: String, mimeType: String, durationMs: Long, waveform: List<Float>) {
        if (!policyProvider.policy.value.mediaSend) return
        viewModelScope.launch { timeline.sendVoice(path, mimeType, durationMs, waveform) }
    }

    private fun mediaRow(item: TimelineItem.Media, senderName: String?): TimelineRow {
        val glyph = when {
            item.isOwn && item.isRead -> READ_GLYPH
            item.isOwn -> TimelineState.glyph(item.sendState)
            else -> ""
        }
        val time = RelativeTime.clockTime(item.timestampEpochMs)
        if (item.kind == MediaKind.IMAGE) {
            return TimelineRow.Image(
                eventId = item.eventId,
                senderName = senderName,
                caption = item.caption,
                time = time,
                isOwn = item.isOwn,
                sendGlyph = glyph,
            )
        }
        return TimelineRow.Attachment(
            eventId = item.eventId,
            senderName = senderName,
            glyph = glyphFor(item.kind),
            label = labelFor(item),
            sub = subFor(item),
            time = time,
            isOwn = item.isOwn,
            mimeType = item.mimeType,
            play = item.kind == MediaKind.AUDIO || item.kind == MediaKind.VOICE,
        )
    }

    private fun glyphFor(kind: MediaKind): String = when (kind) {
        MediaKind.VOICE -> "🎤"
        MediaKind.AUDIO -> "🎧"
        MediaKind.VIDEO -> "🎬"
        else -> "📎"
    }

    private fun labelFor(item: TimelineItem.Media): String = when (item.kind) {
        MediaKind.VOICE -> "Voice message"
        else -> item.filename
    }

    private fun subFor(item: TimelineItem.Media): String? {
        item.durationMs?.let { return formatDuration(it) }
        return item.sizeBytes?.let { formatSize(it) }
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        return "%d:%02d".format(totalSec / 60, totalSec % 60)
    }

    private fun formatSize(bytes: Long): String {
        val kb = bytes / 1024.0
        return if (kb < 1024) "%.0f KB".format(kb) else "%.1f MB".format(kb / 1024)
    }

    private companion object {
        const val PAGE = 20
        const val STOP_TIMEOUT_MS = 5_000L
        const val TYPING_IDLE_MS = 4_000L // stop the typing notice after a pause
        const val READ_GLYPH = "✓✓" // own message read by another member
    }
}

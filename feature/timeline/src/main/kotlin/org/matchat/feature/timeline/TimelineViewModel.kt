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
import org.matchat.core.matrix.Draft
import org.matchat.core.matrix.DraftAttachment
import org.matchat.core.matrix.DraftStore
import org.matchat.core.matrix.MatrixSession
import org.matchat.core.model.EventId
import org.matchat.core.model.MediaKind
import org.matchat.core.model.MillisClock
import org.matchat.core.model.RoomId
import org.matchat.core.model.RoomLink
import org.matchat.core.model.RoomSummary
import org.matchat.core.model.TimelineItem
import org.matchat.core.model.UserId
import org.matchat.core.model.format.RelativeTime
import org.matchat.core.policy.PolicyProvider
import javax.inject.Inject

@HiltViewModel
class TimelineViewModel @Inject constructor(
    private val session: MatrixSession,
    private val clock: MillisClock,
    private val policyProvider: PolicyProvider,
    private val draftStore: DraftStore,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val roomId = RoomId(requireNotNull(savedStateHandle["roomId"]))
    private val timeline = session.timeline(roomId)

    // Save a partially-drafted message (text and/or a staged attachment) so
    // leaving this room doesn't silently lose it (on-device report). Loaded
    // once here, at construction — the same instant "reopening this room"
    // means a brand-new TimelineViewModel (MainActivity.toRoom() has no
    // popUpTo, so a fresh instance is created on every visit).
    private val initialDraft = draftStore.getDraft(roomId)

    private val composeFocused = MutableStateFlow(false)
    private val loadingEarlier = MutableStateFlow(false)

    /** userId -> display name, for the typing indicator (RoomInfoViewModel's
     *  own one-shot `session.roomMembers` fetch, same reasoning: membership
     *  rarely changes mid-conversation, so this doesn't need to be a Flow
     *  folded into the state combine below). */
    private var memberNames: Map<String, String> = emptyMap()

    init {
        viewModelScope.launch {
            memberNames = session.roomMembers(roomId).associate { it.userId.value to it.label }
        }
    }

    /** The staged photo/file/camera-capture, if any (Attachment staging round) —
     *  a separate flow, folded into [state] below via a nested combine since
     *  Kotlin's fixed-arity `combine` tops out at 5 flows. Seeded from a
     *  restored draft's attachment half, if any, instead of always starting
     *  null. */
    private val pendingAttachment = MutableStateFlow(initialDraft?.attachment?.toPendingAttachment())

    /** The compose box's unsent text, restored from [initialDraft] on open.
     *  Deliberately NOT folded into [state]/the combine chain below: doing
     *  so would recompute [TimelineState.rows] — the whole message-list
     *  mapping — on every keystroke, and the Fragment already owns the live
     *  EditText once created, so the ViewModel only needs a `.value`
     *  snapshot at view-creation time, not a stream it keeps pushing text
     *  into (which would fight the user's own typing/cursor position). */
    private val composeTextState = MutableStateFlow(initialDraft?.text.orEmpty())
    val composeText: StateFlow<String> = composeTextState

    /** Whether attaching media is permitted on this (possibly managed) device. */
    val canSendMedia: Boolean get() = policyProvider.policy.value.mediaSend

    private val navChannel = Channel<TimelineNav>(Channel.BUFFERED)
    val navEvents: Flow<TimelineNav> = navChannel.receiveAsFlow()

    /** Everything [state] needs except [pendingAttachment] — its own type
     *  purely so the two combine steps below stay readable; not shown to
     *  any UI directly. */
    private data class ComposeContext(
        val items: List<TimelineItem>,
        val rooms: List<RoomSummary>,
        val composing: Boolean,
        val loading: Boolean,
        val typing: List<UserId>,
    )

    val state: StateFlow<TimelineState> =
        combine(
            combine(
                timeline.items,
                session.rooms,
                composeFocused,
                loadingEarlier,
                timeline.typing,
                ::ComposeContext,
            ),
            pendingAttachment,
        ) { ctx, pending ->
            val room = ctx.rooms.firstOrNull { it.id == roomId }
            TimelineState(
                title = room?.name.orEmpty(),
                titleAvatarUrl = room?.avatarUrl,
                titleAvatarId = room?.id?.value.orEmpty(),
                rows = ctx.items.toRows(),
                isEncrypted = room?.isEncrypted ?: true,
                isComposeFocused = ctx.composing,
                isLoadingEarlier = ctx.loading,
                typingText = typingLine(ctx.typing),
                pinnedCount = ctx.items.count {
                    (it is TimelineItem.Message && it.isPinned) || (it is TimelineItem.Media && it.isPinned)
                },
                pendingAttachment = pending,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TimelineState())

    /** Display name when known ([memberNames]), else the raw id's localpart. */
    private fun typingLine(typing: List<org.matchat.core.model.UserId>): String? = when {
        typing.isEmpty() -> null
        typing.size == 1 -> "${typingName(typing.first().value)} is typing…"
        else -> "Several people are typing…"
    }

    private fun typingName(userId: String): String =
        memberNames[userId] ?: userId.removePrefix("@").substringBefore(':')

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
            is TimelineAction.StageAttachment -> {
                pendingAttachment.value = action.attachment
                persistDraft()
            }
            TimelineAction.ClearPendingAttachment -> {
                pendingAttachment.value = null
                persistDraft()
            }
        }
    }

    /** Writes the current text + staged attachment to [draftStore] (or clears
     *  it, once both are empty) — called from every place either half of the
     *  draft changes. No debounce, deliberately: a debounce timer living in
     *  [viewModelScope] risks being cancelled by [onCleared] before it fires
     *  if the user types/stages then immediately backs out, silently losing
     *  the exact draft this exists to save. `SharedPreferences.apply()` is
     *  already an async, non-blocking write, so there's no performance
     *  reason to debounce a short record for a chat app's typing cadence. */
    private fun persistDraft() {
        val draft = Draft(composeTextState.value, pendingAttachment.value?.toDraftAttachment())
        viewModelScope.launch {
            if (draft.isEmpty) draftStore.clearDraft(roomId) else draftStore.setDraft(roomId, draft)
        }
    }

    private fun markRead() {
        // The eventId is ignored by the SDK-backed timeline, which marks the
        // latest event read; the SDK dedupes repeated identical receipts.
        viewModelScope.launch { runCatching { timeline.markRead(EventId("")) } }
    }

    /** Plain text, or — when an attachment is staged (Attachment staging
     *  round) — that attachment with [body] as its caption instead, via the
     *  same [sendMedia] every other caller already goes through (policy
     *  re-check included), just called from here instead of immediately on
     *  pick/capture. An attachment can be sent with an empty caption; plain
     *  text still can't be empty (S10). */
    private fun send(body: String) {
        val text = body.trim()
        val pending = pendingAttachment.value
        if (pending != null) {
            stopTyping()
            pendingAttachment.value = null
            composeTextState.value = ""
            persistDraft() // both halves are now empty, so this clears the store
            if (pending.kind == MediaKind.VOICE) {
                // sendVoice has no caption slot (MSC3245 voice messages don't
                // carry one) — rather than silently discard typed text, send
                // it as its own follow-up message right after.
                sendVoice(pending.path, pending.mimeType, pending.durationMs ?: 0L, pending.waveform ?: emptyList())
                if (text.isNotEmpty()) viewModelScope.launch { timeline.send(text) }
            } else {
                sendMedia(pending.path, pending.mimeType, pending.kind, text.ifBlank { null })
            }
            return
        }
        if (text.isEmpty()) return // empty send is a no-op, not an error (S10)
        stopTyping()
        composeTextState.value = ""
        persistDraft() // clears the store now that the compose box is empty
        viewModelScope.launch { timeline.send(text) }
    }

    /** Called on every compose edit: start a typing notice on the first keystroke
     *  and refresh a 4s auto-stop so a paused user stops appearing as typing.
     *  Also keeps the draft (this room's saved unsent text) up to date. */
    fun onComposeTextChanged(text: String) {
        composeTextState.value = text
        persistDraft()
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

    /** Edit a previously-sent message (S11). The SDK rejects edits of others'. */
    fun editMessage(eventId: EventId, newBody: String) {
        val text = newBody.trim()
        if (text.isEmpty()) return
        viewModelScope.launch { timeline.editMessage(eventId, text) }
    }

    /** Add or remove our own reaction (Reactions round) — toggleReaction is
     *  itself a toggle, so this is the whole implementation. */
    fun toggleReaction(eventId: EventId, key: String) {
        viewModelScope.launch { timeline.toggleReaction(eventId, key) }
    }

    /** Pin or unpin a message (Pinned messages round). m.room.pinned_events
     *  needs state_default power level (moderator+ by default) to send — a
     *  plain member's write is rejected by the server; surface that instead
     *  of letting it look like nothing happened (the bug this fixes). */
    fun setPinned(eventId: EventId, pinned: Boolean) {
        viewModelScope.launch {
            val ok = timeline.setPinned(eventId, pinned)
            if (!ok) emit(TimelineNav.Toast(TimelineToastKey.PIN_FAILED))
        }
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
                        senderId = item.sender.value,
                        timestampEpochMs = item.timestampEpochMs,
                        senderAvatarUrl = item.senderAvatarUrl,
                        seenBy = item.seenBy,
                        reactions = item.reactions,
                        isPinned = item.isPinned,
                        roomLink = RoomLink.findIn(item.body)?.toMatrixTo(),
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

    /** Download an avatar's bytes by its `mxc://` URI (Avatars round). */
    suspend fun loadAvatar(mxcUrl: String): ByteArray? = session.loadAvatar(mxcUrl)

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
                senderId = item.sender.value,
                senderAvatarUrl = item.senderAvatarUrl,
                seenBy = item.seenBy,
                reactions = item.reactions,
                isPinned = item.isPinned,
                timestampEpochMs = item.timestampEpochMs,
            )
        }
        // VOICE and AUDIO both play in-app via the same AudioPlayback, so they
        // share one bubble UI (Voice bubble round) rather than each getting a
        // different look for functionally identical playback; AUDIO just gets
        // a flat placeholder waveform since it never carries real samples.
        if (item.kind == MediaKind.VOICE || item.kind == MediaKind.AUDIO) {
            return TimelineRow.VoiceBubble(
                eventId = item.eventId,
                senderName = senderName,
                label = labelFor(item),
                time = time,
                isOwn = item.isOwn,
                sendGlyph = glyph,
                mimeType = item.mimeType,
                duration = item.durationMs?.let(::formatDuration).orEmpty(),
                waveform = item.waveform ?: FLAT_WAVEFORM,
                isPinned = item.isPinned,
                reactions = item.reactions,
                timestampEpochMs = item.timestampEpochMs,
                senderId = item.sender.value,
                senderAvatarUrl = item.senderAvatarUrl,
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
            isPinned = item.isPinned,
            reactions = item.reactions,
            timestampEpochMs = item.timestampEpochMs,
            senderId = item.sender.value,
        )
    }

    // VOICE/AUDIO never reach here (routed to VoiceBubble above) — only
    // VIDEO/FILE still use the plain glyph row.
    private fun glyphFor(kind: MediaKind): String = when (kind) {
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

        // A flat, "no data" waveform for an AUDIO file (never carries real
        // samples) or a VOICE message from a client that omitted one — same
        // bar count VoiceRecorder.WAVEFORM_BARS uses, so it fills the same
        // width as a real one instead of looking truncated.
        val FLAT_WAVEFORM = List(30) { 0.15f }
    }
}

/**
 * Bug fix (Reactions round): the reaction picker's own hardcoded emoji
 * literal (e.g. "❤️") can differ byte-for-byte from whatever key is
 * already on the message — a Unicode variation-selector mismatch (with vs.
 * without U+FE0F) is the common case, visually identical but a different
 * SDK-level reaction key — so toggling with the literal creates a second,
 * parallel chip instead of bumping the existing one's count. Resolving
 * against the message's own [existing] reactions first (normalized) and
 * reusing whichever key is already there guarantees a toggle always lands
 * on the same reaction group. A top-level, ViewModel-free function so it's
 * directly testable and reusable from the Fragment's picker-building code.
 */
fun resolveReactionKey(existing: List<org.matchat.core.model.ReactionSummary>, chosenKey: String): String =
    existing.firstOrNull { normalizeReactionKey(it.key) == normalizeReactionKey(chosenKey) }?.key ?: chosenKey

/** Strips variation selectors (U+FE0F "emoji presentation", U+FE0E "text
 *  presentation") so two otherwise-identical emoji compare equal. */
fun normalizeReactionKey(key: String): String = key.replace("\uFE0F", "").replace("\uFE0E", "")

/** [DraftAttachment] mirrors [PendingAttachment] field-for-field (see that
 *  class's own doc comment for why they're two separate types); these two
 *  are a plain 1:1 copy across the boundary. */
private fun PendingAttachment.toDraftAttachment(): DraftAttachment =
    DraftAttachment(path, mimeType, kind, displayName, durationMs, waveform)

private fun DraftAttachment.toPendingAttachment(): PendingAttachment =
    PendingAttachment(path, mimeType, kind, displayName, durationMs, waveform)

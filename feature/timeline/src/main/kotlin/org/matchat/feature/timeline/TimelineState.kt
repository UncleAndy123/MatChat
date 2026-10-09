package org.matchat.feature.timeline

import org.matchat.core.model.ErrorText
import org.matchat.core.model.EventId
import org.matchat.core.model.MediaKind
import org.matchat.core.model.ReactionSummary
import org.matchat.core.model.SeenBy
import org.matchat.core.model.SendState

/** A display-ready timeline row (S9). Formatting is done in the ViewModel. */
sealed interface TimelineRow {
    val stableId: String

    data class Message(
        val eventId: EventId,
        /** Sender name only when it changes from the previous row (UX-SPEC S9). */
        val senderName: String?,
        val body: String,
        val time: String,
        val isOwn: Boolean,
        val sendGlyph: String,
        /** Full sender id and send time, carried for the Message info screen (S11). */
        val senderId: String,
        val timestampEpochMs: Long,
        /** An `mxc://` URI, or null for no avatar set — shown next to
         *  [senderName] when that's non-null (Avatars round). */
        val senderAvatarUrl: String? = null,
        /** Who's read this message besides the sender and the current user —
         *  rendered as a short avatar row under the bubble; shown on both
         *  own and received messages (UX-SPEC S9). */
        val seenBy: List<SeenBy> = emptyList(),
        /** Reaction chips shown below the bubble (Reactions round); empty
         *  hides the row entirely. */
        val reactions: List<ReactionSummary> = emptyList(),
        /** True when pinned — TimelineAdapter prefixes the time text with
         *  📌, the same compact idiom sendGlyph already uses (Pinned
         *  messages round). */
        val isPinned: Boolean = false,
    ) : TimelineRow {
        override val stableId: String get() = eventId.value
    }

    /** An inline image (downloaded by eventId on bind). */
    data class Image(
        val eventId: EventId,
        val senderName: String?,
        val caption: String?,
        val time: String,
        val isOwn: Boolean,
        val sendGlyph: String,
        /** Full sender id, carried for the avatar fallback's color+initial
         *  (AvatarFallback round; parity with Message.senderId). */
        val senderId: String = "",
        val senderAvatarUrl: String? = null,
        val seenBy: List<SeenBy> = emptyList(),
        val reactions: List<ReactionSummary> = emptyList(),
        val isPinned: Boolean = false,
        /** Send time, carried for the Message info screen (S11) — the
         *  options menu round gave Image/Attachment the same menu Message
         *  rows get, which needs this the same way Message.timestampEpochMs
         *  already does. */
        val timestampEpochMs: Long = 0L,
    ) : TimelineRow {
        override val stableId: String get() = "img:${eventId.value}"
    }

    /** A video/file attachment; CENTER downloads and opens externally (VOICE
     *  and AUDIO get [VoiceBubble] instead — always played in-app, never
     *  externally, so there's no longer a play/open split here). */
    data class Attachment(
        val eventId: EventId,
        val senderName: String?,
        val glyph: String,
        val label: String,
        val sub: String?,
        val time: String,
        val isOwn: Boolean,
        val mimeType: String?,
        val isPinned: Boolean = false,
        /** Options-menu round: Attachment rows now open the same menu
         *  Message/Image rows do (React/Pin/Message info etc.), which needs
         *  all three of these the same way Message/Image already carry them. */
        val reactions: List<ReactionSummary> = emptyList(),
        val timestampEpochMs: Long = 0L,
        val senderId: String = "",
    ) : TimelineRow {
        override val stableId: String get() = "att:${eventId.value}"
    }

    /** A voice/audio message rendered as a proper chat bubble (waveform +
     *  duration), not the plain glyph row [Attachment] still uses for
     *  video/file (Voice bubble round). The row has its own focusable
     *  play/pause button (Voice playback round): CENTER on it plays/pauses
     *  in-app via AudioPlayback and the waveform doubles as a progress bar.
     *  CENTER on the bubble itself opens the message menu (React/Pin/Message
     *  info) — no "Open" item, since playback is the button now. */
    data class VoiceBubble(
        val eventId: EventId,
        val senderName: String?,
        /** "Voice message" for VOICE; the filename for a plain AUDIO file
         *  (mirrors Attachment's labelFor). */
        val label: String,
        val time: String,
        val isOwn: Boolean,
        val sendGlyph: String,
        val mimeType: String?,
        /** Pre-formatted ("0:12"), same convention as Attachment.sub —
         *  empty when unknown rather than null, since there's always a
         *  fixed slot for it next to the waveform. */
        val duration: String,
        /** Normalized 0f..1f (Mappers.normalizeWaveform / VoiceRecorder).
         *  A flat placeholder (not empty) when the kind is AUDIO or the
         *  sender didn't include one — an empty list would draw no bars at
         *  all, which reads as broken rather than "no data available". */
        val waveform: List<Float>,
        val isPinned: Boolean = false,
        val reactions: List<ReactionSummary> = emptyList(),
        val timestampEpochMs: Long = 0L,
        val senderId: String = "",
        val senderAvatarUrl: String? = null,
    ) : TimelineRow {
        override val stableId: String get() = "voice:${eventId.value}"
    }

    data class DaySeparator(val label: String) : TimelineRow {
        override val stableId: String get() = "day:$label"
    }

    data class UnableToDecrypt(val eventId: EventId) : TimelineRow {
        override val stableId: String get() = "utd:${eventId.value}"
    }

    data class State(val text: String) : TimelineRow {
        override val stableId: String get() = "state:$text"
    }
}

/** A picked/captured attachment staged for sending but not yet uploaded —
 *  the user can type a caption into `compose_input` before the actual send
 *  (Attachment staging round). Mirrors exactly the arguments
 *  [TimelineViewModel.sendMedia]/[TimelineViewModel.sendVoice] already take;
 *  nothing new is invented on the send path, only when it's called. */
data class PendingAttachment(
    val path: String,
    val mimeType: String,
    val kind: MediaKind,
    /** Shown in the attachment preview row — the picked file's display name
     *  (or a fixed label for a camera capture/voice recording, which have
     *  none worth showing). */
    val displayName: String,
    /** VOICE only — carried through to [TimelineViewModel.sendVoice] at
     *  send time; null for every other kind. */
    val durationMs: Long? = null,
    val waveform: List<Float>? = null,
)

/**
 * Everything the timeline shows (S9). The unencrypted warning band, loading of
 * earlier messages, empty and error are all fields here (AGENTS.md §3, G4).
 */
data class TimelineState(
    val title: String = "",
    val rows: List<TimelineRow> = emptyList(),
    val isEncrypted: Boolean = true,
    val isLoadingEarlier: Boolean = false,
    val isComposeFocused: Boolean = false,
    /** "Alice is typing…" / "Several people are typing…", or null when nobody is. */
    val typingText: String? = null,
    val error: ErrorText? = null,
    /** Count of pinned messages in this room — drives the pinned-messages
     *  band's visibility/text and whether the RIGHT-key shortcut does
     *  anything (Pinned messages quick-access round). */
    val pinnedCount: Int = 0,
    /** A picked photo/file/camera-capture waiting to be sent, or null when
     *  nothing is staged (Attachment staging round). Drives the attachment
     *  preview row's visibility above `compose_input`. */
    val pendingAttachment: PendingAttachment? = null,
) {
    val isEmpty: Boolean get() = rows.isEmpty() && !isLoadingEarlier
    val showUnencryptedBand: Boolean get() = !isEncrypted

    companion object {
        fun glyph(state: SendState): String = when (state) {
            SendState.SENDING -> "○"
            SendState.SENT -> "✓"
            SendState.FAILED -> "!"
        }
    }
}

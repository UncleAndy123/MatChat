package org.matchat.feature.timeline

import org.matchat.core.model.ErrorText
import org.matchat.core.model.EventId
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
    ) : TimelineRow {
        override val stableId: String get() = "img:${eventId.value}"
    }

    /** A file / video / audio / voice attachment; CENTER downloads and opens/plays. */
    data class Attachment(
        val eventId: EventId,
        val senderName: String?,
        val glyph: String,
        val label: String,
        val sub: String?,
        val time: String,
        val isOwn: Boolean,
        val mimeType: String?,
        val play: Boolean, // true = audio/voice (play in-app), false = open externally
    ) : TimelineRow {
        override val stableId: String get() = "att:${eventId.value}"
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
    val error: ErrorText? = null,
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

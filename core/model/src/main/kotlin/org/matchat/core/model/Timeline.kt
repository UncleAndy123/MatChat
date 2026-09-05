package org.matchat.core.model

/**
 * One row in a room timeline (S9). The SDK produces these already paginated and
 * deduped; we never merge or reorder events ourselves (AGENTS.md §0).
 */
sealed interface TimelineItem {
    data class Message(
        val eventId: EventId,
        val sender: UserId,
        val senderName: String,
        val body: String,
        val timestampEpochMs: Long,
        val isOwn: Boolean,
        val sendState: SendState,
        /** True when another member has a read receipt on this (own) message. */
        val isRead: Boolean = false,
    ) : TimelineItem

    /**
     * A media message (image / video / audio / voice / file). The bytes are not
     * carried here — the UI asks the session to download them by [eventId], which
     * keeps encryption keys inside :core:matrix (AGENTS.md §0/§2).
     */
    data class Media(
        val eventId: EventId,
        val sender: UserId,
        val senderName: String,
        val body: String,
        val timestampEpochMs: Long,
        val isOwn: Boolean,
        val sendState: SendState,
        val kind: MediaKind,
        val filename: String,
        val caption: String?,
        val mimeType: String?,
        val sizeBytes: Long?,
        val durationMs: Long?,
        val isRead: Boolean = false,
    ) : TimelineItem

    data class DaySeparator(val label: String) : TimelineItem

    /** Rendered as a distinct, non-scary row with a "Fix encryption" action → S6. */
    data class UnableToDecrypt(
        val eventId: EventId,
        val sender: UserId,
    ) : TimelineItem

    data class StateChange(val text: String) : TimelineItem
}

enum class MediaKind { IMAGE, VIDEO, AUDIO, VOICE, FILE }

enum class SendState { SENDING, SENT, FAILED }

enum class SyncState { IDLE, SYNCING, OFFLINE, ERROR }

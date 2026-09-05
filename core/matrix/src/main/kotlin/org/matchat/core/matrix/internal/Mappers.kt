package org.matchat.core.matrix.internal

import org.matchat.core.model.EventId
import org.matchat.core.model.MediaKind
import org.matchat.core.model.RoomId
import org.matchat.core.model.RoomSummary
import org.matchat.core.model.SendState
import org.matchat.core.model.TimelineItem
import org.matchat.core.model.UserId
import org.matrix.rustcomponents.sdk.EventOrTransactionId
import org.matrix.rustcomponents.sdk.MediaSource
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.TimelineItemContent
import org.matrix.rustcomponents.sdk.TimelineItem as RustTimelineItem

/**
 * SDK types -> :core:model types. The only mapping layer; if the SDK changes
 * shape on upgrade, this file is what breaks, by design (ARCHITECTURE.md).
 */
internal object Mappers {

    suspend fun toRoomSummary(room: Room): RoomSummary {
        val info = runCatching { room.roomInfo() }.getOrNull()
        val encrypted = runCatching { room.isEncrypted() }.getOrDefault(true)
        return RoomSummary(
            id = RoomId(room.id()),
            name = info?.displayName ?: room.displayName() ?: room.id(),
            // Last-message preview needs the latest-event API — a follow-up.
            lastMessage = null,
            lastActivityEpochMs = null,
            unreadCount = (info?.numUnreadMessages ?: 0uL).toInt(),
            isEncrypted = encrypted,
        )
    }

    /**
     * Maps one SDK timeline item to a domain [TimelineItem], or null for items we
     * do not render (state changes, virtual dividers). Only text messages are
     * mapped in this step.
     *
     * EventTimelineItem is a uniffi Record, so its fields are properties. The body
     * path is content -> MsgLike.content.kind -> Message.content.body.
     */
    fun toTimelineItem(item: RustTimelineItem): TimelineItem? {
        val event = item.asEvent() ?: return null
        val msgLike = event.content as? TimelineItemContent.MsgLike ?: return null
        val messageKind = msgLike.content.kind as? MsgLikeKind.Message ?: return null
        val eventId = eventIdOf(event.eventOrTransactionId)
        // Read by others when a receipt on this event belongs to someone other
        // than the sender (for our own messages, that means a member has read it).
        val readByOther = runCatching {
            event.readReceipts.keys.any { it != event.sender }
        }.getOrDefault(false)

        val media = mediaOf(messageKind.content.msgType, eventId, event, readByOther)
        if (media != null) return media

        return TimelineItem.Message(
            eventId = EventId(eventId),
            sender = UserId(event.sender),
            // Sender display name resolution is a follow-up; the id is always safe.
            senderName = event.sender,
            body = messageKind.content.body,
            timestampEpochMs = event.timestamp.toLong(),
            isOwn = event.isOwn,
            sendState = SendState.SENT,
            isRead = readByOther,
        )
    }

    /** Media messages (image/video/audio/voice/file). Registers the MediaSource
     *  so the download-by-id path can reach it. Returns null for text-like types. */
    @Suppress("CyclomaticComplexMethod")
    private fun mediaOf(
        type: MessageType,
        eventId: String,
        event: org.matrix.rustcomponents.sdk.EventTimelineItem,
        isRead: Boolean,
    ): TimelineItem.Media? {
        val (kind, source, filename, caption, mime, size, durationMs) = when (type) {
            is MessageType.Image -> Media6(
                MediaKind.IMAGE, type.content.source, type.content.filename, type.content.caption,
                type.content.info?.mimetype, type.content.info?.size?.toLong(), null,
            )
            is MessageType.Video -> Media6(
                MediaKind.VIDEO, type.content.source, type.content.filename, type.content.caption,
                type.content.info?.mimetype, type.content.info?.size?.toLong(), null,
            )
            is MessageType.Audio -> Media6(
                if (type.content.voice != null) MediaKind.VOICE else MediaKind.AUDIO,
                type.content.source, type.content.filename, type.content.caption,
                type.content.info?.mimetype, type.content.info?.size?.toLong(),
                runCatching {
                    type.content.info?.duration?.toMillis() ?: type.content.audio?.duration?.toMillis()
                }.getOrNull(),
            )
            is MessageType.File -> Media6(
                MediaKind.FILE, type.content.source, type.content.filename, type.content.caption,
                type.content.info?.mimetype, type.content.info?.size?.toLong(), null,
            )
            else -> return null
        }
        MediaRegistry.put(eventId, source)
        return TimelineItem.Media(
            eventId = EventId(eventId),
            sender = UserId(event.sender),
            senderName = event.sender,
            body = caption ?: filename,
            timestampEpochMs = event.timestamp.toLong(),
            isOwn = event.isOwn,
            sendState = SendState.SENT,
            kind = kind,
            filename = filename,
            caption = caption,
            mimeType = mime,
            sizeBytes = size,
            durationMs = durationMs,
            isRead = isRead,
        )
    }

    /** Small carrier so the media `when` can destructure its columns (a data
     *  class provides component1..7 automatically). */
    private data class Media6(
        val kind: MediaKind,
        val source: MediaSource,
        val filename: String,
        val caption: String?,
        val mime: String?,
        val size: Long?,
        val durationMs: Long?,
    )

    private fun eventIdOf(id: EventOrTransactionId): String =
        (id as? EventOrTransactionId.EventId)?.eventId.orEmpty()
}

package org.matchat.core.matrix.internal

import org.matchat.core.model.EventId
import org.matchat.core.model.RoomId
import org.matchat.core.model.RoomSummary
import org.matchat.core.model.SendState
import org.matchat.core.model.TimelineItem
import org.matchat.core.model.UserId
import org.matrix.rustcomponents.sdk.EventOrTransactionId
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
        val encrypted = runCatching { room.isEncrypted() }.getOrDefault(true)
        return RoomSummary(
            id = RoomId(room.id()),
            name = room.displayName() ?: room.id(),
            // FFI follow-up: last message + unread come from room.latestEvent()
            // and room.roomInfo(); left minimal so the room list lands first.
            lastMessage = null,
            lastActivityEpochMs = null,
            unreadCount = 0,
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
        val body = messageKind.content.body
        return TimelineItem.Message(
            eventId = EventId(eventIdOf(event.eventOrTransactionId)),
            sender = UserId(event.sender),
            // Sender display name resolution is a follow-up; the id is always safe.
            senderName = event.sender,
            body = body,
            timestampEpochMs = event.timestamp.toLong(),
            isOwn = event.isOwn,
            sendState = SendState.SENT,
        )
    }

    private fun eventIdOf(id: EventOrTransactionId): String =
        (id as? EventOrTransactionId.EventId)?.eventId.orEmpty()
}

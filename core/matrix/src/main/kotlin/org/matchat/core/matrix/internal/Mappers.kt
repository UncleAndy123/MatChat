package org.matchat.core.matrix.internal

import org.matchat.core.model.EventId
import org.matchat.core.model.RoomId
import org.matchat.core.model.RoomSummary
import org.matchat.core.model.SendState
import org.matchat.core.model.TimelineItem
import org.matchat.core.model.UserId
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.TimelineItem as RustTimelineItem

/**
 * SDK types -> :core:model types. The only mapping layer; if the SDK changes
 * shape on upgrade, this file (and SessionCodec) are what break, by design
 * (ARCHITECTURE.md). FFI-sensitive accessors are marked.
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
     * do not render (virtual day dividers are surfaced separately by the SDK).
     *
     * FFI: asEvent()/content accessors are version-sensitive. As of 26.09.x an
     * event item exposes sender/timestamp/isOwn and content.asMessage()?.body().
     */
    fun toTimelineItem(item: RustTimelineItem): TimelineItem? {
        val event = item.asEvent() ?: return null
        val body = runCatching { event.content().asMessage()?.body() }.getOrNull() ?: return null
        return TimelineItem.Message(
            eventId = EventId(event.eventId() ?: ""),
            sender = UserId(event.sender()),
            // Sender display name resolution is a follow-up; the id is always safe.
            senderName = event.sender(),
            body = body,
            timestampEpochMs = event.timestamp().toLong(),
            isOwn = event.isOwn(),
            sendState = SendState.SENT,
        )
    }
}

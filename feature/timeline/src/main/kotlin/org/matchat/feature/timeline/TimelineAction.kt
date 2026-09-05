package org.matchat.feature.timeline

import org.matchat.core.model.EventId

sealed interface TimelineAction {
    data class Send(val body: String) : TimelineAction
    data object ReachedTop : TimelineAction // triggers paginateBack(20)
    data class MessageFocused(val eventId: EventId) : TimelineAction
    data class ComposeFocusChanged(val focused: Boolean) : TimelineAction
    data class FixEncryption(val eventId: EventId) : TimelineAction

    /** Sent a read receipt for the latest message (clears the room's unread count). */
    data object MarkRead : TimelineAction
}

sealed interface TimelineNav {
    data object Verification : TimelineNav
    data object RoomInfo : TimelineNav
}

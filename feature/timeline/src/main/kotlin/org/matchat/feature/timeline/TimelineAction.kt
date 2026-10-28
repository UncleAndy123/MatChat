package org.matchat.feature.timeline

import org.matchat.core.model.EventId

sealed interface TimelineAction {
    /** Sends the compose box's text — or, when a [PendingAttachment] is
     *  staged, sends that attachment with [body] as its caption instead
     *  (Attachment staging round; see [TimelineViewModel]'s send()). */
    data class Send(val body: String) : TimelineAction
    data object ReachedTop : TimelineAction // triggers paginateBack(20)
    data class MessageFocused(val eventId: EventId) : TimelineAction
    data class ComposeFocusChanged(val focused: Boolean) : TimelineAction
    data class FixEncryption(val eventId: EventId) : TimelineAction

    /** CENTER on the "not verified" band: go verify this phone (S6). */
    data object VerifyDevice : TimelineAction

    /** Sent a read receipt for the latest message (clears the room's unread count). */
    data object MarkRead : TimelineAction

    /** A photo/file/camera-capture was picked — stage it rather than send it
     *  immediately, so the user can type a caption first (Attachment
     *  staging round). */
    data class StageAttachment(val attachment: PendingAttachment) : TimelineAction

    /** Discards the staged attachment without sending it. */
    data object ClearPendingAttachment : TimelineAction
}

sealed interface TimelineNav {
    data object Verification : TimelineNav
    data object RoomInfo : TimelineNav
    data class Toast(val key: TimelineToastKey) : TimelineNav
}

enum class TimelineToastKey { PIN_FAILED }

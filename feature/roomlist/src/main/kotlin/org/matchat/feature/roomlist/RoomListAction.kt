package org.matchat.feature.roomlist

import org.matchat.core.model.RoomId

/** User intents on the room list. The Fragment emits these; the ViewModel reduces. */
sealed interface RoomListAction {
    data class RoomFocused(val index: Int) : RoomListAction
    data class OpenRoom(val roomId: RoomId) : RoomListAction
    data object OpenInvites : RoomListAction
    data object NewMessage : RoomListAction
    data object MarkAllRead : RoomListAction
    data object OpenSettings : RoomListAction
    data object OpenHelp : RoomListAction
    data object SignOut : RoomListAction
    data object NextUnread : RoomListAction // # long-press
}

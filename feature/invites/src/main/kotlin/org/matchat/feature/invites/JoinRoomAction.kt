package org.matchat.feature.invites

import org.matchat.core.model.RoomId

sealed interface JoinRoomAction {
    /** CENTER on the action button — Join, Ask to join or Open, per state. */
    data object Activate : JoinRoomAction

    /** The request-to-join prompt was confirmed; a blank [message] sends none. */
    data class SendRequest(val message: String) : JoinRoomAction
}

sealed interface JoinRoomNav {
    data class OpenRoom(val roomId: RoomId) : JoinRoomNav

    /** The link was a space: open it as a folder (S29). */
    data class OpenSpace(val spaceId: RoomId) : JoinRoomNav

    /** Ask to join: show the optional-message prompt (cancel sends nothing). */
    data object PromptForMessage : JoinRoomNav
}

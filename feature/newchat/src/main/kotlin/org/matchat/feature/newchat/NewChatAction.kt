package org.matchat.feature.newchat

import org.matchat.core.model.RoomId
import org.matchat.core.model.UserId

sealed interface NewChatAction {
    data class Select(val address: UserId) : NewChatAction
    data object TypeAnAddress : NewChatAction
}

sealed interface NewChatNav {
    data class OpenRoom(val roomId: RoomId) : NewChatNav
    data object TypeAddress : NewChatNav
}

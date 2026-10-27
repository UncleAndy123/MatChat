package org.matchat.feature.roomlist

import org.matchat.core.model.RoomId

sealed interface SpaceAction {
    data class OpenRow(val id: RoomId) : SpaceAction

    /** Confirmed from Options → Leave space. Leaves the space only, not its rooms. */
    data object Leave : SpaceAction
}

sealed interface SpaceNav {
    data class Room(val roomId: RoomId) : SpaceNav
    data class Space(val spaceId: RoomId) : SpaceNav
    data object Back : SpaceNav
}

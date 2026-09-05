package org.matchat.feature.newchat

import org.matchat.core.model.RoomId

sealed interface TypeAddressAction {
    /** Field value is passed at submit time — render never reads it back. */
    data class Continue(val raw: String) : TypeAddressAction
    data object Confirm : TypeAddressAction
    data object StartAnyway : TypeAddressAction
    data object Change : TypeAddressAction
}

sealed interface TypeAddressNav {
    data class OpenRoom(val roomId: RoomId) : TypeAddressNav
}

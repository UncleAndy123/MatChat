package org.matchat.feature.roomlist

import org.matchat.core.model.ErrorText
import org.matchat.core.model.RoomId

/** One room row (S8). Carries display-ready values — never an Instant (AGENTS.md §3). */
data class RoomRow(
    val id: RoomId,
    val name: String,
    val preview: String,
    val time: String,
    val unreadCount: Int,
) {
    val isUnread: Boolean get() = unreadCount > 0
}

/** The pending-invitation band shown under the title bar (S8). */
data class InviteBand(val count: Int)

/**
 * Everything the room list shows. loading / empty / error / offline / focused are
 * all fields here, not separate code paths (AGENTS.md §3, UX-SPEC §5).
 */
data class RoomListState(
    val isLoading: Boolean = true,
    val rooms: List<RoomRow> = emptyList(),
    val inviteBand: InviteBand? = null,
    val isOffline: Boolean = false,
    val error: ErrorText? = null,
    val focusedIndex: Int = 0,
    val newMessageEnabled: Boolean = true,
) {
    /** True only after the first sync arrives with no rooms and no invitations. */
    val isEmpty: Boolean get() = !isLoading && rooms.isEmpty() && inviteBand == null
}

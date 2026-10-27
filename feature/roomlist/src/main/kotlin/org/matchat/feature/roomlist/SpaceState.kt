package org.matchat.feature.roomlist

/**
 * S29 Space: one joined space as a folder — its joined subspaces, then its
 * joined rooms, in the room list's own row shape. Rooms of the space the user
 * has not joined are never listed (AGENTS.md §0).
 */
data class SpaceState(
    val title: String = "",
    val rows: List<RoomRow> = emptyList(),
    val isLoading: Boolean = true,
    /** The space is no longer joined (left here or on another device). */
    val isGone: Boolean = false,
    val isLeaving: Boolean = false,
    val leaveFailed: Boolean = false,
) {
    val isEmpty: Boolean get() = !isLoading && !isGone && rows.isEmpty()
}

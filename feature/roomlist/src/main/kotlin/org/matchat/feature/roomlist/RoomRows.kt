package org.matchat.feature.roomlist

import org.matchat.core.matrix.Draft
import org.matchat.core.model.RoomId
import org.matchat.core.model.RoomSummary
import org.matchat.core.model.SpaceSummary
import org.matchat.core.model.SpaceTree
import org.matchat.core.model.format.RelativeTime

/**
 * The rows of one folder level — the room list's top level (S8, [spaceId]
 * null) or one space (S29): its spaces first, most recent activity first, then
 * its rooms in the session's order. Shared so both screens agree on what lives
 * where (rooms in a joined space appear only inside it).
 */
internal fun SpaceTree.rowsIn(spaceId: RoomId?, drafts: Map<String, Draft>, now: Long): List<RoomRow> {
    val spaceRows = spacesIn(spaceId)
        .map { space -> space to allRoomsUnder(space.id) }
        .sortedByDescending { (_, rooms) -> rooms.maxOfOrNull { it.lastActivityEpochMs ?: 0L } ?: 0L }
        .map { (space, rooms) -> space.toRow(rooms, now) }
    return spaceRows + roomsIn(spaceId).map { it.toRow(drafts[it.id.value], now) }
}

/** [draft] is this room's live draft, if any (draft messages round) — a
 *  plain passthrough of the domain data; the Fragment/Adapter (which
 *  already own every `getString`/resource call in this module — the
 *  ViewModel has none) decide the actual "Draft: …" display text and
 *  styling from it, same boundary as everywhere else here. */
internal fun RoomSummary.toRow(draft: Draft?, now: Long) = RoomRow(
    id = id,
    name = name,
    preview = lastMessage.orEmpty(),
    time = lastActivityEpochMs?.let { RelativeTime.roomListLabel(it, now) }.orEmpty(),
    unreadCount = unreadCount,
    avatarUrl = avatarUrl,
    draft = draft,
)

/** A space folder row: unread and time roll up from every room inside it. */
private fun SpaceSummary.toRow(rooms: List<RoomSummary>, now: Long) = RoomRow(
    id = id,
    name = name,
    preview = "",
    time = rooms.mapNotNull { it.lastActivityEpochMs }.maxOrNull()
        ?.let { RelativeTime.roomListLabel(it, now) }.orEmpty(),
    unreadCount = rooms.sumOf { it.unreadCount },
    avatarUrl = avatarUrl,
    isSpace = true,
    groupCount = rooms.size,
)

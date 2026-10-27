package org.matchat.core.model

/**
 * A space the user has joined (S29). Spaces are folders of rooms the user is
 * already in — a space's rooms the user has not joined are never listed, so a
 * space is not a way to find rooms (AGENTS.md §0).
 */
data class SpaceSummary(
    val id: RoomId,
    val name: String,
    val avatarUrl: String? = null,
    /** Joined spaces this space sits inside; empty for a top-level space. */
    val parentSpaceIds: List<RoomId> = emptyList(),
)

/**
 * The joined-spaces folder tree, built only from rooms and spaces the user is
 * in. Pure, so the room list and space screens share one tested definition of
 * "what belongs where". A parent that is not a joined space does not count, so
 * nothing is ever hidden behind a space the user cannot open.
 */
class SpaceTree(private val rooms: List<RoomSummary>, private val spaces: List<SpaceSummary>) {

    private val joinedSpaceIds: Set<RoomId> = spaces.mapTo(mutableSetOf()) { it.id }

    private fun joinedParents(parents: List<RoomId>): List<RoomId> = parents.filter { it in joinedSpaceIds }

    /** Spaces directly inside [spaceId], or the top-level spaces when null. */
    fun spacesIn(spaceId: RoomId?): List<SpaceSummary> = spaces.filter { space ->
        val parents = joinedParents(space.parentSpaceIds) - space.id
        if (spaceId == null) parents.isEmpty() else spaceId in parents
    }

    /** Rooms directly inside [spaceId], or the rooms in no space when null.
     *  Keeps the order of [rooms] (most recent activity first). */
    fun roomsIn(spaceId: RoomId?): List<RoomSummary> = rooms.filter { room ->
        val parents = joinedParents(room.parentSpaceIds)
        if (spaceId == null) parents.isEmpty() else spaceId in parents
    }

    /** Every room anywhere under [spaceId], through nested spaces (cycle-safe). */
    fun allRoomsUnder(spaceId: RoomId): List<RoomSummary> {
        val seen = mutableSetOf<RoomId>()
        val out = LinkedHashMap<RoomId, RoomSummary>()
        fun walk(id: RoomId) {
            if (!seen.add(id)) return
            roomsIn(id).forEach { out[it.id] = it }
            spacesIn(id).forEach { walk(it.id) }
        }
        walk(spaceId)
        return out.values.toList()
    }

    fun space(id: RoomId): SpaceSummary? = spaces.firstOrNull { it.id == id }
}

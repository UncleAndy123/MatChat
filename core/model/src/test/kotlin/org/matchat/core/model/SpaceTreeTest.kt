package org.matchat.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpaceTreeTest {

    private fun room(id: String, vararg parents: String) =
        RoomSummary(RoomId(id), id, null, null, 0, true, parentSpaceIds = parents.map(::RoomId))

    private fun space(id: String, vararg parents: String) = SpaceSummary(RoomId(id), id, null, parents.map(::RoomId))

    private val tree = SpaceTree(
        rooms = listOf(room("!lobby"), room("!barn", "!farm"), room("!hay", "!fields"), room("!x", "!notjoined")),
        spaces = listOf(space("!farm"), space("!fields", "!farm")),
    )

    @Test
    fun `top level holds rooms in no joined space and top-level spaces`() {
        assertEquals(listOf("!lobby", "!x"), tree.roomsIn(null).map { it.id.value })
        assertEquals(listOf("!farm"), tree.spacesIn(null).map { it.id.value })
    }

    @Test
    fun `a space holds its direct rooms and subspaces`() {
        assertEquals(listOf("!barn"), tree.roomsIn(RoomId("!farm")).map { it.id.value })
        assertEquals(listOf("!fields"), tree.spacesIn(RoomId("!farm")).map { it.id.value })
    }

    @Test
    fun `allRoomsUnder walks nested spaces and survives a cycle`() {
        assertEquals(listOf("!barn", "!hay"), tree.allRoomsUnder(RoomId("!farm")).map { it.id.value })
        val cyclic = SpaceTree(listOf(room("!r", "!a")), listOf(space("!a", "!b"), space("!b", "!a")))
        assertEquals(listOf("!r"), cyclic.allRoomsUnder(RoomId("!a")).map { it.id.value })
    }
}

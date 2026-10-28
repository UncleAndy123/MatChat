package org.matchat.feature.roomlist

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.matchat.core.model.MillisClock
import org.matchat.core.model.RoomId
import org.matchat.core.model.RoomSummary
import org.matchat.core.model.SpaceSummary
import org.matchat.core.testing.FakeDraftStore
import org.matchat.core.testing.FakeMatrixSession

class SpaceViewModelTest {

    private val session = FakeMatrixSession()
    private val farm = RoomId("!farm:s")

    @BeforeEach fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterEach fun tearDown() = Dispatchers.resetMain()

    private fun subject() =
        SpaceViewModel(session, MillisClock { 0L }, FakeDraftStore(), SavedStateHandle(mapOf("spaceId" to farm.value)))

    private fun room(id: String, parent: RoomId? = null) =
        RoomSummary(RoomId(id), id, null, null, 0, true, parentSpaceIds = listOfNotNull(parent))

    @Test
    fun `lists subspaces then joined rooms, and opens each kind`() = runTest {
        session.roomsFlow.value = listOf(room("!barn:s", farm), room("!lobby:s"))
        session.spacesFlow.value = listOf(
            SpaceSummary(farm, "Farm"),
            SpaceSummary(RoomId("!fields:s"), "Fields", parentSpaceIds = listOf(farm)),
        )
        val vm = subject()
        vm.state.test {
            val state = expectMostRecentItem()
            assertEquals("Farm", state.title)
            assertEquals(listOf("!fields:s", "!barn:s"), state.rows.map { it.id.value })
            cancelAndIgnoreRemainingEvents()
        }
        vm.navEvents.test {
            vm.onAction(SpaceAction.OpenRow(RoomId("!fields:s")))
            assertEquals(SpaceNav.Space(RoomId("!fields:s")), awaitItem())
            vm.onAction(SpaceAction.OpenRow(RoomId("!barn:s")))
            assertEquals(SpaceNav.Room(RoomId("!barn:s")), awaitItem())
        }
    }

    @Test
    fun `a joined space with no joined rooms is the empty state`() = runTest {
        session.spacesFlow.value = listOf(SpaceSummary(farm, "Farm"))
        subject().state.test {
            assertTrue(expectMostRecentItem().isEmpty)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `leaving leaves the space and goes back`() = runTest {
        session.spacesFlow.value = listOf(SpaceSummary(farm, "Farm"))
        val vm = subject()
        vm.navEvents.test {
            vm.onAction(SpaceAction.Leave)
            assertEquals(SpaceNav.Back, awaitItem())
        }
        assertEquals(listOf(farm), session.leftRooms)
    }

    @Test
    fun `a space that disappears after being shown is gone`() = runTest {
        session.spacesFlow.value = listOf(SpaceSummary(farm, "Farm"))
        val vm = subject()
        vm.state.test {
            assertFalse(expectMostRecentItem().isGone)
            session.spacesFlow.value = emptyList()
            assertTrue(expectMostRecentItem().isGone)
            cancelAndIgnoreRemainingEvents()
        }
    }
}

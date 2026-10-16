package org.matchat.feature.roomlist

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
import org.matchat.core.matrix.Draft
import org.matchat.core.model.InviteSummary
import org.matchat.core.model.MillisClock
import org.matchat.core.model.RoomId
import org.matchat.core.model.RoomSummary
import org.matchat.core.model.SyncState
import org.matchat.core.policy.Policy
import org.matchat.core.testing.FakeDraftStore
import org.matchat.core.testing.FakeMatrixSession
import org.matchat.core.testing.FakePolicyProvider

class RoomListViewModelTest {

    private val session = FakeMatrixSession()
    private val policy = FakePolicyProvider()
    private val clock = MillisClock { 0L }
    private val draftStore = FakeDraftStore()

    private fun subject() = RoomListViewModel(session, policy, clock, draftStore)

    @BeforeEach fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterEach fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `empty after sync is the empty state`() = runTest {
        session.syncFlow.value = SyncState.IDLE
        subject().state.test {
            // Skip the initial default emission, then read the mapped state.
            val state = expectMostRecentItem()
            assertTrue(state.isEmpty)
            assertFalse(state.isLoading)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `invites produce a band with the count`() = runTest {
        session.invitesFlow.value = listOf(
            invite("!a:server"),
            invite("!b:server"),
        )
        subject().state.test {
            assertEquals(InviteBand(2), expectMostRecentItem().inviteBand)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `offline sync sets offline, not empty`() = runTest {
        session.roomsFlow.value = listOf(room("!a:server"))
        session.syncFlow.value = SyncState.OFFLINE
        subject().state.test {
            val state = expectMostRecentItem()
            assertTrue(state.isOffline)
            assertFalse(state.isEmpty)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `policy without direct chat disables new message`() = runTest {
        policy.push(Policy.UNMANAGED.copy(allowDirectChat = false))
        subject().state.test {
            assertFalse(expectMostRecentItem().newMessageEnabled)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `opening a room emits a nav event`() = runTest {
        val vm = subject()
        vm.navEvents.test {
            vm.onAction(RoomListAction.OpenRoom(RoomId("!a:server")))
            assertEquals(RoomListNav.Room(RoomId("!a:server")), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a room's avatarUrl carries through to its row`() = runTest {
        session.roomsFlow.value = listOf(room("!a:server", avatarUrl = "mxc://server/room-avatar"))
        subject().state.test {
            assertEquals("mxc://server/room-avatar", expectMostRecentItem().rooms.single().avatarUrl)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `no avatar set maps to a null avatarUrl`() = runTest {
        session.roomsFlow.value = listOf(room("!a:server"))
        subject().state.test {
            assertEquals(null, expectMostRecentItem().rooms.single().avatarUrl)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a room with a live draft carries it through to its row`() = runTest {
        session.roomsFlow.value = listOf(room("!a:server"))
        draftStore.draftsFlow.value = mapOf("!a:server" to Draft(text = "unfinished thought"))
        subject().state.test {
            val row = expectMostRecentItem().rooms.single()
            assertEquals(Draft(text = "unfinished thought"), row.draft)
            assertTrue(row.isDraft)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a room with no draft carries a null draft`() = runTest {
        session.roomsFlow.value = listOf(room("!a:server"))
        subject().state.test {
            val row = expectMostRecentItem().rooms.single()
            assertEquals(null, row.draft)
            assertFalse(row.isDraft)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun room(id: String, avatarUrl: String? = null) = RoomSummary(
        id = RoomId(id),
        name = "Room",
        lastMessage = "hi",
        lastActivityEpochMs = 0L,
        unreadCount = 0,
        isEncrypted = true,
        avatarUrl = avatarUrl,
    )

    private fun invite(id: String) = InviteSummary(
        roomId = RoomId(id),
        roomName = "R",
        inviter = org.matchat.core.model.UserId("@w:server"),
        inviterName = null,
        isDirect = false,
        isEncrypted = true,
        senderDomain = "server",
        allowedByPolicy = true,
    )
}

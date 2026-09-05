package org.matchat.feature.roomlist

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.matchat.core.model.InviteSummary
import org.matchat.core.model.MillisClock
import org.matchat.core.model.RoomId
import org.matchat.core.model.RoomSummary
import org.matchat.core.model.SyncState
import org.matchat.core.policy.Policy
import org.matchat.core.testing.FakeMatrixSession
import org.matchat.core.testing.FakePolicyProvider

class RoomListViewModelTest {

    private val session = FakeMatrixSession()
    private val policy = FakePolicyProvider()
    private val clock = MillisClock { 0L }

    private fun subject() = RoomListViewModel(session, policy, clock)

    @BeforeEach fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
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
            invite("!a:server"), invite("!b:server"),
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

    private fun room(id: String) = RoomSummary(
        id = RoomId(id), name = "Room", lastMessage = "hi",
        lastActivityEpochMs = 0L, unreadCount = 0, isEncrypted = true,
    )

    private fun invite(id: String) = InviteSummary(
        roomId = RoomId(id), roomName = "R", inviter = org.matchat.core.model.UserId("@w:server"),
        inviterName = null, isDirect = false, isEncrypted = true,
        senderDomain = "server", allowedByPolicy = true,
    )
}

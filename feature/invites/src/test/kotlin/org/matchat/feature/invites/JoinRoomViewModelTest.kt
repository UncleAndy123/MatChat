package org.matchat.feature.invites

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.matchat.core.model.JoinOutcome
import org.matchat.core.model.JoinRule
import org.matchat.core.model.PreviewMembership
import org.matchat.core.model.RoomId
import org.matchat.core.model.RoomPreview
import org.matchat.core.model.RoomSummary
import org.matchat.core.model.SpaceSummary
import org.matchat.core.policy.Policy
import org.matchat.core.testing.FakeMatrixSession
import org.matchat.core.testing.FakePolicyProvider

@OptIn(ExperimentalCoroutinesApi::class)
class JoinRoomViewModelTest {

    private val session = FakeMatrixSession()
    private val policy = FakePolicyProvider()
    private val roomId = RoomId("!abc")
    private val link = "https://matrix.to/#/!abc?via=unredacted.org"

    @BeforeEach fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterEach fun tearDown() = Dispatchers.resetMain()

    private fun subject(rawLink: String = link) =
        JoinRoomViewModel(session, policy, SavedStateHandle(mapOf("link" to rawLink)))

    private fun preview(joinRule: JoinRule = JoinRule.PUBLIC, membership: PreviewMembership = PreviewMembership.NONE) =
        RoomPreview(roomId, "Barn Crew", "Chores", 12, joinRule, membership)

    @Test
    fun `a public room offers Join and shows its details`() = runTest {
        session.previewResult = { Result.success(preview()) }
        val state = subject().state.value
        assertEquals("Barn Crew", state.name)
        assertEquals("!abc", state.address)
        assertEquals(12, state.memberCount)
        assertEquals(JoinButton.JOIN, state.button)
        assertNull(state.note)
    }

    @Test
    fun `a knock room offers Ask to join, which prompts for a message and sends it`() = runTest {
        session.previewResult = { Result.success(preview(JoinRule.KNOCK)) }
        val vm = subject()
        assertEquals(JoinButton.ASK, vm.state.value.button)
        assertEquals(JoinNote.KNOCK_ROOM, vm.state.value.note)

        vm.navEvents.test {
            vm.onAction(JoinRoomAction.Activate)
            assertEquals(JoinRoomNav.PromptForMessage, awaitItem())
        }
        vm.onAction(JoinRoomAction.SendRequest("  Hi, it's Wayne from the barn  "))
        assertEquals("Hi, it's Wayne from the barn", session.lastKnockMessage)
        assertEquals(JoinNote.REQUEST_SENT, vm.state.value.note)
        assertEquals(JoinButton.NONE, vm.state.value.button)
    }

    @Test
    fun `a blank message sends the request without one`() = runTest {
        session.previewResult = { Result.success(preview(JoinRule.KNOCK)) }
        val vm = subject()
        vm.onAction(JoinRoomAction.SendRequest("   "))
        assertNull(session.lastKnockMessage)
        assertEquals(JoinNote.REQUEST_SENT, vm.state.value.note)
    }

    @Test
    fun `a 403 on join turns into Ask to join`() = runTest {
        // The "You are not invited to this room" case: no preview, join refused.
        session.joinResult = { JoinOutcome.NeedsInvite }
        val vm = subject()
        assertEquals(JoinNote.NO_PREVIEW, vm.state.value.note)

        vm.onAction(JoinRoomAction.Activate)
        assertEquals(JoinNote.NEEDS_INVITE, vm.state.value.note)
        assertEquals(JoinButton.ASK, vm.state.value.button)
    }

    @Test
    fun `a failed request to join is explained and can be retried`() = runTest {
        session.previewResult = { Result.success(preview(JoinRule.KNOCK)) }
        session.knockResult = { Result.failure(IllegalStateException()) }
        val vm = subject()
        vm.onAction(JoinRoomAction.SendRequest(""))
        assertEquals(JoinError.KNOCK_FAILED, vm.state.value.error)
        assertEquals(JoinButton.ASK, vm.state.value.button)
    }

    @Test
    fun `a failed join is a retryable error`() = runTest {
        session.previewResult = { Result.success(preview()) }
        session.joinResult = { JoinOutcome.Failed }
        val vm = subject()
        vm.onAction(JoinRoomAction.Activate)
        assertEquals(JoinError.JOIN_FAILED, vm.state.value.error)
        assertEquals(JoinButton.JOIN, vm.state.value.button)
    }

    @Test
    fun `joining opens the room`() = runTest {
        session.previewResult = { Result.success(preview()) }
        session.roomsFlow.value = listOf(RoomSummary(roomId, "Barn Crew", null, null, 0, true))
        val vm = subject()
        vm.navEvents.test {
            vm.onAction(JoinRoomAction.Activate)
            assertEquals(JoinRoomNav.OpenRoom(roomId), awaitItem())
        }
    }

    @Test
    fun `joining a space opens it as a folder`() = runTest {
        session.previewResult = { Result.success(preview().copy(isSpace = true)) }
        session.spacesFlow.value = listOf(SpaceSummary(roomId, "Farm"))
        val vm = subject()
        vm.navEvents.test {
            vm.onAction(JoinRoomAction.Activate)
            assertEquals(JoinRoomNav.OpenSpace(roomId), awaitItem())
        }
    }

    @Test
    fun `already a member offers Open`() = runTest {
        session.previewResult = { Result.success(preview(membership = PreviewMembership.JOINED)) }
        val vm = subject()
        assertEquals(JoinButton.OPEN, vm.state.value.button)
        vm.navEvents.test {
            vm.onAction(JoinRoomAction.Activate)
            assertEquals(JoinRoomNav.OpenRoom(roomId), awaitItem())
        }
    }

    @Test
    fun `invite-only and already-requested rooms have no button`() = runTest {
        session.previewResult = { Result.success(preview(JoinRule.INVITE_ONLY)) }
        assertEquals(JoinButton.NONE, subject().state.value.button)
        session.previewResult = { Result.success(preview(membership = PreviewMembership.KNOCKED)) }
        assertEquals(JoinNote.REQUEST_SENT, subject().state.value.note)
    }

    @Test
    fun `a blocked server is explained, and a policy push is observed live`() = runTest {
        session.previewResult = { Result.success(preview()) }
        policy.push(Policy(isManaged = true, allowedDomains = listOf("example.org")))
        val vm = subject()
        assertEquals("unredacted.org", vm.state.value.blockedDomain)
        assertEquals(JoinButton.NONE, vm.state.value.button)

        policy.push(Policy.UNMANAGED)
        assertNull(vm.state.value.blockedDomain)
        assertEquals(JoinButton.JOIN, vm.state.value.button)
    }

    @Test
    fun `a link that is not a room is explained`() = runTest {
        val state = subject("https://matrix.to/#/@wayne:example.org").state.value
        assertEquals(JoinNote.BAD_LINK, state.note)
        assertEquals(JoinButton.NONE, state.button)
    }
}

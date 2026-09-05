package org.matchat.feature.invites

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.matchat.core.model.InviteSummary
import org.matchat.core.model.RoomId
import org.matchat.core.model.UserId
import org.matchat.core.testing.FakeMatrixSession

class InvitesViewModelTest {

    private val session = FakeMatrixSession()

    @BeforeEach fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @AfterEach fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a blocked invite is shown and tagged, never hidden`() = runTest {
        session.invitesFlow.value = listOf(
            invite("!ok:s", allowed = true),
            invite("!blocked:s", allowed = false),
        )
        InvitesViewModel(session).state.test {
            val rows = expectMostRecentItem().invites
            assertEquals(2, rows.size) // blocked one is still present
            assertTrue(rows.first { it.roomId == RoomId("!blocked:s") }.blocked)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun invite(id: String, allowed: Boolean) = InviteSummary(
        roomId = RoomId(id), roomName = "Room", inviter = UserId("@w:example.org"),
        inviterName = null, isDirect = false, isEncrypted = true,
        senderDomain = "example.org", allowedByPolicy = allowed,
    )
}

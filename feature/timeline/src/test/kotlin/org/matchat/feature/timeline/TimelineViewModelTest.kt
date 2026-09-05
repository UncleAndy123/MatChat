package org.matchat.feature.timeline

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.matchat.core.model.EventId
import org.matchat.core.model.MillisClock
import org.matchat.core.model.RoomId
import org.matchat.core.model.SendState
import org.matchat.core.model.TimelineItem
import org.matchat.core.model.UserId
import org.matchat.core.testing.FakeMatrixSession

class TimelineViewModelTest {

    private val session = FakeMatrixSession()
    private val roomId = RoomId("!room:server")
    private val clock = MillisClock { 0L }

    private fun subject() =
        TimelineViewModel(session, clock, SavedStateHandle(mapOf("roomId" to roomId.value)))

    @BeforeEach fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @AfterEach fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `sender name shown only when it changes`() = runTest {
        val fake = session.timeline(roomId) as org.matchat.core.testing.FakeTimeline
        fake.emit(
            listOf(
                message("a", "@wayne:s", "Wayne", "hi"),
                message("b", "@wayne:s", "Wayne", "again"),
                message("c", "@merv:s", "Merv", "hello"),
            ),
        )
        subject().state.test {
            val rows = expectMostRecentItem().rows.filterIsInstance<TimelineRow.Message>()
            assertEquals("Wayne", rows[0].senderName)
            assertNull(rows[1].senderName) // same sender: name suppressed
            assertEquals("Merv", rows[2].senderName)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `empty timeline is the empty state`() = runTest {
        subject().state.test {
            assertTrue(expectMostRecentItem().isEmpty)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `sending a blank message is a no-op`() = runTest {
        val fake = session.timeline(roomId) as org.matchat.core.testing.FakeTimeline
        val vm = subject()
        vm.onAction(TimelineAction.Send("   "))
        testScheduler.advanceUntilIdle()
        assertTrue(fake.sent.isEmpty())
    }

    private fun message(id: String, sender: String, name: String, body: String) =
        TimelineItem.Message(
            eventId = EventId(id), sender = UserId(sender), senderName = name,
            body = body, timestampEpochMs = 0L, isOwn = false, sendState = SendState.SENT,
        )
}

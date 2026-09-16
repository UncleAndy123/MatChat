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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.matchat.core.model.EventId
import org.matchat.core.model.ReactionSummary
import org.matchat.core.model.RoomId
import org.matchat.core.model.SendState
import org.matchat.core.model.TimelineItem
import org.matchat.core.model.UserId
import org.matchat.core.testing.FakeMatrixSession

class MessageInfoViewModelTest {

    private val session = FakeMatrixSession()
    private val roomId = RoomId("!room:server")
    private val eventId = EventId("a")

    private fun subject() = MessageInfoViewModel(
        session,
        SavedStateHandle(mapOf("roomId" to roomId.value, "eventId" to eventId.value)),
    )

    @BeforeEach fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterEach fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `reactions for the matching event carry through`() = runTest {
        val fake = session.timeline(roomId) as org.matchat.core.testing.FakeTimeline
        fake.emit(
            listOf(
                message(
                    eventId,
                    reactions = listOf(
                        ReactionSummary("👍", 2, reactedByMe = true, senderNames = listOf("Wayne", "Merv")),
                    ),
                ),
                message(EventId("other"), reactions = listOf(ReactionSummary("😀", 1, reactedByMe = false))),
            ),
        )
        subject().reactions.test {
            val reactions = expectMostRecentItem()
            assertEquals(1, reactions.size)
            assertEquals(listOf("Wayne", "Merv"), reactions.single().senderNames)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an event not in the loaded window has empty reactions, not a crash`() = runTest {
        subject().reactions.test {
            assertTrue(expectMostRecentItem().isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun message(id: EventId, reactions: List<ReactionSummary> = emptyList()) = TimelineItem.Message(
        eventId = id,
        sender = UserId("@wayne:s"),
        senderName = "Wayne",
        body = "hi",
        timestampEpochMs = 0L,
        isOwn = false,
        sendState = SendState.SENT,
        reactions = reactions,
    )
}

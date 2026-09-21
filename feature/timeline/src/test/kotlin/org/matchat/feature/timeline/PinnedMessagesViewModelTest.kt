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
import org.matchat.core.model.RoomId
import org.matchat.core.model.SendState
import org.matchat.core.model.TimelineItem
import org.matchat.core.model.UserId
import org.matchat.core.testing.FakeMatrixSession

class PinnedMessagesViewModelTest {

    private val session = FakeMatrixSession()
    private val roomId = RoomId("!room:server")

    private fun subject() = PinnedMessagesViewModel(session, SavedStateHandle(mapOf("roomId" to roomId.value)))

    @BeforeEach fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterEach fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `only pinned messages are shown, unpinned ones are filtered out`() = runTest {
        val fake = session.timeline(roomId) as org.matchat.core.testing.FakeTimeline
        fake.emit(
            listOf(
                message("a", "hi", isPinned = true),
                message("b", "not pinned", isPinned = false),
                message("c", "also pinned", isPinned = true),
            ),
        )
        subject().state.test {
            val keys = expectMostRecentItem().rows.filterIsInstance<RoomInfoRow.Field>().map { it.key }
            assertEquals(listOf("a", "c"), keys)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `no pinned messages is an empty row list`() = runTest {
        val fake = session.timeline(roomId) as org.matchat.core.testing.FakeTimeline
        fake.emit(listOf(message("a", "hi", isPinned = false)))
        subject().state.test {
            assertTrue(expectMostRecentItem().rows.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `roomId exposes the room this screen is scoped to`() {
        assertEquals(roomId, subject().roomId())
    }

    private fun message(id: String, body: String, isPinned: Boolean) = TimelineItem.Message(
        eventId = EventId(id),
        sender = UserId("@wayne:s"),
        senderName = "Wayne",
        body = body,
        timestampEpochMs = 0L,
        isOwn = false,
        sendState = SendState.SENT,
        isPinned = isPinned,
    )
}

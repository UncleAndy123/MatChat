package org.matchat.client.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.matchat.client.sync.NotificationGate.Action
import org.matchat.client.sync.NotificationGate.DECRYPT_WAIT_MS
import org.matchat.client.sync.NotificationGate.RoomState
import org.matchat.core.model.MediaKind
import org.matchat.core.model.RoomId
import org.matchat.core.model.RoomSummary

/** When a message notification is posted: only once its text is readable, or
 *  without the text after [DECRYPT_WAIT_MS] (docs/ARCHITECTURE.md). */
class NotificationGateTest {

    private fun room(
        unread: Int,
        ts: Long?,
        text: String? = "hi",
        own: Boolean = false,
        undecrypted: Boolean = false,
        media: MediaKind? = null,
    ) = RoomSummary(
        id = RoomId("!r:example.org"),
        name = "Room",
        lastMessage = text,
        lastActivityEpochMs = ts,
        unreadCount = unread,
        isEncrypted = true,
        lastMessageIsOwn = own,
        lastMessageUndecrypted = undecrypted,
        lastMessageMedia = media,
    )

    private val seeded = RoomState(unread = 0, accountedTs = 100L)

    @Test
    fun `seed records the existing count and latest message so history never alerts`() {
        assertEquals(RoomState(3, 100L), NotificationGate.seed(room(unread = 3, ts = 100L)))
        assertEquals(RoomState(0, 0L), NotificationGate.seed(room(unread = 0, ts = null)))
    }

    @Test
    fun `a new readable message posts with its text right away`() {
        val r = NotificationGate.decide(seeded, room(unread = 1, ts = 200L), nowMs = 0L, enabled = true)
        assertEquals(Action.POST_WITH_TEXT, r.action)
        assertEquals(RoomState(1, 200L), r.state)
    }

    @Test
    fun `media without text is readable`() {
        val r = NotificationGate.decide(
            seeded,
            room(unread = 1, ts = 200L, text = null, media = MediaKind.IMAGE),
            0L,
            true,
        )
        assertEquals(Action.POST_WITH_TEXT, r.action)
    }

    @Test
    fun `an undecrypted message waits, then posts with its text once decrypted`() {
        val held = NotificationGate.decide(seeded, room(1, 200L, text = null, undecrypted = true), 0L, true)
        assertEquals(Action.HOLD, held.action)
        assertEquals(0L, held.state.pendingSinceMs)

        val posted = NotificationGate.decide(held.state, room(1, 200L), nowMs = 1_000L, enabled = true)
        assertEquals(Action.POST_WITH_TEXT, posted.action)
        assertNull(posted.state.pendingSinceMs)
    }

    @Test
    fun `a count that rises before the latest message updates waits for the new message`() {
        // The room still shows the previous message (ts 100, already accounted).
        val held = NotificationGate.decide(seeded, room(1, 100L, text = "old"), 0L, true)
        assertEquals(Action.HOLD, held.action)

        val posted = NotificationGate.decide(held.state, room(1, 200L, text = "new"), 500L, true)
        assertEquals(Action.POST_WITH_TEXT, posted.action)
    }

    @Test
    fun `a message still unreadable after the wait posts without its text`() {
        val locked = room(1, 200L, text = null, undecrypted = true)
        val held = NotificationGate.decide(seeded, locked, 0L, true)
        val early = NotificationGate.decide(held.state, locked, DECRYPT_WAIT_MS - 1, true)
        assertEquals(Action.HOLD, early.action)

        val late = NotificationGate.decide(early.state, locked, DECRYPT_WAIT_MS, true)
        assertEquals(Action.POST_GENERIC, late.action)
        assertEquals(RoomState(1, 200L), late.state)

        // Nothing more to do for it once posted, even when it decrypts later.
        assertEquals(Action.NONE, NotificationGate.decide(late.state, room(1, 200L), DECRYPT_WAIT_MS + 1, true).action)
    }

    @Test
    fun `a further message while one is waiting keeps the original deadline`() {
        val held = NotificationGate.decide(seeded, room(1, 200L, null, undecrypted = true), 0L, true)
        val again = NotificationGate.decide(held.state, room(2, 300L, null, undecrypted = true), 5_000L, true)
        assertEquals(Action.HOLD, again.action)
        assertEquals(0L, again.state.pendingSinceMs)
        assertEquals(2, again.state.unread)
    }

    @Test
    fun `reading the room cancels, including a notification still waiting`() {
        val shown = RoomState(unread = 1, accountedTs = 200L)
        assertEquals(Action.CANCEL, NotificationGate.decide(shown, room(0, 200L), 0L, true).action)

        val waiting = RoomState(unread = 1, accountedTs = 100L, pendingSinceMs = 0L)
        val r = NotificationGate.decide(waiting, room(0, 200L), 0L, true)
        assertEquals(Action.CANCEL, r.action)
        assertEquals(RoomState(0, 200L), r.state)
    }

    @Test
    fun `nothing unread and nothing waiting does nothing`() {
        assertEquals(Action.NONE, NotificationGate.decide(seeded, room(0, 200L), 0L, true).action)
    }

    @Test
    fun `notifications off never posts and drops anything waiting`() {
        val waiting = RoomState(unread = 1, accountedTs = 100L, pendingSinceMs = 0L)
        val r = NotificationGate.decide(waiting, room(2, 200L), DECRYPT_WAIT_MS, enabled = false)
        assertEquals(Action.NONE, r.action)
        assertNull(r.state.pendingSinceMs)
    }

    @Test
    fun `our own latest message is never the text, and counts as accounted for`() {
        val held = NotificationGate.decide(seeded, room(1, 200L, own = true), 0L, true)
        assertEquals(Action.HOLD, held.action)
        assertEquals(200L, held.state.accountedTs)
    }

    @Test
    fun `no change in the count with nothing waiting does nothing`() {
        val shown = RoomState(unread = 1, accountedTs = 200L)
        assertEquals(Action.NONE, NotificationGate.decide(shown, room(1, 300L), 0L, true).action)
    }

    @Test
    fun `a room not seen before treats its latest message as new`() {
        val r = NotificationGate.decide(null, room(1, 200L), 0L, true)
        assertEquals(Action.POST_WITH_TEXT, r.action)
    }
}

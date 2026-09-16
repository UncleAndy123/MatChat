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
import org.matchat.core.matrix.Draft
import org.matchat.core.matrix.DraftAttachment
import org.matchat.core.model.EventId
import org.matchat.core.model.MediaKind
import org.matchat.core.model.Membership
import org.matchat.core.model.MillisClock
import org.matchat.core.model.ReactionSummary
import org.matchat.core.model.RoomId
import org.matchat.core.model.RoomMemberSummary
import org.matchat.core.model.SeenBy
import org.matchat.core.model.SendState
import org.matchat.core.model.TimelineItem
import org.matchat.core.model.UserId
import org.matchat.core.testing.FakeDraftStore
import org.matchat.core.testing.FakeMatrixSession
import org.matchat.core.testing.FakePolicyProvider

class TimelineViewModelTest {

    private val session = FakeMatrixSession()
    private val roomId = RoomId("!room:server")
    private val clock = MillisClock { 0L }
    private val policy = FakePolicyProvider()
    private val draftStore = FakeDraftStore()

    private fun subject() =
        TimelineViewModel(session, clock, policy, draftStore, SavedStateHandle(mapOf("roomId" to roomId.value)))

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

    @Test
    fun `staging an attachment doesn't send it until Send`() = runTest {
        val fake = session.timeline(roomId) as org.matchat.core.testing.FakeTimeline
        val vm = subject()
        val attachment = PendingAttachment("/cache/photo.jpg", "image/jpeg", MediaKind.IMAGE, "photo.jpg")
        vm.onAction(TimelineAction.StageAttachment(attachment))
        testScheduler.advanceUntilIdle()
        assertTrue(fake.sentMediaCalls.isEmpty())
        vm.state.test {
            assertEquals(attachment, expectMostRecentItem().pendingAttachment)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Send with a staged attachment sends it with the typed text as its caption`() = runTest {
        val fake = session.timeline(roomId) as org.matchat.core.testing.FakeTimeline
        val vm = subject()
        val attachment = PendingAttachment("/cache/photo.jpg", "image/jpeg", MediaKind.IMAGE, "photo.jpg")
        vm.onAction(TimelineAction.StageAttachment(attachment))
        vm.onAction(TimelineAction.Send("look at this"))
        testScheduler.advanceUntilIdle()
        assertEquals(
            listOf(
                org.matchat.core.testing.FakeTimeline.SentMedia(
                    "/cache/photo.jpg",
                    "image/jpeg",
                    MediaKind.IMAGE,
                    "look at this",
                ),
            ),
            fake.sentMediaCalls,
        )
        assertTrue(fake.sent.isEmpty()) // not also sent as a plain text message
        vm.state.test {
            assertNull(expectMostRecentItem().pendingAttachment) // cleared after sending
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Send with a staged attachment and no typed text sends with a null caption`() = runTest {
        val fake = session.timeline(roomId) as org.matchat.core.testing.FakeTimeline
        val vm = subject()
        val attachment = PendingAttachment("/cache/photo.jpg", "image/jpeg", MediaKind.IMAGE, "photo.jpg")
        vm.onAction(TimelineAction.StageAttachment(attachment))
        vm.onAction(TimelineAction.Send("   "))
        testScheduler.advanceUntilIdle()
        assertEquals(null, fake.sentMediaCalls.single().caption)
    }

    @Test
    fun `ClearPendingAttachment discards the staged attachment without sending`() = runTest {
        val fake = session.timeline(roomId) as org.matchat.core.testing.FakeTimeline
        val vm = subject()
        val attachment = PendingAttachment("/cache/photo.jpg", "image/jpeg", MediaKind.IMAGE, "photo.jpg")
        vm.onAction(TimelineAction.StageAttachment(attachment))
        vm.onAction(TimelineAction.ClearPendingAttachment)
        testScheduler.advanceUntilIdle()
        assertTrue(fake.sentMediaCalls.isEmpty())
        vm.state.test {
            assertNull(expectMostRecentItem().pendingAttachment)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `sender avatar and seen-by carry through to the row`() = runTest {
        val fake = session.timeline(roomId) as org.matchat.core.testing.FakeTimeline
        fake.emit(
            listOf(
                message(
                    "a",
                    "@wayne:s",
                    "Wayne",
                    "hi",
                    senderAvatarUrl = "mxc://s/wayne-avatar",
                    seenBy = listOf(SeenBy(UserId("@merv:s"), "mxc://s/merv-avatar")),
                ),
            ),
        )
        subject().state.test {
            val row = expectMostRecentItem().rows.filterIsInstance<TimelineRow.Message>().single()
            assertEquals("mxc://s/wayne-avatar", row.senderAvatarUrl)
            assertEquals(listOf(SeenBy(UserId("@merv:s"), "mxc://s/merv-avatar")), row.seenBy)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `reactions carry through to the row`() = runTest {
        val fake = session.timeline(roomId) as org.matchat.core.testing.FakeTimeline
        fake.emit(
            listOf(
                message(
                    "a",
                    "@wayne:s",
                    "Wayne",
                    "hi",
                    reactions = listOf(ReactionSummary("👍", 2, reactedByMe = true)),
                ),
            ),
        )
        subject().state.test {
            val row = expectMostRecentItem().rows.filterIsInstance<TimelineRow.Message>().single()
            assertEquals(listOf(ReactionSummary("👍", 2, reactedByMe = true)), row.reactions)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `toggling a reaction calls through to the timeline`() = runTest {
        val fake = session.timeline(roomId) as org.matchat.core.testing.FakeTimeline
        subject().toggleReaction(EventId("a"), "👍")
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(EventId("a") to "👍"), fake.toggledReactions)
    }

    @Test
    fun `isPinned carries through to the row`() = runTest {
        val fake = session.timeline(roomId) as org.matchat.core.testing.FakeTimeline
        fake.emit(listOf(message("a", "@wayne:s", "Wayne", "hi", isPinned = true)))
        subject().state.test {
            val row = expectMostRecentItem().rows.filterIsInstance<TimelineRow.Message>().single()
            assertTrue(row.isPinned)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `pinnedCount counts only pinned rows`() = runTest {
        val fake = session.timeline(roomId) as org.matchat.core.testing.FakeTimeline
        fake.emit(
            listOf(
                message("a", "@wayne:s", "Wayne", "hi", isPinned = true),
                message("b", "@wayne:s", "Wayne", "not pinned", isPinned = false),
                message("c", "@merv:s", "Merv", "also pinned", isPinned = true),
            ),
        )
        subject().state.test {
            assertEquals(2, expectMostRecentItem().pinnedCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setPinned calls through to the timeline`() = runTest {
        val fake = session.timeline(roomId) as org.matchat.core.testing.FakeTimeline
        subject().setPinned(EventId("a"), true)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(EventId("a") to true), fake.pinnedChanges)
    }

    @Test
    fun `a rejected pin write emits a Toast instead of silently doing nothing`() = runTest {
        val fake = session.timeline(roomId) as org.matchat.core.testing.FakeTimeline
        fake.pinnedResult = false
        val vm = subject()
        vm.setPinned(EventId("a"), true)
        testScheduler.advanceUntilIdle()
        vm.navEvents.test {
            assertEquals(TimelineNav.Toast(TimelineToastKey.PIN_FAILED), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a successful pin write emits no Toast`() = runTest {
        val fake = session.timeline(roomId) as org.matchat.core.testing.FakeTimeline
        fake.pinnedResult = true
        val vm = subject()
        vm.setPinned(EventId("a"), true)
        testScheduler.advanceUntilIdle()
        vm.navEvents.test {
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `typing indicator shows the member's display name, not the raw id`() = runTest {
        session.members = listOf(
            RoomMemberSummary(UserId("@wayne:s"), "Wayne", Membership.JOINED, isSelf = false),
        )
        val fake = session.timeline(roomId) as org.matchat.core.testing.FakeTimeline
        val vm = subject()
        testScheduler.advanceUntilIdle() // let the init-block member fetch land
        fake.emitTyping(listOf(UserId("@wayne:s")))
        vm.state.test {
            assertEquals("Wayne is typing…", expectMostRecentItem().typingText)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `typing indicator falls back to the id's localpart for an unknown member`() = runTest {
        val fake = session.timeline(roomId) as org.matchat.core.testing.FakeTimeline
        val vm = subject()
        testScheduler.advanceUntilIdle()
        fake.emitTyping(listOf(UserId("@stranger:s")))
        vm.state.test {
            assertEquals("stranger is typing…", expectMostRecentItem().typingText)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun message(
        id: String,
        sender: String,
        name: String,
        body: String,
        senderAvatarUrl: String? = null,
        seenBy: List<SeenBy> = emptyList(),
        reactions: List<ReactionSummary> = emptyList(),
        isPinned: Boolean = false,
    ) = TimelineItem.Message(
        eventId = EventId(id), sender = UserId(sender), senderName = name,
        body = body, timestampEpochMs = 0L, isOwn = false, sendState = SendState.SENT,
        senderAvatarUrl = senderAvatarUrl, seenBy = seenBy, reactions = reactions, isPinned = isPinned,
    )

    private fun mediaItem(id: String, kind: MediaKind, durationMs: Long? = null, waveform: List<Float>? = null) =
        TimelineItem.Media(
            eventId = EventId(id), sender = UserId("@wayne:s"), senderName = "Wayne",
            body = "voice.m4a", timestampEpochMs = 0L, isOwn = false, sendState = SendState.SENT,
            kind = kind, filename = "voice.m4a", caption = null, mimeType = "audio/mp4",
            sizeBytes = null, durationMs = durationMs, waveform = waveform,
        )

    @Test
    fun `a VOICE item with a waveform renders as a VoiceBubble row carrying it through`() = runTest {
        val fake = session.timeline(roomId) as org.matchat.core.testing.FakeTimeline
        val waveform = listOf(0.1f, 0.5f, 0.9f)
        fake.emit(listOf(mediaItem("a", MediaKind.VOICE, durationMs = 12_000L, waveform = waveform)))
        subject().state.test {
            val row = expectMostRecentItem().rows.filterIsInstance<TimelineRow.VoiceBubble>().single()
            assertEquals(waveform, row.waveform)
            assertEquals("0:12", row.duration)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an AUDIO item with no waveform gets the flat placeholder, not an empty list`() = runTest {
        val fake = session.timeline(roomId) as org.matchat.core.testing.FakeTimeline
        fake.emit(listOf(mediaItem("a", MediaKind.AUDIO, waveform = null)))
        subject().state.test {
            val row = expectMostRecentItem().rows.filterIsInstance<TimelineRow.VoiceBubble>().single()
            assertTrue(row.waveform.isNotEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Send with a staged voice attachment sends via sendVoice, not sendMedia`() = runTest {
        val fake = session.timeline(roomId) as org.matchat.core.testing.FakeTimeline
        val vm = subject()
        val attachment = PendingAttachment(
            "/cache/voice.m4a",
            "audio/mp4",
            MediaKind.VOICE,
            "Voice message (0:05)",
            durationMs = 5_000L,
            waveform = listOf(0.2f, 0.4f),
        )
        vm.onAction(TimelineAction.StageAttachment(attachment))
        vm.onAction(TimelineAction.Send("   ")) // no caption typed
        testScheduler.advanceUntilIdle()
        val expected = org.matchat.core.testing.FakeTimeline
            .SentVoice("/cache/voice.m4a", "audio/mp4", 5_000L, listOf(0.2f, 0.4f))
        assertEquals(listOf(expected), fake.sentVoiceCalls)
        assertTrue(fake.sentMediaCalls.isEmpty())
        assertTrue(fake.sent.isEmpty()) // nothing typed -> no follow-up text message
    }

    @Test
    fun `Send with a staged voice attachment and typed text also sends the text separately`() = runTest {
        val fake = session.timeline(roomId) as org.matchat.core.testing.FakeTimeline
        val vm = subject()
        val attachment = PendingAttachment(
            "/cache/voice.m4a",
            "audio/mp4",
            MediaKind.VOICE,
            "Voice message (0:05)",
            durationMs = 5_000L,
            waveform = listOf(0.2f, 0.4f),
        )
        vm.onAction(TimelineAction.StageAttachment(attachment))
        vm.onAction(TimelineAction.Send("also this"))
        testScheduler.advanceUntilIdle()
        assertEquals(1, fake.sentVoiceCalls.size)
        assertEquals(listOf("also this"), fake.sent) // sent as its own message, not a caption
    }

    @Test
    fun `a saved draft's text is restored into composeText on open`() = runTest {
        draftStore.draftsFlow.value = mapOf(roomId.value to Draft(text = "unfinished thought"))
        assertEquals("unfinished thought", subject().composeText.value)
    }

    @Test
    fun `a saved draft's attachment is restored as the pending attachment on open`() = runTest {
        val attachment = DraftAttachment("/cache/photo.jpg", "image/jpeg", MediaKind.IMAGE, "photo.jpg")
        draftStore.draftsFlow.value = mapOf(roomId.value to Draft(attachment = attachment))
        subject().state.test {
            val pending = expectMostRecentItem().pendingAttachment
            assertEquals("/cache/photo.jpg", pending?.path)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `typing persists a draft, clearing the box clears it`() = runTest {
        val vm = subject()
        vm.onComposeTextChanged("hello")
        testScheduler.advanceUntilIdle()
        assertEquals(Draft(text = "hello"), draftStore.getDraft(roomId))

        vm.onComposeTextChanged("")
        testScheduler.advanceUntilIdle()
        assertNull(draftStore.getDraft(roomId))
    }

    @Test
    fun `staging an attachment persists it as a draft`() = runTest {
        val vm = subject()
        val attachment = PendingAttachment("/cache/photo.jpg", "image/jpeg", MediaKind.IMAGE, "photo.jpg")
        vm.onAction(TimelineAction.StageAttachment(attachment))
        testScheduler.advanceUntilIdle()
        val draft = draftStore.getDraft(roomId)
        assertEquals("/cache/photo.jpg", draft?.attachment?.path)
    }

    @Test
    fun `ClearPendingAttachment clears the attachment from the persisted draft too`() = runTest {
        val vm = subject()
        val attachment = PendingAttachment("/cache/photo.jpg", "image/jpeg", MediaKind.IMAGE, "photo.jpg")
        vm.onAction(TimelineAction.StageAttachment(attachment))
        vm.onAction(TimelineAction.ClearPendingAttachment)
        testScheduler.advanceUntilIdle()
        assertNull(draftStore.getDraft(roomId)) // no text either, so the whole draft is gone
    }

    @Test
    fun `sending a plain message clears the persisted draft`() = runTest {
        val vm = subject()
        vm.onComposeTextChanged("hello")
        vm.onAction(TimelineAction.Send("hello"))
        testScheduler.advanceUntilIdle()
        assertNull(draftStore.getDraft(roomId))
    }

    @Test
    fun `sending a staged attachment clears the persisted draft`() = runTest {
        val vm = subject()
        val attachment = PendingAttachment("/cache/photo.jpg", "image/jpeg", MediaKind.IMAGE, "photo.jpg")
        vm.onAction(TimelineAction.StageAttachment(attachment))
        vm.onAction(TimelineAction.Send("caption"))
        testScheduler.advanceUntilIdle()
        assertNull(draftStore.getDraft(roomId))
    }
}

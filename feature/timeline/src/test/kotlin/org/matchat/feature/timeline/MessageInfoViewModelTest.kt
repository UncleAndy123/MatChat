package org.matchat.feature.timeline

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
import org.matchat.core.model.EventId
import org.matchat.core.model.Profile
import org.matchat.core.model.ReactionSummary
import org.matchat.core.model.RoomId
import org.matchat.core.model.SendState
import org.matchat.core.model.TimelineItem
import org.matchat.core.model.UserId
import org.matchat.core.policy.Policy
import org.matchat.core.testing.FakeContactsRepository
import org.matchat.core.testing.FakeMatrixSession
import org.matchat.core.testing.FakePolicyProvider

class MessageInfoViewModelTest {

    private val session = FakeMatrixSession()
    private val roomId = RoomId("!room:server")
    private val eventId = EventId("a")

    private val wayne = UserId("@wayne:s")

    private fun subject(
        contacts: FakeContactsRepository = FakeContactsRepository(),
        policy: Policy = Policy.UNMANAGED,
        sender: UserId = wayne,
    ) = MessageInfoViewModel(
        session,
        contacts,
        FakePolicyProvider(policy),
        SavedStateHandle(
            mapOf(
                "roomId" to roomId.value,
                "eventId" to eventId.value,
                "senderId" to sender.value,
                "timestamp" to 0L,
            ),
        ),
    )

    @BeforeEach fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

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
        subject().state.test {
            val reactions = expectMostRecentItem().reactions
            assertEquals(1, reactions.size)
            assertEquals(listOf("Wayne", "Merv"), reactions.single().senderNames)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an event not in the loaded window has empty reactions, not a crash`() = runTest {
        subject().state.test {
            assertTrue(expectMostRecentItem().reactions.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the sender's name and address come from their profile`() = runTest {
        session.profileResult = { Result.success(Profile(it, "Wayne")) }
        subject().state.test {
            val state = expectMostRecentItem()
            assertEquals("Wayne", state.senderName)
            assertEquals("@wayne:s", state.senderAddress)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Send message opens the direct chat`() = runTest {
        session.startDirectChatResult = { Result.success(RoomId("!dm:s")) }
        val vm = subject()
        vm.state.test {
            assertTrue(expectMostRecentItem().canMessage)
            cancelAndIgnoreRemainingEvents()
        }
        vm.navEvents.test {
            vm.sendMessage()
            assertEquals(MessageInfoNav.OpenRoom(RoomId("!dm:s")), awaitItem())
        }
    }

    @Test
    fun `save and remove toggle the contact`() = runTest {
        val contacts = FakeContactsRepository()
        val vm = subject(contacts)
        vm.state.test {
            assertFalse(expectMostRecentItem().isSaved)
            vm.saveContact()
            assertTrue(awaitItem().isSaved)
            vm.removeContact()
            assertFalse(awaitItem().isSaved)
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(contacts.contacts.first().isEmpty())
    }

    @Test
    fun `add to a phone contact picks or edits depending on the permission`() = runTest {
        val allowed = subject(FakeContactsRepository(phoneAllowed = true, mergeResult = "Dad"))
        allowed.navEvents.test {
            allowed.addToPhoneContact()
            assertEquals(MessageInfoNav.PickPhoneContact, awaitItem())
            allowed.onPhoneContactPicked("content://com.android.contacts/contacts/1")
            assertEquals(MessageInfoNav.Toast(ToastKey.PHONE_MERGED), awaitItem())
        }
        val notAllowed = subject(FakeContactsRepository(phoneAllowed = false))
        notAllowed.navEvents.test {
            notAllowed.addToPhoneContact()
            assertEquals(MessageInfoNav.EditPhoneContact("@wayne:s"), awaitItem())
        }
    }

    @Test
    fun `your own message offers no contact actions`() = runTest {
        session.ownUser = wayne
        subject().state.test {
            val state = expectMostRecentItem()
            assertFalse(state.showContactActions)
            assertFalse(state.canMessage)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `with direct chat turned off there is no Send message`() = runTest {
        subject(policy = Policy.UNMANAGED.copy(allowDirectChat = false)).state.test {
            val state = expectMostRecentItem()
            assertTrue(state.showContactActions)
            assertFalse(state.canMessage)
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

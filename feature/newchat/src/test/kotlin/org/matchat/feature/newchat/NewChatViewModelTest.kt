package org.matchat.feature.newchat

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
import org.matchat.core.model.Contact
import org.matchat.core.model.RoomId
import org.matchat.core.model.UserId
import org.matchat.core.policy.Policy
import org.matchat.core.testing.FakeContactsRepository
import org.matchat.core.testing.FakeMatrixSession
import org.matchat.core.testing.FakePolicyProvider

/** S20 Contacts: a person's menu (Send message, save, remove, phone contact). */
class NewChatViewModelTest {

    private val wayne = UserId("@wayne:example.org")
    private val boss = UserId("@boss:example.org")
    private val session = FakeMatrixSession()

    @BeforeEach fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterEach fun tearDown() = Dispatchers.resetMain()

    private fun subject(contacts: FakeContactsRepository, policy: Policy = Policy.UNMANAGED) =
        NewChatViewModel(session, contacts, FakePolicyProvider(policy))

    @Test
    fun `Send message opens the direct chat`() = runTest {
        session.startDirectChatResult = { Result.success(RoomId("!dm:s")) }
        val vm = subject(FakeContactsRepository())
        vm.state.first { !it.isLoading } // the policy check needs a live state
        vm.navEvents.test {
            vm.onAction(NewChatAction.Message(wayne))
            assertEquals(NewChatNav.OpenRoom(RoomId("!dm:s")), awaitItem())
        }
    }

    @Test
    fun `a chat that can't be started says so`() = runTest {
        session.startDirectChatResult = { Result.failure(IllegalStateException()) }
        val vm = subject(FakeContactsRepository())
        vm.state.first { !it.isLoading }
        vm.navEvents.test {
            vm.onAction(NewChatAction.Message(wayne))
            assertEquals(NewChatNav.Message(NewChatMessage.MESSAGE_FAILED), awaitItem())
        }
    }

    @Test
    fun `with direct chat turned off there is no messaging`() = runTest {
        val vm = subject(FakeContactsRepository(), Policy.UNMANAGED.copy(allowDirectChat = false))
        assertFalse(vm.state.first { !it.isLoading }.canMessage)
    }

    @Test
    fun `saved contacts can be removed, admin ones can't, recents aren't saved`() = runTest {
        val contacts = FakeContactsRepository(
            initial = listOf(
                Contact(boss, "Boss", Contact.Source.ADMIN),
                Contact(wayne, "Wayne", Contact.Source.LOCAL),
            ),
        )
        contacts.recordRecent(UserId("@new:s"))
        val state = subject(contacts).state.first { !it.isLoading }
        assertFalse(state.contacts.single { it.address == boss }.isRemovable)
        assertTrue(state.contacts.single { it.address == wayne }.isRemovable)
        assertFalse(state.recents.single().isSaved)
    }

    @Test
    fun `saving a recent makes it a contact`() = runTest {
        val contacts = FakeContactsRepository()
        val vm = subject(contacts)
        vm.navEvents.test {
            vm.onAction(NewChatAction.SaveContact(wayne))
            assertEquals(NewChatNav.Message(NewChatMessage.CONTACT_SAVED), awaitItem())
        }
        assertEquals(listOf(wayne), contacts.contacts.first().map { it.address })
    }

    @Test
    fun `removing a contact takes it off the list`() = runTest {
        val contacts = FakeContactsRepository(initial = listOf(Contact(wayne, "Wayne", Contact.Source.LOCAL)))
        val vm = subject(contacts)
        vm.navEvents.test {
            vm.onAction(NewChatAction.RemoveContact(wayne))
            assertEquals(NewChatNav.Message(NewChatMessage.CONTACT_REMOVED), awaitItem())
        }
        assertTrue(contacts.contacts.first().isEmpty())
    }

    @Test
    fun `add to a phone contact picks or edits depending on the permission`() = runTest {
        val allowed = subject(FakeContactsRepository(phoneAllowed = true, mergeResult = "Dad"))
        allowed.navEvents.test {
            allowed.onAction(NewChatAction.AddToPhoneContact(wayne))
            assertEquals(NewChatNav.PickPhoneContact, awaitItem())
            allowed.onAction(NewChatAction.PhoneContactPicked("content://com.android.contacts/contacts/1"))
            assertEquals(NewChatNav.Message(NewChatMessage.PHONE_MERGED), awaitItem())
        }

        val notAllowed = subject(FakeContactsRepository(phoneAllowed = false))
        notAllowed.navEvents.test {
            notAllowed.onAction(NewChatAction.AddToPhoneContact(wayne))
            assertEquals(NewChatNav.EditPhoneContact(wayne.value), awaitItem())
        }
    }

    @Test
    fun `no Contacts app says so`() = runTest {
        val vm = subject(FakeContactsRepository(phoneAllowed = false))
        vm.navEvents.test {
            vm.onAction(NewChatAction.PhoneContactsUnavailable)
            assertEquals(NewChatNav.Message(NewChatMessage.PHONE_CONTACTS_UNAVAILABLE), awaitItem())
        }
    }
}

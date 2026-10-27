package org.matchat.feature.timeline

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
import org.matchat.core.model.Membership
import org.matchat.core.model.RoomId
import org.matchat.core.model.RoomMemberSummary
import org.matchat.core.model.UserId
import org.matchat.core.testing.FakeBundledSoundInstaller
import org.matchat.core.testing.FakeContactsRepository
import org.matchat.core.testing.FakeMatrixSession
import org.matchat.core.testing.FakeRoomNotificationSounds

/** S12 member menu: saving people as contacts, from a group too. */
class RoomInfoContactsTest {

    private val roomId = RoomId("!group:server")
    private val wayne = UserId("@wayne:example.org")
    private val session = FakeMatrixSession().apply {
        members = listOf(RoomMemberSummary(wayne, "Wayne", Membership.JOINED, isSelf = false))
    }

    private fun subject(contacts: FakeContactsRepository) = RoomInfoViewModel(
        session,
        FakeRoomNotificationSounds(),
        FakeBundledSoundInstaller(),
        contacts,
        SavedStateHandle(mapOf("roomId" to roomId.value)),
    )

    @BeforeEach fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterEach fun tearDown() = Dispatchers.resetMain()

    private fun RoomInfoState.wayneRow() = rows.filterIsInstance<RoomInfoRow.Member>().single()

    @Test
    fun `saving a group member makes them a contact and marks the row`() = runTest {
        val contacts = FakeContactsRepository()
        val vm = subject(contacts)
        advanceUntilIdle()
        assertFalse(vm.state.value.wayneRow().isSaved)

        vm.saveContact(vm.state.value.wayneRow())
        advanceUntilIdle()
        assertEquals(listOf(Contact(wayne, "Wayne", Contact.Source.LOCAL)), contacts.contacts.first())
        assertTrue(vm.state.value.wayneRow().isSaved)
    }

    @Test
    fun `removing a contact unmarks the row`() = runTest {
        val contacts = FakeContactsRepository(initial = listOf(Contact(wayne, "Wayne", Contact.Source.LOCAL)))
        val vm = subject(contacts)
        advanceUntilIdle()
        vm.removeContact(wayne)
        advanceUntilIdle()
        assertFalse(vm.state.value.wayneRow().isSaved)
    }

    @Test
    fun `with the contacts permission it opens the phone's picker`() = runTest {
        val vm = subject(FakeContactsRepository(phoneAllowed = true))
        vm.navEvents.test {
            vm.addToPhoneContact(wayne)
            assertEquals(RoomInfoNav.PickPhoneContact, awaitItem())
        }
    }

    @Test
    fun `without it it opens the phone's add-to-contact screen`() = runTest {
        val vm = subject(FakeContactsRepository(phoneAllowed = false))
        vm.navEvents.test {
            vm.addToPhoneContact(wayne)
            assertEquals(RoomInfoNav.EditPhoneContact(wayne.value), awaitItem())
        }
    }

    @Test
    fun `a picked phone contact's name becomes the saved name`() = runTest {
        val contacts = FakeContactsRepository(phoneAllowed = true, mergeResult = "Uncle Wayne")
        val vm = subject(contacts)
        vm.navEvents.test {
            vm.addToPhoneContact(wayne)
            awaitItem()
            vm.onPhoneContactPicked("content://com.android.contacts/contacts/1")
            assertEquals(RoomInfoNav.Toast(ToastKey.PHONE_MERGED), awaitItem())
        }
        assertEquals("Uncle Wayne", contacts.contacts.first().single().name)
    }

    @Test
    fun `backing out of the picker changes nothing`() = runTest {
        val contacts = FakeContactsRepository(phoneAllowed = true, mergeResult = "Uncle Wayne")
        val vm = subject(contacts)
        vm.addToPhoneContact(wayne)
        vm.onPhoneContactPicked(null)
        advanceUntilIdle()
        assertTrue(contacts.contacts.first().isEmpty())
    }
}

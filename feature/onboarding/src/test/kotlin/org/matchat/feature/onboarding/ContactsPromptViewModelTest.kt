package org.matchat.feature.onboarding

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
import org.matchat.core.testing.FakeContactsRepository

/** S30 "Save contacts to this phone?" (UX-SPEC §S30). */
class ContactsPromptViewModelTest {

    @BeforeEach fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterEach fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `showing it once marks it offered`() {
        val contacts = FakeContactsRepository()
        ContactsPromptViewModel(contacts)
        assertTrue(contacts.isPhonePromptOffered())
    }

    @Test
    fun `without the permission it asks Android, and allowing turns it on`() = runTest {
        val contacts = FakeContactsRepository(phoneAllowed = false)
        val vm = ContactsPromptViewModel(contacts)
        vm.navEvents.test {
            vm.onAction(ContactsPromptAction.SaveToPhone)
            assertEquals(ContactsPromptNav.RequestPermission, awaitItem())
            vm.onAction(ContactsPromptAction.PermissionResult(granted = true))
            assertEquals(ContactsPromptNav.Done, awaitItem())
        }
        assertTrue(contacts.phoneSyncEnabled.first())
    }

    @Test
    fun `already allowed turns on at once`() = runTest {
        val contacts = FakeContactsRepository(phoneAllowed = true)
        val vm = ContactsPromptViewModel(contacts)
        vm.navEvents.test {
            vm.onAction(ContactsPromptAction.SaveToPhone)
            assertEquals(ContactsPromptNav.Done, awaitItem())
        }
        assertTrue(contacts.phoneSyncEnabled.first())
    }

    @Test
    fun `refusing says contacts stay in MatChat and leaves it off`() = runTest {
        val contacts = FakeContactsRepository(phoneAllowed = false)
        val vm = ContactsPromptViewModel(contacts)
        vm.onAction(ContactsPromptAction.PermissionResult(granted = false))
        advanceUntilIdle()
        assertTrue(vm.state.value.refused)
        assertFalse(contacts.phoneSyncEnabled.first())
    }
}

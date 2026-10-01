package org.matchat.feature.settings

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.matchat.core.testing.FakeBundledSoundInstaller
import org.matchat.core.testing.FakeUserPreferences
import org.matchat.core.ui.prefs.SILENT_NOTIFICATION_SOUND

class NotificationsViewModelTest {

    private val prefs = FakeUserPreferences()

    private val bundledSounds = FakeBundledSoundInstaller()

    private fun subject() = NotificationsViewModel(prefs, bundledSounds)

    @BeforeEach fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterEach fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `default state is enabled with the default sound`() = runTest {
        subject().state.test {
            val state = expectMostRecentItem()
            assertEquals(true, state.enabled)
            assertEquals(SoundChoice.Default, state.sound)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `toggling off updates the fake and the state`() = runTest {
        val vm = subject()
        vm.onAction(NotificationsAction.ToggleEnabled)
        vm.state.test {
            assertEquals(false, expectMostRecentItem().enabled)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(false, prefs.notificationsEnabled.value)
    }

    @Test
    fun `selecting silent updates state and bumps the channel version`() = runTest {
        val vm = subject()
        vm.onAction(NotificationsAction.SelectSound(SILENT_NOTIFICATION_SOUND))
        vm.state.test {
            assertEquals(SoundChoice.Silent, expectMostRecentItem().sound)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(SILENT_NOTIFICATION_SOUND, prefs.notificationSoundUri.value)
        assertEquals(1, prefs.notificationChannelVersion.value)
    }

    @Test
    fun `selecting a custom uri is reflected in state`() = runTest {
        val vm = subject()
        vm.onAction(NotificationsAction.SelectSound("content://media/custom"))
        vm.state.test {
            assertEquals(SoundChoice.Custom("content://media/custom"), expectMostRecentItem().sound)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ringtonePickResultToUri maps a null pick to silent`() {
        val vm = subject()
        assertEquals(SILENT_NOTIFICATION_SOUND, vm.ringtonePickResultToUri(null, wasCancelled = false))
    }

    @Test
    fun `ringtonePickResultToUri keeps the current value when cancelled`() = runTest {
        val vm = subject()
        vm.onAction(NotificationsAction.SelectSound(SILENT_NOTIFICATION_SOUND))
        assertEquals(
            SILENT_NOTIFICATION_SOUND,
            vm.ringtonePickResultToUri(pickedUri = null, wasCancelled = true),
        )
    }

    @Test
    fun `opening the picker copies bundled sounds first`() = runTest {
        val vm = subject()
        vm.navEvents.test {
            vm.onAction(NotificationsAction.OpenSoundPicker)
            assertEquals(NotificationsNav.OpenPicker, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(true, bundledSounds.installed)
    }

    @Test
    fun `without storage access it asks first`() = runTest {
        bundledSounds.needsStoragePermission = true
        val vm = subject()
        vm.navEvents.test {
            vm.onAction(NotificationsAction.OpenSoundPicker)
            assertEquals(NotificationsNav.RequestStoragePermission, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `refused storage access still opens the picker and shows the note`() = runTest {
        bundledSounds.needsStoragePermission = true
        val vm = subject()
        vm.navEvents.test {
            vm.onAction(NotificationsAction.StoragePermissionResult(granted = false))
            assertEquals(NotificationsNav.OpenPicker, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        vm.state.test {
            assertEquals(true, expectMostRecentItem().bundledSoundsNeedAccess)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `granted storage access hides the note`() = runTest {
        val vm = subject()
        vm.onAction(NotificationsAction.StoragePermissionResult(granted = true))
        vm.state.test {
            assertEquals(false, expectMostRecentItem().bundledSoundsNeedAccess)
            cancelAndIgnoreRemainingEvents()
        }
    }
}

package org.matchat.feature.timeline

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.matchat.core.model.RoomId
import org.matchat.core.model.notify.RoomSound
import org.matchat.core.model.notify.RoomSoundChoice
import org.matchat.core.model.notify.SILENT_SOUND
import org.matchat.core.testing.FakeBundledSoundInstaller
import org.matchat.core.testing.FakeMatrixSession
import org.matchat.core.testing.FakeRoomNotificationSounds

/** S12's Notification sound row (per-room sound). */
class RoomInfoSoundTest {

    private val roomId = RoomId("!room:server")
    private val systemDefault = "content://settings/system/notification_sound"
    private val session = FakeMatrixSession()
    private val sounds = FakeRoomNotificationSounds()
    private val bundled = FakeBundledSoundInstaller()

    private fun subject() =
        RoomInfoViewModel(session, sounds, bundled, SavedStateHandle(mapOf("roomId" to roomId.value)))

    @BeforeEach fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterEach fun tearDown() = Dispatchers.resetMain()

    private fun RoomInfoState.soundChoice() = rows.filterIsInstance<RoomInfoRow.Sound>().single().choice

    @Test
    fun `no override shows the app sound`() = runTest {
        val vm = subject()
        advanceUntilIdle()
        assertEquals(RoomSoundChoice.AppDefault, vm.state.value.soundChoice())
    }

    @Test
    fun `an existing override is shown`() = runTest {
        sounds.overrides.value = mapOf(roomId to RoomSound(SILENT_SOUND, 1))
        val vm = subject()
        advanceUntilIdle()
        assertEquals(RoomSoundChoice.Silent, vm.state.value.soundChoice())
    }

    @Test
    fun `picking a sound sets this room's override`() = runTest {
        val vm = subject()
        vm.onSoundPicked("content://media/7", cancelled = false, systemDefaultUri = systemDefault)
        advanceUntilIdle()
        assertEquals(RoomSoundChoice.Custom("content://media/7"), vm.state.value.soundChoice())
    }

    @Test
    fun `picking Default goes back to the app sound`() = runTest {
        sounds.overrides.value = mapOf(roomId to RoomSound("content://media/7", 1))
        val vm = subject()
        vm.onSoundPicked(systemDefault, cancelled = false, systemDefaultUri = systemDefault)
        advanceUntilIdle()
        assertEquals(RoomSoundChoice.AppDefault, vm.state.value.soundChoice())
    }

    @Test
    fun `cancelling the picker changes nothing`() = runTest {
        sounds.overrides.value = mapOf(roomId to RoomSound("content://media/7", 1))
        val vm = subject()
        vm.onSoundPicked(null, cancelled = true, systemDefaultUri = systemDefault)
        advanceUntilIdle()
        assertEquals(RoomSoundChoice.Custom("content://media/7"), vm.state.value.soundChoice())
    }

    @Test
    fun `opening the picker copies bundled sounds and preselects the current sound`() = runTest {
        sounds.overrides.value = mapOf(roomId to RoomSound("content://media/7", 1))
        val vm = subject()
        vm.navEvents.test {
            vm.openSoundPicker()
            assertEquals(RoomInfoNav.OpenSoundPicker("content://media/7"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(true, bundled.installed)
    }

    @Test
    fun `without storage access it asks first`() = runTest {
        bundled.needsStoragePermission = true
        val vm = subject()
        vm.navEvents.test {
            vm.openSoundPicker()
            assertEquals(RoomInfoNav.RequestStoragePermission, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `refused storage access says so and still opens the picker`() = runTest {
        val vm = subject()
        vm.navEvents.test {
            vm.onStoragePermissionResult(granted = false)
            assertEquals(RoomInfoNav.Toast(ToastKey.SOUNDS_NEED_ACCESS), awaitItem())
            assertEquals(RoomInfoNav.OpenSoundPicker(null), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}

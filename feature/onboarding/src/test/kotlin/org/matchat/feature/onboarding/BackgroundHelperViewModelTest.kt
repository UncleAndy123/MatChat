package org.matchat.feature.onboarding

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
import org.matchat.core.testing.FakeBackgroundSync
import org.matchat.core.model.background.BackgroundSyncStatus
import org.matchat.core.model.background.HelperStatus

class BackgroundHelperViewModelTest {

    private val backgroundSync = FakeBackgroundSync()

    private fun subject() = BackgroundHelperViewModel(backgroundSync)

    @BeforeEach fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterEach fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `helper off shows off`() = runTest {
        subject().state.test {
            assertEquals(HelperStatus.OFF, expectMostRecentItem().status)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `helper on without the battery exemption says it is needed`() = runTest {
        backgroundSync.status.value = BackgroundSyncStatus(batteryExempt = false, helperConnected = true)
        subject().state.test {
            assertEquals(HelperStatus.NEEDS_BATTERY, expectMostRecentItem().status)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `helper on with the exemption shows on`() = runTest {
        backgroundSync.status.value = BackgroundSyncStatus(batteryExempt = true, helperConnected = true)
        subject().state.test {
            assertEquals(HelperStatus.ON, expectMostRecentItem().status)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the state follows the helper being turned on`() = runTest {
        backgroundSync.status.value = BackgroundSyncStatus(batteryExempt = true)
        val vm = subject()
        vm.state.test {
            assertEquals(HelperStatus.OFF, expectMostRecentItem().status)
            backgroundSync.status.value = BackgroundSyncStatus(batteryExempt = true, helperConnected = true)
            assertEquals(HelperStatus.ON, awaitItem().status)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `open settings emits the accessibility settings navigation`() = runTest {
        val vm = subject()
        vm.navEvents.test {
            vm.onAction(BackgroundHelperAction.OpenSettings)
            assertEquals(BackgroundHelperNav.AccessibilitySettings, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `refresh picks up an exemption granted in system settings`() = runTest {
        backgroundSync.status.value = BackgroundSyncStatus(batteryExempt = false, helperConnected = true)
        val vm = subject()
        vm.state.test {
            assertEquals(HelperStatus.NEEDS_BATTERY, expectMostRecentItem().status)
            backgroundSync.systemBatteryExempt = true
            vm.onAction(BackgroundHelperAction.Refresh)
            assertEquals(HelperStatus.ON, awaitItem().status)
            cancelAndIgnoreRemainingEvents()
        }
    }
}

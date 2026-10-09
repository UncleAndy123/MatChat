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
import org.matchat.core.testing.FakeBackgroundSync
import org.matchat.core.testing.FakeUserPreferences
import org.matchat.core.model.background.BackgroundSyncStatus
import org.matchat.core.model.background.HelperStatus

class AdvancedViewModelTest {

    private val prefs = FakeUserPreferences()
    private val backgroundSync = FakeBackgroundSync()

    private fun subject() = AdvancedViewModel(prefs, backgroundSync)

    @BeforeEach fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterEach fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `default state is not swapped`() = runTest {
        subject().state.test {
            assertEquals(false, expectMostRecentItem().softkeysSwapped)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `toggling on updates the fake and the state`() = runTest {
        val vm = subject()
        vm.onAction(AdvancedAction.ToggleSoftkeysSwapped)
        vm.state.test {
            assertEquals(true, expectMostRecentItem().softkeysSwapped)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(true, prefs.softkeysSwapped.value)
    }

    @Test
    fun `toggling twice returns to not swapped`() = runTest {
        val vm = subject()
        vm.onAction(AdvancedAction.ToggleSoftkeysSwapped)
        vm.onAction(AdvancedAction.ToggleSoftkeysSwapped)
        vm.state.test {
            assertEquals(false, expectMostRecentItem().softkeysSwapped)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `default background state is not allowed and helper off`() = runTest {
        subject().state.test {
            val state = expectMostRecentItem()
            assertEquals(false, state.batteryExempt)
            assertEquals(HelperStatus.OFF, state.helperStatus)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `helper on without exemption says run in background is needed`() = runTest {
        backgroundSync.status.value = BackgroundSyncStatus(batteryExempt = false, helperConnected = true)
        subject().state.test {
            assertEquals(HelperStatus.NEEDS_BATTERY, expectMostRecentItem().helperStatus)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `helper and exemption show no notification needed`() = runTest {
        backgroundSync.status.value = BackgroundSyncStatus(batteryExempt = true, helperConnected = true)
        subject().state.test {
            val state = expectMostRecentItem()
            assertEquals(true, state.batteryExempt)
            assertEquals(HelperStatus.ON, state.helperStatus)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `refresh picks up an exemption granted in system settings`() = runTest {
        val vm = subject()
        vm.state.test {
            assertEquals(false, expectMostRecentItem().batteryExempt)
            backgroundSync.systemBatteryExempt = true
            vm.onAction(AdvancedAction.Refresh)
            assertEquals(true, awaitItem().batteryExempt)
            cancelAndIgnoreRemainingEvents()
        }
    }
}

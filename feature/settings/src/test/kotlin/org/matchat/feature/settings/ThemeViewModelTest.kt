package org.matchat.feature.settings

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.matchat.core.testing.FakeUserPreferences
import org.matchat.core.ui.prefs.AccentColor
import org.matchat.core.ui.prefs.ThemeMode

class ThemeViewModelTest {

    private val prefs = FakeUserPreferences()

    private fun subject() = ThemeViewModel(prefs)

    @BeforeEach fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterEach fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `default state is light and green`() = runTest {
        subject().state.test {
            val state = expectMostRecentItem()
            assertEquals(ThemeMode.LIGHT, state.mode)
            assertEquals(AccentColor.GREEN, state.accent)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `selecting dark updates the fake and the state`() = runTest {
        val vm = subject()
        vm.onAction(ThemeAction.SelectDark)
        vm.state.test {
            assertEquals(ThemeMode.DARK, expectMostRecentItem().mode)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(ThemeMode.DARK, prefs.themeMode.value)
    }

    @Test
    fun `selecting an accent updates the fake and the state, leaving mode alone`() = runTest {
        val vm = subject()
        vm.onAction(ThemeAction.SelectAccent(AccentColor.BLUE))
        vm.state.test {
            val state = expectMostRecentItem()
            assertEquals(AccentColor.BLUE, state.accent)
            assertEquals(ThemeMode.LIGHT, state.mode)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(AccentColor.BLUE, prefs.accentColor.value)
    }
}

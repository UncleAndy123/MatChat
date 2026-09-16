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
import org.matchat.core.testing.FakeUserPreferences
import org.matchat.core.ui.prefs.TextSizePreference

class TextSizeViewModelTest {

    private val prefs = FakeUserPreferences()

    private fun subject() = TextSizeViewModel(prefs)

    @BeforeEach fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterEach fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `default state is Normal`() = runTest {
        subject().state.test {
            assertEquals(TextSizePreference.NORMAL, expectMostRecentItem().size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `selecting Small updates the fake and the state`() = runTest {
        val vm = subject()
        vm.onAction(TextSizeAction.SelectSmall)
        vm.state.test {
            assertEquals(TextSizePreference.SMALL, expectMostRecentItem().size)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(TextSizePreference.SMALL, prefs.textSize.value)
    }

    @Test
    fun `selecting Normal after Small returns to Normal`() = runTest {
        val vm = subject()
        vm.onAction(TextSizeAction.SelectSmall)
        vm.onAction(TextSizeAction.SelectNormal)
        vm.state.test {
            assertEquals(TextSizePreference.NORMAL, expectMostRecentItem().size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `selecting Large updates the fake and the state`() = runTest {
        val vm = subject()
        vm.onAction(TextSizeAction.SelectLarge)
        vm.state.test {
            assertEquals(TextSizePreference.LARGE, expectMostRecentItem().size)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(TextSizePreference.LARGE, prefs.textSize.value)
    }
}

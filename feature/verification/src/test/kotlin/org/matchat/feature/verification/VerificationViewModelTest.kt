package org.matchat.feature.verification

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.matchat.core.testing.FakeMatrixSession

class VerificationViewModelTest {

    private val session = FakeMatrixSession()

    @BeforeEach fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @AfterEach fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `empty recovery key is rejected without calling the session`() {
        val vm = VerificationViewModel(session)
        vm.onAction(VerificationAction.Submit("   "))
        assertNotNull(vm.state.value.error)
        assertEquals(null, session.lastRecoveryKey)
    }

    @Test
    fun `a valid recovery key verifies and navigates`() = runTest {
        session.recoverResult = Result.success(Unit)
        val vm = VerificationViewModel(session)
        vm.navEvents.test {
            vm.onAction(VerificationAction.Submit("EsTa key"))
            assertEquals(VerificationNav.Verified, awaitItem())
            assertEquals("EsTa key", session.lastRecoveryKey)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a bad recovery key surfaces an error`() = runTest {
        session.recoverResult = Result.failure(IllegalStateException("nope"))
        val vm = VerificationViewModel(session)
        vm.onAction(VerificationAction.Submit("bad"))
        testScheduler.advanceUntilIdle()
        assertNotNull(vm.state.value.error)
    }
}

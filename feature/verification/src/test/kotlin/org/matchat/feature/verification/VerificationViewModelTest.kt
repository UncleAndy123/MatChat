package org.matchat.feature.verification

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.matchat.core.model.SasEmoji
import org.matchat.core.model.SasState
import org.matchat.core.testing.FakeMatrixSession
import org.matchat.core.testing.FakeSessionVerification

class VerificationViewModelTest {

    private val sas = FakeSessionVerification()
    private val session = FakeMatrixSession()

    @BeforeEach fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @AfterEach fun tearDown() = Dispatchers.resetMain()

    private fun subject() = VerificationViewModel(sas, session)

    @Test
    fun `starting SAS moves to waiting, then comparing shows emojis`() = runTest {
        val vm = subject()
        vm.onAction(VerificationAction.StartSas)
        testScheduler.advanceUntilIdle()
        assertTrue(sas.started)
        assertEquals(Phase.WAITING_FOR_DEVICE, vm.state.value.phase)

        sas.emit(SasState.Comparing(listOf(SasEmoji("🐶", "Dog"), SasEmoji("🐱", "Cat"))))
        testScheduler.advanceUntilIdle()
        assertEquals(Phase.COMPARING, vm.state.value.phase)
        assertEquals(2, vm.state.value.emojis.size)
    }

    @Test
    fun `approving and finishing emits Verified`() = runTest {
        val vm = subject()
        vm.navEvents.test {
            vm.onAction(VerificationAction.ApproveSas)
            sas.emit(SasState.Success)
            testScheduler.advanceUntilIdle()
            assertEquals(VerificationNav.Verified, awaitItem())
            assertTrue(sas.approved)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `recovery key path verifies via the session`() = runTest {
        session.recoverResult = Result.success(Unit)
        val vm = subject()
        vm.onAction(VerificationAction.ChooseRecovery)
        assertEquals(Phase.RECOVERY_KEY, vm.state.value.phase)
        vm.navEvents.test {
            vm.onAction(VerificationAction.SubmitRecovery("my key"))
            testScheduler.advanceUntilIdle()
            assertEquals(VerificationNav.Verified, awaitItem())
            assertEquals("my key", session.lastRecoveryKey)
            cancelAndIgnoreRemainingEvents()
        }
    }
}

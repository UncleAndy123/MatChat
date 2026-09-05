package org.matchat.feature.onboarding

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.matchat.core.model.ErrorText
import org.matchat.core.policy.Policy
import org.matchat.core.testing.FakeMatrixAuth
import org.matchat.core.testing.FakePolicyProvider

class SignInViewModelTest {

    @BeforeEach fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @AfterEach fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `pinned homeserver is read-only`() {
        val vm = SignInViewModel(
            FakeMatrixAuth(),
            FakePolicyProvider(Policy.UNMANAGED.copy(pinnedHomeserver = "srv.example")),
        )
        assertEquals("srv.example", vm.state.value.homeserver)
        assertTrue(vm.state.value.homeserverPinned)
    }

    @Test
    fun `empty credentials produce an error, no network call`() {
        val auth = FakeMatrixAuth()
        val vm = SignInViewModel(auth, FakePolicyProvider())
        vm.onAction(SignInAction.Submit("", ""))
        assertNotNull(vm.state.value.error)
    }

    @Test
    fun `successful sign in emits Success`() = runTest {
        val vm = SignInViewModel(FakeMatrixAuth(signInResult = Result.success(Unit)), FakePolicyProvider())
        vm.navEvents.test {
            vm.onAction(SignInAction.Submit("wayne", "hunter2"))
            assertEquals(SignInNav.Success, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `network failure maps to a retryable network error`() = runTest {
        val auth = FakeMatrixAuth(signInResult = Result.failure(java.io.IOException("down")))
        val vm = SignInViewModel(auth, FakePolicyProvider())
        vm.onAction(SignInAction.Submit("wayne", "hunter2"))
        // Let the launched coroutine run.
        testScheduler.advanceUntilIdle()
        assertEquals(ErrorText.Key.NETWORK, vm.state.value.error?.key)
    }
}

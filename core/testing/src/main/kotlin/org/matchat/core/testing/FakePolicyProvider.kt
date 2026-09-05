package org.matchat.core.testing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.matchat.core.policy.Policy
import org.matchat.core.policy.PolicyProvider

/** A [PolicyProvider] tests can retarget to simulate an EMM pushing a new bundle. */
class FakePolicyProvider(
    initial: Policy = Policy.UNMANAGED,
) : PolicyProvider {
    private val state = MutableStateFlow(initial)
    override val policy: StateFlow<Policy> = state

    /** Simulate ACTION_APPLICATION_RESTRICTIONS_CHANGED with a new policy. */
    fun push(policy: Policy) { state.value = policy }
}

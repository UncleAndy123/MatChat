package org.matchat.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.matchat.core.policy.PolicyProvider
import javax.inject.Inject

/** S23 Policy — read-only. It exists so a user who was just blocked can find out
 *  why without phoning anyone. It never offers a way around the policy. */
data class PolicyState(
    val isManaged: Boolean = false,
    val homeserver: String = "",
    /** null renders as "All servers allowed" (the unmanaged, fail-open case). */
    val allowedServers: List<String>? = null,
    val directChatAllowed: Boolean = true,
)

@HiltViewModel
class PolicyViewModel @Inject constructor(
    policyProvider: PolicyProvider,
) : ViewModel() {
    val state: StateFlow<PolicyState> =
        policyProvider.policy.map { policy ->
            PolicyState(
                isManaged = policy.isManaged,
                homeserver = policy.pinnedHomeserver.orEmpty(),
                allowedServers = policy.allowedDomains,
                directChatAllowed = policy.allowDirectChat,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PolicyState())

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

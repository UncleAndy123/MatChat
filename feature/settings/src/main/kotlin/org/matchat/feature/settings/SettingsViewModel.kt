package org.matchat.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.matchat.core.matrix.MatrixSession
import org.matchat.core.policy.PolicyProvider
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val session: MatrixSession,
    policyProvider: PolicyProvider,
) : ViewModel() {

    val state: StateFlow<SettingsState> =
        policyProvider.policy
            .map { SettingsState(isManaged = it.isManaged) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SettingsState())

    private val navChannel = Channel<SettingsNav>(Channel.BUFFERED)
    val navEvents: Flow<SettingsNav> = navChannel.receiveAsFlow()

    fun onAction(action: SettingsAction) {
        when (action) {
            SettingsAction.OpenEncryption -> emit(SettingsNav.Encryption)
            SettingsAction.OpenPolicy -> emit(SettingsNav.Policy)
            SettingsAction.OpenHelp -> emit(SettingsNav.Help)
            SettingsAction.ConfirmSignOut -> signOut()
        }
    }

    private fun signOut() {
        viewModelScope.launch {
            session.logout()
            navChannel.send(SettingsNav.SignedOut)
        }
    }

    private fun emit(nav: SettingsNav) {
        viewModelScope.launch { navChannel.send(nav) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

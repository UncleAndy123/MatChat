package org.matchat.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.matchat.core.contacts.ContactsRepository
import org.matchat.core.matrix.MatrixSession
import org.matchat.core.policy.PolicyProvider
import org.matchat.core.update.UpdateManager
import org.matchat.core.update.UpdateStatus
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val session: MatrixSession,
    policyProvider: PolicyProvider,
    updateManager: UpdateManager,
    private val contacts: ContactsRepository,
) : ViewModel() {

    // Bumped on Refresh so the phone-contacts row re-checks the permission
    // (it may have been taken away in system settings meanwhile).
    private val refreshTick = MutableStateFlow(0)
    private val phoneRefused = MutableStateFlow(false)

    val state: StateFlow<SettingsState> =
        combine(
            policyProvider.policy,
            updateManager.state,
            contacts.phoneSyncEnabled,
            phoneRefused,
            refreshTick,
        ) { policy, update, phoneOn, refused, _ ->
            SettingsState(
                isManaged = policy.isManaged,
                updateAvailable = update is UpdateStatus.Available ||
                    update is UpdateStatus.Downloaded,
                phoneContactsOn = phoneOn && contacts.canUsePhoneContacts(),
                phoneContactsRefused = refused,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SettingsState())

    private val navChannel = Channel<SettingsNav>(Channel.BUFFERED)
    val navEvents: Flow<SettingsNav> = navChannel.receiveAsFlow()

    fun onAction(action: SettingsAction) {
        when (action) {
            SettingsAction.OpenEncryption -> emit(SettingsNav.Encryption)
            SettingsAction.OpenTextSize -> emit(SettingsNav.TextSize)
            SettingsAction.OpenTheme -> emit(SettingsNav.Theme)
            SettingsAction.OpenAdvanced -> emit(SettingsNav.Advanced)
            SettingsAction.OpenNotifications -> emit(SettingsNav.Notifications)
            SettingsAction.OpenPolicy -> emit(SettingsNav.Policy)
            SettingsAction.OpenUpdate -> emit(SettingsNav.Update)
            SettingsAction.OpenHelp -> emit(SettingsNav.Help)
            SettingsAction.ConfirmSignOut -> signOut()
            SettingsAction.TogglePhoneContacts -> togglePhoneContacts()
            is SettingsAction.PhoneContactsPermissionResult -> viewModelScope.launch {
                phoneRefused.value = !contacts.onPhonePermissionResult(action.granted)
            }
            SettingsAction.Refresh -> refreshTick.value++
        }
    }

    /** Off -> on (asking Android first when needed); on -> off. Turning it off
     *  never deletes anything already on the phone. */
    private fun togglePhoneContacts() {
        viewModelScope.launch {
            if (state.value.phoneContactsOn) {
                contacts.setPhoneSync(false)
                phoneRefused.value = false
            } else if (contacts.turnOnPhoneSync()) {
                phoneRefused.value = false
            } else {
                navChannel.send(SettingsNav.RequestContactsPermission)
            }
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

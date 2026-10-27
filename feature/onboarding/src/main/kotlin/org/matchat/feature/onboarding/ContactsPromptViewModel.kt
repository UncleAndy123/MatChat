package org.matchat.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.matchat.core.contacts.ContactsRepository
import javax.inject.Inject

@HiltViewModel
class ContactsPromptViewModel @Inject constructor(
    private val contacts: ContactsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ContactsPromptState())
    val state: StateFlow<ContactsPromptState> = _state.asStateFlow()

    private val navChannel = Channel<ContactsPromptNav>(Channel.BUFFERED)
    val navEvents: Flow<ContactsPromptNav> = navChannel.receiveAsFlow()

    init {
        // Shown once per install, whatever the user answers (UX-SPEC §S30).
        contacts.markPhonePromptOffered()
    }

    fun onAction(action: ContactsPromptAction) {
        viewModelScope.launch {
            when (action) {
                ContactsPromptAction.SaveToPhone ->
                    if (contacts.turnOnPhoneSync()) {
                        navChannel.send(ContactsPromptNav.Done)
                    } else {
                        navChannel.send(ContactsPromptNav.RequestPermission)
                    }
                is ContactsPromptAction.PermissionResult ->
                    if (contacts.onPhonePermissionResult(action.granted)) {
                        navChannel.send(ContactsPromptNav.Done)
                    } else {
                        _state.value = ContactsPromptState(refused = true)
                    }
            }
        }
    }
}

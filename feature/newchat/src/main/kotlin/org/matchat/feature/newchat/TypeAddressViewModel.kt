package org.matchat.feature.newchat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.matchat.core.contacts.ContactsRepository
import org.matchat.core.matrix.MatrixSession
import org.matchat.core.model.ErrorText
import org.matchat.core.policy.PolicyProvider
import javax.inject.Inject

/**
 * S21 logic — the guarded pipeline for a direct chat by address (PLAN.md §6.9):
 * shape check → policy check → profile lookup → confirm → create. Confirmation
 * before create matters: a keypad typo must not leave an orphan room forever.
 */
@HiltViewModel
class TypeAddressViewModel @Inject constructor(
    private val session: MatrixSession,
    private val policyProvider: PolicyProvider,
    private val contacts: ContactsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(TypeAddressState())
    val state: StateFlow<TypeAddressState> = _state.asStateFlow()

    private val navChannel = Channel<TypeAddressNav>(Channel.BUFFERED)
    val navEvents: Flow<TypeAddressNav> = navChannel.receiveAsFlow()

    fun onAction(action: TypeAddressAction) {
        when (action) {
            is TypeAddressAction.Continue -> onContinue(action.raw)
            TypeAddressAction.Confirm, TypeAddressAction.StartAnyway -> createChat()
            TypeAddressAction.Change -> _state.update {
                it.copy(confirm = null, error = null, blockedDomain = null)
            }
        }
    }

    private fun onContinue(raw: String) {
        // 1. Shape check.
        val address = AddressValidator.parse(raw) ?: run {
            _state.update { it.copy(error = ErrorText(ErrorText.Key.MALFORMED_ADDRESS)) }
            return
        }
        // 2. Policy check — a blocked domain is named and goes no further (S22).
        if (!policyProvider.policy.value.allows(address)) {
            _state.update { it.copy(error = null, confirm = null, blockedDomain = address.domain) }
            return
        }
        // 3. Profile lookup (a lookup of a known address, not a search).
        _state.update { it.copy(isBusy = true, error = null, blockedDomain = null) }
        viewModelScope.launch {
            val profile = session.lookupProfile(address).getOrNull()
            _state.update {
                it.copy(
                    isBusy = false,
                    confirm = ConfirmTarget(
                        address = address,
                        name = profile?.displayName ?: address.value,
                        isEncrypted = true,
                        unresolved = profile?.displayName == null,
                    ),
                )
            }
        }
    }

    private fun createChat() {
        val target = state.value.confirm ?: return
        _state.update { it.copy(isBusy = true, error = null) }
        viewModelScope.launch {
            session.startDirectChat(target.address).fold(
                onSuccess = { roomId ->
                    contacts.recordRecent(target.address)
                    navChannel.send(TypeAddressNav.OpenRoom(roomId))
                },
                onFailure = {
                    _state.update {
                        it.copy(isBusy = false, error = ErrorText(ErrorText.Key.SERVER_UNREACHABLE, retryable = true))
                    }
                },
            )
        }
    }
}

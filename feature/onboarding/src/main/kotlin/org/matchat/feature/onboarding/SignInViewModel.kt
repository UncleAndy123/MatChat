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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.matchat.core.matrix.MatrixAuth
import org.matchat.core.model.ErrorText
import org.matchat.core.policy.PolicyProvider
import javax.inject.Inject

@HiltViewModel
class SignInViewModel @Inject constructor(
    private val auth: MatrixAuth,
    policyProvider: PolicyProvider,
) : ViewModel() {

    private val pinned = policyProvider.policy.value.pinnedHomeserver

    private val _state = MutableStateFlow(
        SignInState(
            homeserver = pinned ?: DEFAULT_HOMESERVER,
            homeserverPinned = pinned != null,
        ),
    )
    val state: StateFlow<SignInState> = _state.asStateFlow()

    private val navChannel = Channel<SignInNav>(Channel.BUFFERED)
    val navEvents: Flow<SignInNav> = navChannel.receiveAsFlow()

    fun onAction(action: SignInAction) {
        when (action) {
            is SignInAction.Submit -> submit(action.username.trim(), action.password)
            SignInAction.DismissError -> _state.update { it.copy(error = null) }
        }
    }

    private fun submit(username: String, password: String) {
        if (username.isEmpty() || password.isEmpty()) {
            _state.update { it.copy(error = ErrorText(ErrorText.Key.BAD_CREDENTIALS)) }
            return
        }
        _state.update { it.copy(isSubmitting = true, error = null) }
        viewModelScope.launch {
            val result = auth.signIn(username, password)
            result.fold(
                onSuccess = {
                    _state.update { it.copy(isSubmitting = false) }
                    navChannel.send(SignInNav.Success)
                },
                onFailure = { cause ->
                    _state.update {
                        it.copy(isSubmitting = false, error = mapError(cause))
                    }
                },
            )
        }
    }

    private fun mapError(cause: Throwable): ErrorText =
        when (cause) {
            is java.io.IOException -> ErrorText(ErrorText.Key.NETWORK, retryable = true)
            else -> ErrorText(ErrorText.Key.BAD_CREDENTIALS)
        }

    private companion object {
        // Homeserver assumed from the sign-in mockup (PLAN.md §12 open question 1).
        const val DEFAULT_HOMESERVER = "chats.carpathianserver.org"
    }
}

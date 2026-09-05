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
            is SignInAction.Submit ->
                submit(action.username.trim(), action.password, action.homeserver.trim())
            SignInAction.DismissError -> _state.update { it.copy(error = null) }
        }
    }

    private fun submit(username: String, password: String, homeserver: String) {
        val server = if (state.value.homeserverPinned) state.value.homeserver else homeserver
        if (username.isEmpty() || password.isEmpty() || server.isEmpty()) {
            _state.update { it.copy(error = ErrorText(ErrorText.Key.BAD_CREDENTIALS)) }
            return
        }
        _state.update { it.copy(isSubmitting = true, error = null) }
        viewModelScope.launch {
            val result = auth.signIn(username, password, server)
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

    private fun mapError(cause: Throwable): ErrorText {
        val message = cause.message.orEmpty()
        return when {
            cause is java.io.IOException -> ErrorText(ErrorText.Key.NETWORK, retryable = true)
            // Clear auth rejections read as a wrong username/password.
            listOf("forbidden", "unauthorized", "password", "m_forbidden", "invalid")
                .any { message.contains(it, ignoreCase = true) } ->
                ErrorText(ErrorText.Key.BAD_CREDENTIALS)
            // Anything else (bad server, TLS/cert, unreachable) is shown verbatim
            // so the real cause is visible (AGENTS.md — no silent failure states).
            else -> ErrorText(ErrorText.Key.SIGN_IN_FAILED, args = listOf(message.take(MAX_MSG)))
        }
    }

    private companion object {
        // Homeserver assumed from the sign-in mockup (PLAN.md §12 open question 1).
        const val DEFAULT_HOMESERVER = "chats.carpathianserver.org"
        const val MAX_MSG = 200
    }
}

package org.matchat.feature.verification

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
import org.matchat.core.matrix.MatrixSession
import org.matchat.core.model.ErrorText
import javax.inject.Inject

@HiltViewModel
class VerificationViewModel @Inject constructor(
    private val session: MatrixSession,
) : ViewModel() {

    private val _state = MutableStateFlow(VerificationState())
    val state: StateFlow<VerificationState> = _state.asStateFlow()

    private val navChannel = Channel<VerificationNav>(Channel.BUFFERED)
    val navEvents: Flow<VerificationNav> = navChannel.receiveAsFlow()

    fun onAction(action: VerificationAction) {
        when (action) {
            is VerificationAction.Submit -> recover(action.recoveryKey.trim())
            VerificationAction.DismissError -> _state.update { it.copy(error = null) }
        }
    }

    private fun recover(recoveryKey: String) {
        if (recoveryKey.isEmpty()) {
            _state.update { it.copy(error = ErrorText(ErrorText.Key.UNKNOWN)) }
            return
        }
        _state.update { it.copy(isSubmitting = true, error = null) }
        viewModelScope.launch {
            session.recoverEncryption(recoveryKey).fold(
                onSuccess = {
                    _state.update { it.copy(isSubmitting = false) }
                    navChannel.send(VerificationNav.Verified)
                },
                onFailure = {
                    _state.update { it.copy(isSubmitting = false, error = ErrorText(ErrorText.Key.UNKNOWN)) }
                },
            )
        }
    }
}

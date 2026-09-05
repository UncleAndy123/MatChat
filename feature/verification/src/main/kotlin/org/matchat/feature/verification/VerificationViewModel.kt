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
import org.matchat.core.matrix.SessionVerification
import org.matchat.core.model.ErrorText
import org.matchat.core.model.SasState
import javax.inject.Inject

/**
 * S5/S6/S7 logic: emoji (SAS) verification with the recovery key as the fallback.
 * SAS phase transitions are driven by [SessionVerification.state]; recovery is a
 * one-shot call. All logic here so it is unit-testable (AGENTS.md §6).
 */
@HiltViewModel
class VerificationViewModel @Inject constructor(
    private val sas: SessionVerification,
    private val session: MatrixSession,
) : ViewModel() {

    private val _state = MutableStateFlow(VerificationState())
    val state: StateFlow<VerificationState> = _state.asStateFlow()

    private val navChannel = Channel<VerificationNav>(Channel.BUFFERED)
    val navEvents: Flow<VerificationNav> = navChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            sas.state.collect { sasState -> applySasState(sasState) }
        }
    }

    fun onAction(action: VerificationAction) {
        when (action) {
            VerificationAction.StartSas -> viewModelScope.launch { runCatching { sas.start() } }
            VerificationAction.ApproveSas -> viewModelScope.launch { sas.approve() }
            VerificationAction.DeclineSas -> viewModelScope.launch { sas.decline() }
            VerificationAction.ChooseRecovery ->
                _state.update { it.copy(phase = Phase.RECOVERY_KEY, error = null) }
            is VerificationAction.SubmitRecovery -> recover(action.recoveryKey.trim())
            VerificationAction.Cancel -> cancel()
        }
    }

    private fun applySasState(sasState: SasState) {
        // Do not let SAS updates clobber the recovery-key phase the user chose.
        if (_state.value.phase == Phase.RECOVERY_KEY) return
        when (sasState) {
            SasState.Idle -> _state.update { it.copy(phase = Phase.CHOOSE, emojis = emptyList()) }
            SasState.Requested -> _state.update { it.copy(phase = Phase.WAITING_FOR_DEVICE, error = null) }
            is SasState.Comparing ->
                _state.update { it.copy(phase = Phase.COMPARING, emojis = sasState.emojis) }
            SasState.Success -> emitVerified()
            SasState.Cancelled ->
                _state.update {
                    it.copy(phase = Phase.CHOOSE, emojis = emptyList(), error = ErrorText(ErrorText.Key.UNKNOWN))
                }
        }
    }

    private fun recover(recoveryKey: String) {
        if (recoveryKey.isEmpty()) {
            _state.update { it.copy(error = ErrorText(ErrorText.Key.UNKNOWN)) }
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            session.recoverEncryption(recoveryKey).fold(
                onSuccess = {
                    _state.update { it.copy(busy = false) }
                    emitVerified()
                },
                onFailure = {
                    _state.update { it.copy(busy = false, error = ErrorText(ErrorText.Key.UNKNOWN)) }
                },
            )
        }
    }

    private fun cancel() {
        viewModelScope.launch { runCatching { sas.cancel() } }
        _state.update { it.copy(phase = Phase.CHOOSE, emojis = emptyList(), error = null) }
    }

    private fun emitVerified() {
        viewModelScope.launch { navChannel.send(VerificationNav.Verified) }
    }

    override fun onCleared() {
        sas.reset()
        super.onCleared()
    }
}

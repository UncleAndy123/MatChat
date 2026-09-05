package org.matchat.core.testing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.matchat.core.matrix.SessionVerification
import org.matchat.core.model.SasState

/** A [SessionVerification] tests drive by pushing [SasState]s. */
class FakeSessionVerification(
    initial: SasState = SasState.Idle,
) : SessionVerification {
    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<SasState> = _state

    var started = false
    var approved = false
    var declined = false

    override suspend fun start() {
        started = true
        _state.value = SasState.Requested
    }

    override suspend fun approve() { approved = true }

    override suspend fun decline() {
        declined = true
        _state.value = SasState.Cancelled
    }

    override suspend fun cancel() { _state.value = SasState.Idle }

    override fun reset() { _state.value = SasState.Idle }

    /** Push a state as if the SDK delegate reported it. */
    fun emit(state: SasState) { _state.value = state }
}

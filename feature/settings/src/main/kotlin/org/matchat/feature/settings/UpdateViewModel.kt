package org.matchat.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.matchat.core.update.UpdateManager
import org.matchat.core.update.UpdateStatus
import javax.inject.Inject

@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val updateManager: UpdateManager,
) : ViewModel() {

    val state: StateFlow<UpdateState> =
        updateManager.state
            .map(::toState)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                toState(updateManager.state.value),
            )

    init {
        // Opening the screen always re-checks (unless a download is mid-flight):
        // the launch auto-check is throttled, but a user who came here wants the
        // freshest answer, so force it.
        viewModelScope.launch { updateManager.checkForUpdate(force = true) }
    }

    fun onAction(action: UpdateAction) {
        viewModelScope.launch {
            when (action) {
                UpdateAction.Check -> updateManager.checkForUpdate(force = true)
                UpdateAction.Download -> updateManager.download()
                UpdateAction.Install -> updateManager.installDownloaded()
            }
        }
    }

    private fun toState(status: UpdateStatus): UpdateState = when (status) {
        UpdateStatus.Idle, UpdateStatus.Checking ->
            UpdateState(phase = UpdatePhase.CHECKING, currentVersion = updateManager.currentVersion())
        UpdateStatus.UpToDate ->
            UpdateState(phase = UpdatePhase.UP_TO_DATE, currentVersion = updateManager.currentVersion())
        is UpdateStatus.Available -> UpdateState(
            phase = UpdatePhase.AVAILABLE,
            currentVersion = status.info.currentVersion,
            latestVersion = status.info.latestVersion,
            notes = status.info.notes,
        )
        is UpdateStatus.Downloading -> UpdateState(
            phase = UpdatePhase.DOWNLOADING,
            currentVersion = status.info.currentVersion,
            latestVersion = status.info.latestVersion,
            notes = status.info.notes,
            percent = status.percent,
        )
        is UpdateStatus.Downloaded -> UpdateState(
            phase = UpdatePhase.READY,
            currentVersion = status.info.currentVersion,
            latestVersion = status.info.latestVersion,
            notes = status.info.notes,
        )
        is UpdateStatus.Failed -> UpdateState(
            phase = UpdatePhase.FAILED,
            currentVersion = updateManager.currentVersion(),
            error = status.error,
        )
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

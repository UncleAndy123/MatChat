package org.matchat.feature.onboarding

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
import org.matchat.core.model.background.BackgroundSync
import javax.inject.Inject

@HiltViewModel
class BackgroundHelperViewModel @Inject constructor(
    private val backgroundSync: BackgroundSync,
) : ViewModel() {

    val state: StateFlow<BackgroundHelperState> =
        backgroundSync.status
            .map { BackgroundHelperState(status = it.helperStatus) }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                BackgroundHelperState(status = backgroundSync.status.value.helperStatus),
            )

    private val navChannel = Channel<BackgroundHelperNav>(Channel.BUFFERED)
    val navEvents: Flow<BackgroundHelperNav> = navChannel.receiveAsFlow()

    fun onAction(action: BackgroundHelperAction) {
        when (action) {
            BackgroundHelperAction.OpenSettings ->
                viewModelScope.launch { navChannel.send(BackgroundHelperNav.AccessibilitySettings) }
            BackgroundHelperAction.Refresh -> backgroundSync.refresh()
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

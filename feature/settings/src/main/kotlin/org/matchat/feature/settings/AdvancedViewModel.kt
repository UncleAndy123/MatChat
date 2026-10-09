package org.matchat.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.matchat.core.model.background.BackgroundSync
import org.matchat.core.ui.prefs.UserPreferences
import javax.inject.Inject

@HiltViewModel
class AdvancedViewModel @Inject constructor(
    private val userPreferences: UserPreferences,
    private val backgroundSync: BackgroundSync,
) : ViewModel() {

    val state: StateFlow<AdvancedState> =
        combine(userPreferences.softkeysSwapped, backgroundSync.status) { swapped, background ->
            AdvancedState(
                softkeysSwapped = swapped,
                batteryExempt = background.batteryExempt,
                helperStatus = background.helperStatus,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AdvancedState())

    fun onAction(action: AdvancedAction) {
        when (action) {
            AdvancedAction.ToggleSoftkeysSwapped -> viewModelScope.launch {
                userPreferences.setSoftkeysSwapped(!userPreferences.softkeysSwapped.value)
            }
            AdvancedAction.Refresh -> backgroundSync.refresh()
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

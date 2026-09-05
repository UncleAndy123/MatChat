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
import javax.inject.Inject

@HiltViewModel
class WelcomeViewModel @Inject constructor() : ViewModel() {

    private val _state = MutableStateFlow(WelcomeState(qrEnabled = false))
    val state: StateFlow<WelcomeState> = _state.asStateFlow()

    private val navChannel = Channel<WelcomeNav>(Channel.BUFFERED)
    val navEvents: Flow<WelcomeNav> = navChannel.receiveAsFlow()

    fun onAction(action: WelcomeAction) {
        val nav = when (action) {
            WelcomeAction.SignInWithPassword -> WelcomeNav.Password
            WelcomeAction.SignInWithQr -> WelcomeNav.Qr
            WelcomeAction.Help -> WelcomeNav.Help
        }
        viewModelScope.launch { navChannel.send(nav) }
    }
}

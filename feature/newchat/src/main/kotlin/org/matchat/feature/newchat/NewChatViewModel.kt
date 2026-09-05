package org.matchat.feature.newchat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.matchat.core.contacts.ContactsRepository
import org.matchat.core.matrix.MatrixSession
import org.matchat.core.model.Contact
import org.matchat.core.model.UserId
import javax.inject.Inject

@HiltViewModel
class NewChatViewModel @Inject constructor(
    private val session: MatrixSession,
    contacts: ContactsRepository,
) : ViewModel() {

    private val navChannel = Channel<NewChatNav>(Channel.BUFFERED)
    val navEvents: Flow<NewChatNav> = navChannel.receiveAsFlow()

    val state: StateFlow<NewChatState> =
        combine(contacts.contacts, contacts.recents) { people, recents ->
            NewChatState(
                contacts = people.map { it.toRow() },
                recents = recents.map { it.toRow() },
                isLoading = false,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), NewChatState())

    fun onAction(action: NewChatAction) {
        when (action) {
            is NewChatAction.Select -> startChat(action.address)
            NewChatAction.TypeAnAddress -> emit(NewChatNav.TypeAddress)
        }
    }

    private fun startChat(address: UserId) {
        viewModelScope.launch {
            session.startDirectChat(address).getOrNull()?.let { emit(NewChatNav.OpenRoom(it)) }
        }
    }

    private fun emit(nav: NewChatNav) {
        viewModelScope.launch { navChannel.send(nav) }
    }

    private fun Contact.toRow() = ContactRow(
        address = address,
        primary = name ?: address.value,
        secondary = if (name != null) address.value else "",
    )

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

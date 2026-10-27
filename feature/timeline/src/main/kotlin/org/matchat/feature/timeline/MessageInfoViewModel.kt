package org.matchat.feature.timeline

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.matchat.core.contacts.ContactsRepository
import org.matchat.core.matrix.MatrixSession
import org.matchat.core.model.EventId
import org.matchat.core.model.ReactionSummary
import org.matchat.core.model.RoomId
import org.matchat.core.model.TimelineItem
import org.matchat.core.model.UserId
import org.matchat.core.policy.PolicyProvider
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

/**
 * S11 Message info: who sent a message and when, who reacted, and — merged in
 * from the old profile screen — the sender's name and address with Send
 * message, Save contact / Remove from contacts and Add to a phone contact….
 * Reactions are live (they can change while this is open), read from the
 * room's own RoomTimeline (same precedent as PinnedMessagesViewModel).
 */
@HiltViewModel
class MessageInfoViewModel @Inject constructor(
    private val session: MatrixSession,
    private val contacts: ContactsRepository,
    policyProvider: PolicyProvider,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val roomId = RoomId(requireNotNull(savedStateHandle["roomId"]))
    private val eventId = EventId(requireNotNull(savedStateHandle["eventId"]))
    private val sender = UserId(savedStateHandle.get<String>("senderId").orEmpty())
    private val sentText = formatFull(savedStateHandle.get<Long>("timestamp") ?: 0L)
    private val timeline = session.timeline(roomId)

    private val senderName = MutableStateFlow<String?>(null)
    private val isOwnMessage = MutableStateFlow(true) // until known: offer nothing
    private var busy = false

    // The sender, while the phone's contact picker is open for them.
    private var pendingPhoneMerge = false

    private val navChannel = Channel<MessageInfoNav>(Channel.BUFFERED)
    val navEvents: Flow<MessageInfoNav> = navChannel.receiveAsFlow()

    /** Empty reactions if the event isn't in the currently-loaded window — a
     *  flagged, honest scope cut, not a crash. */
    val state: StateFlow<MessageInfoState> =
        combine(
            timeline.items.map { it.reactionsOf(eventId) },
            senderName,
            isOwnMessage,
            contacts.isSaved(sender),
            policyProvider.policy,
        ) { reactions, name, own, saved, policy ->
            MessageInfoState(
                senderName = name ?: sender.value,
                senderAddress = sender.value,
                sent = sentText,
                eventId = eventId.value,
                reactions = reactions,
                showContactActions = !own && sender.value.isNotEmpty(),
                canMessage = !own && sender.value.isNotEmpty() && policy.allowDirectChat,
                isSaved = saved,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), initialState())

    init {
        viewModelScope.launch {
            isOwnMessage.value = session.ownUserId() == sender
            senderName.value = session.lookupProfile(sender).getOrNull()?.displayName?.takeIf { it.isNotBlank() }
        }
    }

    /** "Send message": open or start a direct chat with the sender. */
    fun sendMessage() {
        if (busy || !state.value.canMessage) return
        busy = true
        viewModelScope.launch {
            session.startDirectChat(sender).fold(
                onSuccess = { navChannel.send(MessageInfoNav.OpenRoom(it)) },
                onFailure = { navChannel.send(MessageInfoNav.Toast(ToastKey.MESSAGE_FAILED)) },
            )
            busy = false
        }
    }

    fun saveContact() {
        viewModelScope.launch {
            contacts.save(sender, senderName.value)
            navChannel.send(MessageInfoNav.Toast(ToastKey.CONTACT_SAVED))
        }
    }

    fun removeContact() {
        viewModelScope.launch {
            contacts.remove(sender)
            navChannel.send(MessageInfoNav.Toast(ToastKey.CONTACT_REMOVED))
        }
    }

    /** The phone's own picker when MatChat may write contacts, else the
     *  phone's "add to existing contact" screen (UX-SPEC S11). */
    fun addToPhoneContact() {
        viewModelScope.launch {
            if (contacts.canUsePhoneContacts()) {
                pendingPhoneMerge = true
                navChannel.send(MessageInfoNav.PickPhoneContact)
            } else {
                navChannel.send(MessageInfoNav.EditPhoneContact(sender.value))
            }
        }
    }

    /** The picker's answer: [contactUri] null when the user backed out. */
    fun onPhoneContactPicked(contactUri: String?) {
        if (!pendingPhoneMerge) return
        pendingPhoneMerge = false
        if (contactUri == null) return
        viewModelScope.launch {
            val merged = contacts.mergeIntoPhoneContact(contactUri, sender)
            navChannel.send(MessageInfoNav.Toast(if (merged) ToastKey.PHONE_MERGED else ToastKey.PHONE_MERGE_FAILED))
        }
    }

    /** No Contacts app could open the picker or editor. */
    fun onPhoneContactsUnavailable() {
        pendingPhoneMerge = false
        viewModelScope.launch { navChannel.send(MessageInfoNav.Toast(ToastKey.PHONE_CONTACTS_UNAVAILABLE)) }
    }

    private fun initialState() = MessageInfoState(
        senderName = sender.value,
        senderAddress = sender.value,
        sent = sentText,
        eventId = eventId.value,
    )

    private fun List<TimelineItem>.reactionsOf(id: EventId): List<ReactionSummary> = firstNotNullOfOrNull { item ->
        when {
            item is TimelineItem.Message && item.eventId == id -> item.reactions
            item is TimelineItem.Media && item.eventId == id -> item.reactions
            else -> null
        }
    }.orEmpty()

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L

        fun formatFull(epochMs: Long): String =
            if (epochMs <= 0) "—" else DateFormat.getDateTimeInstance().format(Date(epochMs))
    }
}

/** Everything S11 shows, display-ready (labels come from strings.xml in the Fragment). */
data class MessageInfoState(
    val senderName: String,
    val senderAddress: String,
    val sent: String,
    val eventId: String,
    val reactions: List<ReactionSummary> = emptyList(),
    /** Not your own message: Save / Remove contact and Add to a phone contact… */
    val showContactActions: Boolean = false,
    /** …and Send message, unless policy turns direct chat off. */
    val canMessage: Boolean = false,
    val isSaved: Boolean = false,
)

sealed interface MessageInfoNav {
    data class OpenRoom(val roomId: RoomId) : MessageInfoNav
    data class Toast(val key: ToastKey) : MessageInfoNav

    /** Open the phone's own contact picker (contacts permission granted). */
    data object PickPhoneContact : MessageInfoNav

    /** Open the phone's "add to existing contact" screen for [address]. */
    data class EditPhoneContact(val address: String) : MessageInfoNav
}

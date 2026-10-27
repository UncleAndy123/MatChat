package org.matchat.feature.newchat

import org.matchat.core.model.RoomId
import org.matchat.core.model.UserId

sealed interface NewChatAction {
    /** A person's menu > "Send message": open or start a direct chat. */
    data class Message(val address: UserId) : NewChatAction
    data object TypeAnAddress : NewChatAction

    /** A recent's menu > "Save contact". */
    data class SaveContact(val address: UserId) : NewChatAction

    /** A contact's menu > "Add to a phone contact…". */
    data class AddToPhoneContact(val address: UserId) : NewChatAction

    /** A contact's menu > "Remove contact". */
    data class RemoveContact(val address: UserId) : NewChatAction

    /** The phone's contact picker answered: [contactUri] null = backed out. */
    data class PhoneContactPicked(val contactUri: String?) : NewChatAction

    /** No Contacts app could open the picker or editor. */
    data object PhoneContactsUnavailable : NewChatAction
}

sealed interface NewChatNav {
    data class OpenRoom(val roomId: RoomId) : NewChatNav
    data object TypeAddress : NewChatNav

    /** Open the phone's own contact picker (contacts permission granted). */
    data object PickPhoneContact : NewChatNav

    /** Open the phone's "add to existing contact" screen for [address]. */
    data class EditPhoneContact(val address: String) : NewChatNav

    /** A short confirmation or failure line. */
    data class Message(val key: NewChatMessage) : NewChatNav
}

enum class NewChatMessage {
    CONTACT_SAVED,
    CONTACT_REMOVED,
    MESSAGE_FAILED,
    PHONE_MERGED,
    PHONE_MERGE_FAILED,
    PHONE_CONTACTS_UNAVAILABLE,
}

package org.matchat.core.ui.contacts

import android.content.Intent
import android.provider.ContactsContract

/**
 * The phone's own "add to an existing contact" screen, pre-filled with a Matrix
 * address — used by `Add to a phone contact…` (UX-SPEC S12/S20) when MatChat
 * doesn't have the contacts permission. Needs no permission: the user picks the
 * contact and saves in the phone's Contacts app. Deliberately no name extra, so
 * the editor never offers to replace the contact's name with a Matrix nickname.
 * Shared here because two features (S12, S20) launch it and features can't
 * depend on each other.
 */
object PhoneContactIntents {

    /** How the address is labelled in the phone's Contacts app. */
    private const val PROTOCOL_LABEL = "Matrix"

    fun addToExisting(address: String): Intent = Intent(Intent.ACTION_INSERT_OR_EDIT).apply {
        type = ContactsContract.Contacts.CONTENT_ITEM_TYPE
        putExtra(ContactsContract.Intents.Insert.IM_PROTOCOL, PROTOCOL_LABEL)
        putExtra(ContactsContract.Intents.Insert.IM_HANDLE, address)
    }
}

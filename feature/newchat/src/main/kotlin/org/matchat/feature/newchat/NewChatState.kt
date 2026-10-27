package org.matchat.feature.newchat

import org.matchat.core.model.UserId

data class ContactRow(
    val address: UserId,
    val primary: String,
    val secondary: String,
    /** In Contacts (saved or admin-pushed), not just a recent address. */
    val isSaved: Boolean = true,
    /** A contact saved on this phone (not admin-pushed): can be removed. */
    val isRemovable: Boolean = false,
)

/** S20 Contacts: saved contacts, recent addresses, and a final "Type an
 *  address" row. CENTER on a person opens their menu (Send message, …).
 *  No search box — the two lists are short by construction (AGENTS.md §0). */
data class NewChatState(
    val contacts: List<ContactRow> = emptyList(),
    val recents: List<ContactRow> = emptyList(),
    val isLoading: Boolean = true,
    /** Policy allows direct chat: "Send message" and "Type an address" show. */
    val canMessage: Boolean = true,
) {
    val hasSavedContacts: Boolean get() = contacts.isNotEmpty() || recents.isNotEmpty()
}

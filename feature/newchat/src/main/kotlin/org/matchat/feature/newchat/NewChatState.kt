package org.matchat.feature.newchat

import org.matchat.core.model.UserId

data class ContactRow(
    val address: UserId,
    val primary: String,
    val secondary: String,
)

/** S20 New message: contacts, recents, and a final "Type an address" row.
 *  No search box — the two lists are short by construction (AGENTS.md §0). */
data class NewChatState(
    val contacts: List<ContactRow> = emptyList(),
    val recents: List<ContactRow> = emptyList(),
    val isLoading: Boolean = true,
) {
    val hasSavedContacts: Boolean get() = contacts.isNotEmpty() || recents.isNotEmpty()
}

package org.matchat.core.contacts

import kotlinx.coroutines.flow.Flow
import org.matchat.core.model.Contact
import org.matchat.core.model.UserId

/**
 * The merged contact list shown on S20. There is deliberately no `search` or
 * `query` here: the whole list is short and scrolled with the D-pad (AGENTS.md §0).
 */
interface ContactsRepository {
    /** Admin-pushed + local, de-duplicated by address, admin entries first. */
    val contacts: Flow<List<Contact>>

    /** The last 8 addresses used, newest first (S20 "Recent"). */
    val recents: Flow<List<Contact>>

    /** Remember an address the user typed and sent to (feeds recents + local). */
    suspend fun recordRecent(address: UserId)
}

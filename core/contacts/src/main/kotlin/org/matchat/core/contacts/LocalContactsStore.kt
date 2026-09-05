package org.matchat.core.contacts

import kotlinx.coroutines.flow.Flow
import org.matchat.core.model.Contact
import org.matchat.core.model.UserId

/**
 * On-device store of people the user has DM'd and the last typed addresses.
 * Never uploaded (PLAN.md §6.10). M0 provides an in-memory implementation;
 * disk persistence (DataStore) lands with the newchat feature in M5.
 */
interface LocalContactsStore {
    val local: Flow<List<Contact>>
    val recents: Flow<List<Contact>>
    suspend fun addRecent(address: UserId)
}

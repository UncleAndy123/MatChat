package org.matchat.core.contacts

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.matchat.core.model.Contact
import org.matchat.core.model.UserId
import javax.inject.Inject
import javax.inject.Singleton

/** M0 in-memory store. Persistence (DataStore) replaces this in M5. */
@Singleton
internal class InMemoryLocalContactsStore @Inject constructor() : LocalContactsStore {
    private val recentAddresses = MutableStateFlow<List<UserId>>(emptyList())

    override val recents: Flow<List<Contact>> =
        recentAddresses.map { list -> list.map { Contact(it, name = null, source = Contact.Source.LOCAL) } }

    // A DM partner becomes a local contact; until DM creation lands (M5) this
    // mirrors recents so the merge logic has something to de-duplicate against.
    override val local: Flow<List<Contact>> = recents

    override suspend fun addRecent(address: UserId) {
        recentAddresses.value = (listOf(address) + recentAddresses.value)
            .distinct()
            .take(MAX_RECENTS)
    }

    private companion object {
        const val MAX_RECENTS = 8 // "last 8 typed addresses" (PLAN.md §6.10)
    }
}

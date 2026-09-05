package org.matchat.core.contacts

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import org.matchat.core.model.Contact
import org.matchat.core.model.UserId
import org.matchat.core.policy.PolicyProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Merges the admin-pushed contacts (from live policy) with local contacts and
 * recents. De-duplicated by address, admin entries first (PLAN.md §6.10). The
 * admin list is re-read from the policy Flow, so an EMM change reflows the list
 * without a restart.
 */
@Singleton
internal class DefaultContactsRepository @Inject constructor(
    private val policyProvider: PolicyProvider,
    private val localStore: LocalContactsStore,
) : ContactsRepository {

    override val contacts: Flow<List<Contact>> =
        combine(policyProvider.policy, localStore.local) { policy, local ->
            merge(admin = policy.adminContacts, local = local)
        }

    override val recents: Flow<List<Contact>> = localStore.recents

    override suspend fun recordRecent(address: UserId) = localStore.addRecent(address)

    private fun merge(admin: List<Contact>, local: List<Contact>): List<Contact> {
        val seen = HashSet<String>()
        val out = ArrayList<Contact>(admin.size + local.size)
        // Admin first, so a locally-known address never shadows the admin's name.
        for (c in admin + local) {
            if (seen.add(c.address.value)) out += c
        }
        return out
    }
}

package org.matchat.core.contacts

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.matchat.core.model.Contact
import org.matchat.core.model.UserId
import org.matchat.core.policy.PolicyProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Merges the admin-pushed contacts (from live policy) with the saved ones.
 * De-duplicated by address, admin entries first (PLAN.md §6.10). The admin
 * list is re-read from the policy Flow, so an EMM change reflows the list
 * without a restart.
 */
@Singleton
internal class DefaultContactsRepository @Inject constructor(
    private val policyProvider: PolicyProvider,
    private val localStore: LocalContactsStore,
    private val phone: PhoneContacts,
) : ContactsRepository {

    override val contacts: Flow<List<Contact>> =
        combine(policyProvider.policy, localStore.saved) { policy, saved ->
            merge(admin = policy.adminContacts, saved = saved)
        }

    override val recents: Flow<List<Contact>> =
        combine(localStore.recents, contacts) { recents, known ->
            val knownAddresses = known.mapTo(HashSet()) { it.address }
            recents.filterNot { it.address in knownAddresses }
        }

    override val phoneSyncEnabled: Flow<Boolean> =
        localStore.phoneSyncEnabled.map { it && phone.isAllowed() }

    override suspend fun recordRecent(address: UserId) = localStore.addRecent(address)

    override fun isSaved(address: UserId): Flow<Boolean> =
        contacts.map { list -> list.any { it.address == address } }.distinctUntilChanged()

    override suspend fun save(address: UserId, name: String?) {
        localStore.save(address, name)
        copyToPhone(address, name)
    }

    override suspend fun autoSave(address: UserId, name: String?) {
        if (localStore.saveIfNew(address, name)) copyToPhone(address, name)
    }

    override suspend fun remove(address: UserId) = localStore.remove(address)

    override fun canUsePhoneContacts(): Boolean = phone.isAllowed()

    override suspend fun setPhoneSync(enabled: Boolean) {
        localStore.setPhoneSyncEnabled(enabled)
        if (enabled && phone.isAllowed()) {
            localStore.saved.first().forEach { phone.upsert(it.address, it.name) }
        }
    }

    override suspend fun turnOnPhoneSync(): Boolean {
        if (!phone.isAllowed()) return false
        setPhoneSync(true)
        return true
    }

    override suspend fun onPhonePermissionResult(granted: Boolean): Boolean =
        if (granted) turnOnPhoneSync() else false

    override fun isPhonePromptOffered(): Boolean = localStore.isPhonePromptOffered()

    override fun markPhonePromptOffered() = localStore.markPhonePromptOffered()

    override suspend fun mergeIntoPhoneContact(contactUri: String, address: UserId): Boolean {
        val phoneName = phone.mergeInto(contactUri, address) ?: return false
        localStore.save(address, phoneName.takeIf { it.isNotBlank() })
        return true
    }

    private suspend fun copyToPhone(address: UserId, name: String?) {
        if (localStore.phoneSyncEnabled.first() && phone.isAllowed()) phone.upsert(address, name)
    }

    private fun merge(admin: List<Contact>, saved: List<Contact>): List<Contact> {
        val seen = HashSet<String>()
        val out = ArrayList<Contact>(admin.size + saved.size)
        // Admin first, so a locally-known address never shadows the admin's name.
        val savedByName = saved.sortedBy { (it.name ?: it.address.value).lowercase() }
        for (c in admin + savedByName) {
            if (seen.add(c.address.value)) out += c
        }
        return out
    }
}

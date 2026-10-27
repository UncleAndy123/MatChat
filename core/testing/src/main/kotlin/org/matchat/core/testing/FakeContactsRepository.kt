package org.matchat.core.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import org.matchat.core.contacts.ContactsRepository
import org.matchat.core.model.Contact
import org.matchat.core.model.UserId

/**
 * An in-memory [ContactsRepository] for ViewModel tests. [phoneAllowed] stands
 * in for the contacts permission; [mergeResult] is the name the phone's picked
 * contact "has" (null = the merge fails). Tests assert on [saved], [phoneSync]
 * and [promptOffered] — the resulting state, not calls.
 */
class FakeContactsRepository(
    initial: List<Contact> = emptyList(),
    var phoneAllowed: Boolean = false,
    var mergeResult: String? = null,
) : ContactsRepository {

    val saved = MutableStateFlow(initial)
    val recentState = MutableStateFlow<List<Contact>>(emptyList())
    val phoneSync = MutableStateFlow(false)
    val removed = mutableSetOf<UserId>()
    var promptOffered = false

    override val contacts: Flow<List<Contact>> = saved
    override val recents: Flow<List<Contact>> =
        combine(recentState, saved) { recents, known ->
            recents.filterNot { r -> known.any { it.address == r.address } }
        }
    override val phoneSyncEnabled: Flow<Boolean> = phoneSync.map { it && phoneAllowed }

    override suspend fun recordRecent(address: UserId) {
        recentState.value = (listOf(Contact(address, null, Contact.Source.LOCAL)) + recentState.value).distinct()
    }

    override fun isSaved(address: UserId): Flow<Boolean> = saved.map { list -> list.any { it.address == address } }

    override suspend fun save(address: UserId, name: String?) {
        removed -= address
        val existing = saved.value.firstOrNull { it.address == address }
        saved.value = if (existing == null) {
            saved.value + Contact(address, name, Contact.Source.LOCAL)
        } else {
            saved.value.map { if (it.address == address && name != null) it.copy(name = name) else it }
        }
    }

    override suspend fun autoSave(address: UserId, name: String?) {
        if (address in removed || saved.value.any { it.address == address }) return
        saved.value = saved.value + Contact(address, name, Contact.Source.LOCAL)
    }

    override suspend fun remove(address: UserId) {
        saved.value = saved.value.filterNot { it.address == address }
        removed += address
    }

    override fun canUsePhoneContacts(): Boolean = phoneAllowed

    override suspend fun setPhoneSync(enabled: Boolean) {
        phoneSync.value = enabled
    }

    override suspend fun turnOnPhoneSync(): Boolean {
        if (!phoneAllowed) return false
        phoneSync.value = true
        return true
    }

    /** A granted answer grants [phoneAllowed], as Android would. */
    override suspend fun onPhonePermissionResult(granted: Boolean): Boolean {
        if (granted) phoneAllowed = true
        return if (granted) turnOnPhoneSync() else false
    }

    override fun isPhonePromptOffered(): Boolean = promptOffered

    override fun markPhonePromptOffered() {
        promptOffered = true
    }

    override suspend fun mergeIntoPhoneContact(contactUri: String, address: UserId): Boolean {
        val name = mergeResult ?: return false
        save(address, name)
        return true
    }
}

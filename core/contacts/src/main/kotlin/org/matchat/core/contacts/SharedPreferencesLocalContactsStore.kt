package org.matchat.core.contacts

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.matchat.core.model.Contact
import org.matchat.core.model.UserId
import javax.inject.Inject
import javax.inject.Singleton

/** [LocalContactsStore] kept in a private SharedPreferences file, so saved
 *  contacts survive a restart (they used to live only in memory). The lists
 *  are small JSON arrays ([ContactsCodec]); every change rewrites one key. */
@Singleton
internal class SharedPreferencesLocalContactsStore @Inject constructor(
    @ApplicationContext context: Context,
) : LocalContactsStore {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    private val savedState = MutableStateFlow(ContactsCodec.decodeContacts(prefs.getString(KEY_SAVED, null)))
    private val recentState = MutableStateFlow(ContactsCodec.decodeAddresses(prefs.getString(KEY_RECENTS, null)))
    private val removedState = MutableStateFlow(ContactsCodec.decodeAddresses(prefs.getString(KEY_REMOVED, null)))
    private val phoneSyncState = MutableStateFlow(prefs.getBoolean(KEY_PHONE_SYNC, false))

    override val saved: Flow<List<Contact>> = savedState
    override val recents: Flow<List<Contact>> =
        recentState.map { list -> list.map { Contact(it, name = null, source = Contact.Source.LOCAL) } }
    override val phoneSyncEnabled: Flow<Boolean> = phoneSyncState

    override suspend fun addRecent(address: UserId) {
        synchronized(lock) {
            val next = (listOf(address) + recentState.value).distinct().take(MAX_RECENTS)
            recentState.value = next
            prefs.edit().putString(KEY_RECENTS, ContactsCodec.encodeAddresses(next)).apply()
        }
    }

    override suspend fun save(address: UserId, name: String?) {
        synchronized(lock) {
            if (address in removedState.value) writeRemoved(removedState.value - address)
            val current = savedState.value
            val existing = current.firstOrNull { it.address == address }
            if (existing == null) {
                writeSaved(current + Contact(address, name, Contact.Source.LOCAL))
            } else if (name != null && name != existing.name) {
                writeSaved(current.map { if (it.address == address) it.copy(name = name) else it })
            }
        }
    }

    override suspend fun saveIfNew(address: UserId, name: String?): Boolean {
        synchronized(lock) {
            val current = savedState.value
            if (address in removedState.value || current.any { it.address == address }) return false
            writeSaved(current + Contact(address, name, Contact.Source.LOCAL))
            return true
        }
    }

    override suspend fun remove(address: UserId) {
        synchronized(lock) {
            writeSaved(savedState.value.filterNot { it.address == address })
            if (address !in removedState.value) writeRemoved(removedState.value + address)
        }
    }

    override suspend fun setPhoneSyncEnabled(enabled: Boolean) {
        synchronized(lock) {
            phoneSyncState.value = enabled
            prefs.edit().putBoolean(KEY_PHONE_SYNC, enabled).apply()
        }
    }

    override fun isPhonePromptOffered(): Boolean = prefs.getBoolean(KEY_PROMPT_OFFERED, false)

    override fun markPhonePromptOffered() {
        prefs.edit().putBoolean(KEY_PROMPT_OFFERED, true).apply()
    }

    private fun writeSaved(next: List<Contact>) {
        savedState.value = next
        prefs.edit().putString(KEY_SAVED, ContactsCodec.encodeContacts(next)).apply()
    }

    private fun writeRemoved(next: List<UserId>) {
        removedState.value = next
        prefs.edit().putString(KEY_REMOVED, ContactsCodec.encodeAddresses(next)).apply()
    }

    private companion object {
        const val PREFS_NAME = "matchat_contacts"
        const val KEY_SAVED = "saved"
        const val KEY_RECENTS = "recents"
        const val KEY_REMOVED = "removed"
        const val KEY_PHONE_SYNC = "phone_sync"
        const val KEY_PROMPT_OFFERED = "phone_prompt_offered"
        const val MAX_RECENTS = 8 // "last 8 typed addresses" (PLAN.md §6.10)
    }
}

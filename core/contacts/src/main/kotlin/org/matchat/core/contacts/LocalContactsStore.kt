package org.matchat.core.contacts

import kotlinx.coroutines.flow.Flow
import org.matchat.core.model.Contact
import org.matchat.core.model.UserId

/**
 * On-device store of the user's saved contacts (everyone they have a direct
 * chat with, plus anyone they saved by hand) and the last typed addresses.
 * Never uploaded (PLAN.md §6.10).
 */
interface LocalContactsStore {
    /** Saved contacts, in the order they were saved, oldest first. */
    val saved: Flow<List<Contact>>

    /** The last 8 typed addresses, newest first. */
    val recents: Flow<List<Contact>>

    /** Settings > "Save contacts to phone" (off until the user turns it on). */
    val phoneSyncEnabled: Flow<Boolean>

    suspend fun addRecent(address: UserId)

    /** Saves [address], or renames it when [name] is non-null. A save by
     *  hand also undoes an earlier [remove], so auto-saving may add it again. */
    suspend fun save(address: UserId, name: String?)

    /** Saves [address] unless it is saved already or the user removed it.
     *  Never renames. True when it was added. */
    suspend fun saveIfNew(address: UserId, name: String?): Boolean

    /** Removes [address], and remembers that, so auto-saving won't re-add it. */
    suspend fun remove(address: UserId)

    suspend fun setPhoneSyncEnabled(enabled: Boolean)

    /** Whether the one-time "Save contacts to this phone?" prompt (S30) was shown. */
    fun isPhonePromptOffered(): Boolean

    fun markPhonePromptOffered()
}

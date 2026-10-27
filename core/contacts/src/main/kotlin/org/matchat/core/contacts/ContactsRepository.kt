package org.matchat.core.contacts

import kotlinx.coroutines.flow.Flow
import org.matchat.core.model.Contact
import org.matchat.core.model.UserId

/**
 * The people the user already knows (S20): admin-pushed contacts plus the ones
 * saved on this phone — everyone they have a direct chat with, and anyone they
 * saved by hand (S12). There is deliberately no `search` or `query` here: the
 * whole list is short and scrolled with the D-pad (AGENTS.md §0).
 *
 * Saved contacts always live in MatChat. When the user has turned on Settings >
 * "Save contacts to phone" (and granted the contacts permission), each one is
 * also written to the phone's own Contacts app.
 */
interface ContactsRepository {
    /** Admin-pushed first, then saved ones by name; de-duplicated by address. */
    val contacts: Flow<List<Contact>>

    /** The last 8 typed addresses, newest first, minus any already in [contacts]. */
    val recents: Flow<List<Contact>>

    /** Settings > "Save contacts to phone": on only while the user wants it
     *  *and* the permission is still granted (re-read on each emission). */
    val phoneSyncEnabled: Flow<Boolean>

    /** Remember an address the user typed and sent to (S20 "Recent"). */
    suspend fun recordRecent(address: UserId)

    /** True while [address] is a saved (or admin-pushed) contact. */
    fun isSaved(address: UserId): Flow<Boolean>

    /** Save by hand (S12 "Save contact"); [name] null keeps any saved name. */
    suspend fun save(address: UserId, name: String?)

    /** Save a direct-chat partner, unless already saved or removed by the user. */
    suspend fun autoSave(address: UserId, name: String?)

    /** Remove from MatChat. The phone's copy, if any, is left alone. */
    suspend fun remove(address: UserId)

    /** True when the contacts permission is granted right now. */
    fun canUsePhoneContacts(): Boolean

    /** Turn the phone copy on (copying every saved contact over) or off. */
    suspend fun setPhoneSync(enabled: Boolean)

    /** Settings / S30 "turn it on": on at once (true) when the permission is
     *  already granted; false means the caller must ask Android for it and
     *  report the answer to [onPhonePermissionResult]. */
    suspend fun turnOnPhoneSync(): Boolean

    /** The permission answer. True when the phone copy is now on; false means
     *  contacts stay in MatChat only. */
    suspend fun onPhonePermissionResult(granted: Boolean): Boolean

    /** The one-time S30 prompt: whether it was shown, and marking it shown. */
    fun isPhonePromptOffered(): Boolean
    fun markPhonePromptOffered()

    /** Add [address] to an existing phone contact the user picked; on success
     *  MatChat's saved name becomes that contact's name (the phone's name is
     *  never changed). False if it failed. */
    suspend fun mergeIntoPhoneContact(contactUri: String, address: UserId): Boolean
}

package org.matchat.core.contacts

import org.matchat.core.model.UserId

/**
 * The phone's own Contacts app, as an optional second home for MatChat's
 * contacts (Settings > "Save contacts to phone", S30). Only used once the user
 * has granted the contacts permission; without it, contacts stay in MatChat
 * only and nothing here is called. Never a source of people to *find* — it is
 * only written to, plus one lookup to avoid a duplicate (AGENTS.md §0).
 */
interface PhoneContacts {
    /** True when the contacts permission (read + write) is granted. */
    fun isAllowed(): Boolean

    /** Adds a phone contact for [address] (name, Matrix address, matrix.to
     *  link) unless a phone contact already carries that address. */
    suspend fun upsert(address: UserId, name: String?)

    /**
     * Adds [address] to the existing phone contact [contactUri] (the phone's
     * own contact picker's answer). Only *adds* the Matrix address and link:
     * the contact's name is never changed. A separate phone contact MatChat
     * created earlier for the same address is deleted so the person isn't
     * listed twice. Returns that contact's name, or null if it failed.
     */
    suspend fun mergeInto(contactUri: String, address: UserId): String?
}

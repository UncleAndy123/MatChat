package org.matchat.core.contacts

import org.json.JSONArray
import org.json.JSONObject
import org.matchat.core.model.Contact
import org.matchat.core.model.UserId

/** The JSON shapes [SharedPreferencesLocalContactsStore] keeps in its
 *  preferences. Pure, so it's unit-tested without a device. */
internal object ContactsCodec {

    private const val ADDRESS = "a"
    private const val NAME = "n"

    fun encodeContacts(contacts: List<Contact>): String {
        val array = JSONArray()
        contacts.forEach { c ->
            array.put(JSONObject().put(ADDRESS, c.address.value).apply { c.name?.let { put(NAME, it) } })
        }
        return array.toString()
    }

    /** Unreadable or missing data reads as an empty list, never a crash. */
    fun decodeContacts(json: String?): List<Contact> {
        val array = parse(json) ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            val address = obj.optString(ADDRESS).takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = if (obj.has(NAME)) obj.optString(NAME).takeIf { it.isNotBlank() } else null
            Contact(UserId(address), name, Contact.Source.LOCAL)
        }
    }

    fun encodeAddresses(addresses: List<UserId>): String {
        val array = JSONArray()
        addresses.forEach { array.put(it.value) }
        return array.toString()
    }

    fun decodeAddresses(json: String?): List<UserId> {
        val array = parse(json) ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            array.optString(i).takeIf { it.isNotBlank() }?.let(::UserId)
        }
    }

    private fun parse(json: String?): JSONArray? = json?.let { runCatching { JSONArray(it) }.getOrNull() }
}

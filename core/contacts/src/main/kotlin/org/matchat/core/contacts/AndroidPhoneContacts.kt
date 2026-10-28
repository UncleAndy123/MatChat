package org.matchat.core.contacts

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.matchat.core.model.UserId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [PhoneContacts] over the system ContactsContract provider. New contacts go
 * in the phone's own storage (no account), so they never sync anywhere.
 * Every call is best-effort: a failure is logged (never with the address or
 * name — AGENTS.md §9) and the contact simply stays in MatChat.
 *
 * Remembers the raw contacts it created (address -> id) in its own small
 * preferences file, so a later merge into an existing contact can delete
 * MatChat's separate copy instead of leaving the person listed twice.
 */
@Singleton
internal class AndroidPhoneContacts @Inject constructor(
    @ApplicationContext private val context: Context,
) : PhoneContacts {

    private val created = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun isAllowed(): Boolean =
        granted(Manifest.permission.READ_CONTACTS) && granted(Manifest.permission.WRITE_CONTACTS)

    override suspend fun upsert(address: UserId, name: String?) {
        if (!isAllowed()) return
        withContext(Dispatchers.IO) {
            runCatching {
                if (rawContactsWith(address).isNotEmpty()) return@runCatching
                val ops = arrayListOf(
                    ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                        .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                        .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                        .build(),
                )
                PhoneContactRows.newContact(address, name).forEach { row ->
                    ops += dataInsert(row).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0).build()
                }
                val results = context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
                val rawUri = results.firstOrNull()?.uri
                if (rawUri != null) created.edit().putLong(address.value, ContentUris.parseId(rawUri)).apply()
            }.onFailure { Log.w(TAG, "couldn't add a phone contact", it) }
        }
    }

    override suspend fun mergeInto(contactUri: String, address: UserId): String? {
        if (!isAllowed()) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val (contactId, displayName) = contactOf(Uri.parse(contactUri)) ?: return@runCatching null
                val targetRaw = firstRawContactOf(contactId) ?: return@runCatching null
                val existing = rawContactsWith(address)
                if (targetRaw !in existing && existing.none { contactIdOfRaw(it) == contactId }) {
                    val ops = arrayListOf<ContentProviderOperation>()
                    PhoneContactRows.addressRows(address).forEach { row ->
                        ops += dataInsert(row).withValue(ContactsContract.Data.RAW_CONTACT_ID, targetRaw).build()
                    }
                    context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
                }
                deleteOwnCopy(address, keepContactId = contactId)
                displayName
            }.onFailure { Log.w(TAG, "couldn't merge into a phone contact", it) }.getOrNull()
        }
    }

    /** Deletes the separate phone contact MatChat created for [address], unless
     *  it is (now part of) the contact the user picked. */
    private fun deleteOwnCopy(address: UserId, keepContactId: Long) {
        val rawId = created.getLong(address.value, NONE)
        if (rawId == NONE) return
        if (contactIdOfRaw(rawId) != keepContactId) {
            context.contentResolver.delete(
                ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, rawId),
                null,
                null,
            )
        }
        created.edit().remove(address.value).apply()
    }

    private fun dataInsert(row: PhoneContactRows.Row): ContentProviderOperation.Builder {
        val builder = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValue(ContactsContract.Data.MIMETYPE, row.mimeType)
        row.values.forEach { (column, value) -> builder.withValue(column, value) }
        return builder
    }

    /** Raw contacts that already carry [address] as their Matrix IM handle. */
    private fun rawContactsWith(address: UserId): List<Long> {
        val out = mutableListOf<Long>()
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.RAW_CONTACT_ID),
            "${ContactsContract.Data.MIMETYPE}=? AND ${ContactsContract.Data.DATA1}=?",
            arrayOf(PhoneContactRows.MIME_IM, address.value),
            null,
        )?.use { c -> while (c.moveToNext()) out += c.getLong(0) }
        return out
    }

    /** The picked contact's id and display name. */
    private fun contactOf(uri: Uri): Pair<Long, String?>? = context.contentResolver.query(
        uri,
        arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { c -> if (c.moveToFirst()) c.getLong(0) to c.getString(1) else null }

    private fun firstRawContactOf(contactId: Long): Long? = context.contentResolver.query(
        ContactsContract.RawContacts.CONTENT_URI,
        arrayOf(ContactsContract.RawContacts._ID),
        "${ContactsContract.RawContacts.CONTACT_ID}=?",
        arrayOf(contactId.toString()),
        "${ContactsContract.RawContacts._ID} ASC",
    )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }

    private fun contactIdOfRaw(rawId: Long): Long? = context.contentResolver.query(
        ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, rawId),
        arrayOf(ContactsContract.RawContacts.CONTACT_ID),
        null,
        null,
        null,
    )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "MatChatPhoneContacts"
        const val PREFS_NAME = "matchat_phone_contacts"
        const val NONE = -1L
    }
}

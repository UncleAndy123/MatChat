package org.matchat.core.contacts

import org.matchat.core.model.UserId

/**
 * The phone-contact rows MatChat writes for a Matrix address, as plain
 * (mimetype, column -> value) pairs so the choice of rows is unit-testable
 * without a ContentResolver. [AndroidPhoneContacts] turns them into
 * ContactsContract inserts.
 */
internal object PhoneContactRows {

    const val MIME_NAME = "vnd.android.cursor.item/name"
    const val MIME_IM = "vnd.android.cursor.item/im"
    const val MIME_WEBSITE = "vnd.android.cursor.item/website"

    /** ContactsContract.CommonDataKinds.Im: PROTOCOL_CUSTOM / TYPE_OTHER;
     *  Website: TYPE_OTHER. Literal so this file stays free of Android. */
    private const val IM_PROTOCOL_CUSTOM = -1
    private const val TYPE_OTHER = 3
    private const val WEBSITE_TYPE_OTHER = 7

    /** How the address is labelled in the phone's Contacts app. */
    const val PROTOCOL_LABEL = "Matrix"

    data class Row(val mimeType: String, val values: Map<String, Any>)

    /** A new phone contact: its name plus [addressRows]. */
    fun newContact(address: UserId, name: String?): List<Row> =
        listOf(Row(MIME_NAME, mapOf("data1" to (name?.takeIf { it.isNotBlank() } ?: address.value)))) +
            addressRows(address)

    /** What a merge adds to an existing contact: only the Matrix address and
     *  the matrix.to link — never a name row, so the contact's name on the
     *  phone stays exactly as the user saved it. */
    fun addressRows(address: UserId): List<Row> = listOf(
        Row(
            MIME_IM,
            mapOf(
                "data1" to address.value,
                "data2" to TYPE_OTHER,
                "data5" to IM_PROTOCOL_CUSTOM,
                "data6" to PROTOCOL_LABEL,
            ),
        ),
        Row(MIME_WEBSITE, mapOf("data1" to matrixToLink(address), "data2" to WEBSITE_TYPE_OTHER)),
    )

    fun matrixToLink(address: UserId): String = "https://matrix.to/#/${address.value}"
}

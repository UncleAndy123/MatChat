package org.matchat.core.contacts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.matchat.core.model.Contact
import org.matchat.core.model.UserId

class ContactsCodecTest {

    @Test
    fun `contacts round-trip, with and without a name`() {
        val contacts = listOf(
            Contact(UserId("@wayne:example.org"), "Wayne", Contact.Source.LOCAL),
            Contact(UserId("@merv:carpathianserver.org"), null, Contact.Source.LOCAL),
        )
        assertEquals(contacts, ContactsCodec.decodeContacts(ContactsCodec.encodeContacts(contacts)))
    }

    @Test
    fun `addresses round-trip in order`() {
        val addresses = listOf(UserId("@b:s"), UserId("@a:s"))
        assertEquals(addresses, ContactsCodec.decodeAddresses(ContactsCodec.encodeAddresses(addresses)))
    }

    @Test
    fun `missing or damaged data reads as empty`() {
        assertEquals(emptyList<Contact>(), ContactsCodec.decodeContacts(null))
        assertEquals(emptyList<Contact>(), ContactsCodec.decodeContacts("not json"))
        assertEquals(emptyList<UserId>(), ContactsCodec.decodeAddresses("{"))
    }

    @Test
    fun `a merge adds only the Matrix address and link, never a name`() {
        val rows = PhoneContactRows.addressRows(UserId("@wayne:example.org"))
        assertFalse(rows.any { it.mimeType == PhoneContactRows.MIME_NAME })
        assertEquals("@wayne:example.org", rows.single { it.mimeType == PhoneContactRows.MIME_IM }.values["data1"])
        assertEquals(
            "https://matrix.to/#/@wayne:example.org",
            rows.single { it.mimeType == PhoneContactRows.MIME_WEBSITE }.values["data1"],
        )
    }

    @Test
    fun `a new phone contact is named, falling back to the address`() {
        val named = PhoneContactRows.newContact(UserId("@w:s"), "Wayne")
        assertEquals("Wayne", named.single { it.mimeType == PhoneContactRows.MIME_NAME }.values["data1"])
        val unnamed = PhoneContactRows.newContact(UserId("@w:s"), null)
        assertEquals("@w:s", unnamed.single { it.mimeType == PhoneContactRows.MIME_NAME }.values["data1"])
        assertTrue(unnamed.any { it.mimeType == PhoneContactRows.MIME_IM })
    }
}

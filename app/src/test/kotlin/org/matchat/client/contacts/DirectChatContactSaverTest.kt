package org.matchat.client.contacts

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.matchat.core.model.Contact
import org.matchat.core.model.RoomId
import org.matchat.core.model.RoomSummary
import org.matchat.core.model.UserId
import org.matchat.core.testing.FakeContactsRepository

/** Direct-chat partners become saved contacts (PLAN.md §6.10). */
class DirectChatContactSaverTest {

    private val wayne = UserId("@wayne:example.org")

    private fun room(id: String, name: String, direct: UserId?) = RoomSummary(
        id = RoomId(id),
        name = name,
        lastMessage = null,
        lastActivityEpochMs = null,
        unreadCount = 0,
        isEncrypted = true,
        directUserId = direct,
    )

    @Test
    fun `a direct chat partner is saved under the chat's name`() = runBlocking {
        val contacts = FakeContactsRepository()
        DirectChatContactSaver(contacts).onRooms(listOf(room("!dm:s", "Wayne", wayne)))
        assertEquals(listOf(Contact(wayne, "Wayne", Contact.Source.LOCAL)), contacts.contacts.first())
    }

    @Test
    fun `a group chat saves nobody`() = runBlocking {
        val contacts = FakeContactsRepository()
        DirectChatContactSaver(contacts).onRooms(listOf(room("!g:s", "Family", direct = null)))
        assertTrue(contacts.contacts.first().isEmpty())
    }

    @Test
    fun `a contact the user removed is not added back`() = runBlocking {
        val contacts = FakeContactsRepository()
        contacts.remove(wayne)
        DirectChatContactSaver(contacts).onRooms(listOf(room("!dm:s", "Wayne", wayne)))
        assertTrue(contacts.contacts.first().isEmpty())
    }

    @Test
    fun `a name the user already has is kept`() = runBlocking {
        val contacts = FakeContactsRepository(initial = listOf(Contact(wayne, "Dad", Contact.Source.LOCAL)))
        DirectChatContactSaver(contacts).onRooms(listOf(room("!dm:s", "Wayne", wayne)))
        assertEquals("Dad", contacts.contacts.first().single().name)
    }
}

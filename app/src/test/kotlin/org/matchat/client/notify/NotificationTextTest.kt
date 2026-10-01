package org.matchat.client.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.matchat.core.model.MediaKind

class NotificationTextTest {

    private fun content(sender: String? = "Ann", text: String? = "hi", media: MediaKind? = null) =
        NotificationContent(roomName = "Barn Crew", sender = sender, text = text, media = media, unread = 1)

    @Test
    fun `text messages show their text`() {
        assertEquals(NotificationText.Body.Text("hi"), NotificationText.body(content()))
    }

    @Test
    fun `media shows its kind`() {
        assertEquals(
            NotificationText.Body.Media(MediaKind.VOICE),
            NotificationText.body(content(text = null, media = MediaKind.VOICE)),
        )
    }

    @Test
    fun `nothing to show falls back to the count`() {
        assertEquals(NotificationText.Body.CountOnly, NotificationText.body(content(text = null)))
        assertEquals(NotificationText.Body.CountOnly, NotificationText.body(content(text = "  ")))
    }

    @Test
    fun `group messages name the sender`() {
        assertEquals("Ann", NotificationText.senderPrefix(content()))
    }

    @Test
    fun `no prefix when the sender is the room name`() {
        assertNull(NotificationText.senderPrefix(content(sender = "Barn Crew")))
    }

    @Test
    fun `no prefix when the sender is unknown`() {
        assertNull(NotificationText.senderPrefix(content(sender = null)))
        assertNull(NotificationText.senderPrefix(content(sender = "")))
    }
}

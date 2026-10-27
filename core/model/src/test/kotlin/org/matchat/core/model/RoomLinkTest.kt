package org.matchat.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RoomLinkTest {

    @Test
    fun `matrix-to room id with via servers`() {
        val link = RoomLink.parse("https://matrix.to/#/!VeXInyjU?via=unredacted.org&via=matrix.org")
        assertEquals(RoomLink("!VeXInyjU", listOf("unredacted.org", "matrix.org")), link)
    }

    @Test
    fun `matrix-to alias, percent-encoded`() {
        assertEquals(RoomLink("#barn:example.org"), RoomLink.parse("https://matrix.to/#/%23barn%3Aexample.org"))
    }

    @Test
    fun `an event permalink links to its room`() {
        val link = RoomLink.parse("https://matrix.to/#/!abc:example.org/\$event?via=example.org")
        assertEquals(RoomLink("!abc:example.org", listOf("example.org")), link)
    }

    @Test
    fun `matrix uri forms`() {
        assertEquals(RoomLink("!abc", listOf("s.org")), RoomLink.parse("matrix:roomid/abc?via=s.org"))
        assertEquals(RoomLink("#barn:example.org"), RoomLink.parse("matrix:r/barn:example.org"))
    }

    @Test
    fun `web client room link`() {
        assertEquals(RoomLink("#barn:example.org"), RoomLink.parse("https://app.element.io/#/room/#barn:example.org"))
    }

    @Test
    fun `user links, plain pages and hashtags are not room links`() {
        assertNull(RoomLink.parse("https://matrix.to/#/@wayne:example.org"))
        assertNull(RoomLink.parse("https://example.org/#/about"))
        assertNull(RoomLink.parse("matrix:u/wayne:example.org"))
        assertNull(RoomLink.parse("https://matrix.to/#/#hashtag"))
    }

    @Test
    fun `findIn picks the link out of a sentence and drops trailing punctuation`() {
        val body = "Come join us: https://matrix.to/#/#barn:example.org."
        assertEquals(RoomLink("#barn:example.org"), RoomLink.findIn(body))
        assertNull(RoomLink.findIn("no links here, just #hashtags"))
    }

    @Test
    fun `domain falls back to the first via server for a v12 room id`() {
        assertEquals("example.org", RoomLink("#a:example.org").domain)
        assertEquals("unredacted.org", RoomLink("!abc", listOf("unredacted.org")).domain)
        assertNull(RoomLink("!abc").domain)
    }

    @Test
    fun `toMatrixTo round-trips`() {
        val link = RoomLink("!abc", listOf("a.org", "b.org"))
        assertEquals(link, RoomLink.parse(link.toMatrixTo()))
    }
}

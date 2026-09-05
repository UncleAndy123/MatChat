package org.matchat.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UserIdTest {
    @Test
    fun `domain is the part after the colon`() {
        assertEquals("example.org", UserId("@wayne:example.org").domain)
    }

    @Test
    fun `domain is empty when there is no colon`() {
        // A malformed address has no domain; the caller treats "" as unknown and
        // the shape check in newchat rejects it before any policy decision.
        assertEquals("", UserId("wayne").domain)
    }
}

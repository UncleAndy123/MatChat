package org.matchat.feature.newchat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AddressValidatorTest {
    @Test
    fun `accepts a well-formed matrix id`() {
        assertEquals("@wayne:example.org", AddressValidator.parse(" @wayne:example.org ")?.value)
    }

    @Test
    fun `rejects missing at, missing domain dot, and spaces`() {
        assertNull(AddressValidator.parse("wayne:example.org"))
        assertNull(AddressValidator.parse("@wayne:localhost"))
        assertNull(AddressValidator.parse("@way ne:example.org"))
        assertNull(AddressValidator.parse("@:example.org"))
    }
}

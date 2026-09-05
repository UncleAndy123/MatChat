package org.matchat.core.policy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.matchat.core.model.Contact
import org.matchat.core.model.UserId

class PolicyBundleParserTest {

    private fun parse(
        isEmpty: Boolean = false,
        pinned: String? = null,
        domains: String? = null,
        allowDm: Boolean = true,
        invite: String? = null,
        contacts: String? = null,
        media: Boolean = true,
    ) = PolicyBundleParser.parse(isEmpty, pinned, domains, allowDm, invite, contacts, media)

    @Test
    fun `empty bundle is unmanaged and fail-open`() {
        val policy = parse(isEmpty = true)
        assertFalse(policy.isManaged)
        assertNull(policy.allowedDomains)
        // Fail-open: every domain allowed when unmanaged (docs/adr/0005).
        assertTrue(policy.allows(UserId("@a:anywhere.example")))
    }

    @Test
    fun `managed with allowedDomains blocks other domains`() {
        val policy = parse(domains = "carpathianserver.org, example.org")
        assertTrue(policy.isManaged)
        assertEquals(listOf("carpathianserver.org", "example.org"), policy.allowedDomains)
        assertTrue(policy.allows(UserId("@wayne:example.org")))
        assertFalse(policy.allows(UserId("@x:elsewhere.net")))
    }

    @Test
    fun `managed with empty allowedDomains still allows everything`() {
        // Fail-open holds under management until an admin sets an explicit list.
        val policy = parse(domains = "")
        assertNull(policy.allowedDomains)
        assertTrue(policy.allows(UserId("@a:anywhere.example")))
    }

    @Test
    fun `invitePolicy autoAllowed is parsed, everything else is ask`() {
        assertEquals(InvitePolicy.AUTO_ALLOWED, parse(invite = "autoAllowed").invitePolicy)
        assertEquals(InvitePolicy.ASK, parse(invite = "ask").invitePolicy)
        assertEquals(InvitePolicy.ASK, parse(invite = null).invitePolicy)
    }

    @Test
    fun `contacts json parses to admin contacts`() {
        val json = """[{"name":"Wayne Zimmerman","address":"@wayne:example.org"}]"""
        val contacts = parse(contacts = json).adminContacts
        assertEquals(1, contacts.size)
        assertEquals(UserId("@wayne:example.org"), contacts[0].address)
        assertEquals("Wayne Zimmerman", contacts[0].name)
        assertEquals(Contact.Source.ADMIN, contacts[0].source)
    }

    @Test
    fun `malformed contacts json yields no contacts, not a crash`() {
        assertTrue(parse(contacts = "not json").adminContacts.isEmpty())
    }
}

package org.matchat.core.policy

import org.matchat.core.model.Contact
import org.matchat.core.model.UserId
import org.json.JSONArray

/**
 * Pure mapping from the raw managed-configuration values to a [Policy]. Kept
 * Android-free (takes primitives, not a Bundle) so it is fully unit-testable —
 * see PolicyBundleParserTest. Key schema and worked examples in docs/MDM.md §3.
 */
internal object PolicyBundleParser {
    /**
     * @param isEmpty true when there is no DPC / the restrictions bundle is empty.
     *        Drives fail-open: an empty bundle yields [Policy.UNMANAGED].
     */
    fun parse(
        isEmpty: Boolean,
        pinnedHomeserver: String?,
        allowedDomains: String?,
        allowDirectChat: Boolean,
        invitePolicy: String?,
        contactsJson: String?,
        mediaSend: Boolean,
    ): Policy {
        if (isEmpty) return Policy.UNMANAGED

        return Policy(
            isManaged = true,
            pinnedHomeserver = pinnedHomeserver?.trim()?.takeIf { it.isNotEmpty() },
            // Absent/empty allowedDomains on a managed device still means "all
            // domains" — the fail-open rule holds even under management until an
            // admin sets an explicit list (docs/MDM.md §4).
            allowedDomains = parseDomains(allowedDomains),
            allowDirectChat = allowDirectChat,
            invitePolicy = parseInvitePolicy(invitePolicy),
            adminContacts = parseContacts(contactsJson),
            mediaSend = mediaSend,
        )
    }

    private fun parseDomains(raw: String?): List<String>? =
        raw?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }

    private fun parseInvitePolicy(raw: String?): InvitePolicy =
        when (raw?.trim()) {
            "autoAllowed" -> InvitePolicy.AUTO_ALLOWED
            else -> InvitePolicy.ASK
        }

    /**
     * `contacts` is a JSON *string* (not bundle_array, which needs API 26 while
     * we support 24 — docs/MDM.md §3). A malformed value yields no contacts
     * rather than a crash; it is admin-supplied, never message content.
     */
    private fun parseContacts(json: String?): List<Contact> {
        val text = json?.trim().orEmpty()
        if (text.isEmpty()) return emptyList()
        return runCatching {
            val array = JSONArray(text)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val address = obj.optString("address").takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                Contact(
                    address = UserId(address),
                    name = obj.optString("name").takeIf { it.isNotBlank() },
                    source = Contact.Source.ADMIN,
                )
            }
        }.getOrDefault(emptyList())
    }
}

package org.matchat.core.policy

import org.matchat.core.model.Contact
import org.matchat.core.model.UserId

/**
 * The immutable lockdown policy for this device, mapped from the managed
 * configuration bundle. Everything outside :core:policy reads this object; it
 * never touches RestrictionsManager itself (ARCHITECTURE.md).
 *
 * `allowedDomains == null` means *unmanaged*, and unmanaged means *allow every
 * domain* — the fail-open decision (docs/adr/0005, AGENTS.md "Policy rules").
 * Never invert this "for safety": it would brick an unmanaged phone.
 */
data class Policy(
    val isManaged: Boolean,
    val pinnedHomeserver: String? = null,
    val allowedDomains: List<String>? = null,
    val allowDirectChat: Boolean = true,
    val invitePolicy: InvitePolicy = InvitePolicy.ASK,
    val adminContacts: List<Contact> = emptyList(),
    val mediaSend: Boolean = true,
) {
    /** True when the address's domain may be messaged. Fail-open by design. */
    fun allows(address: UserId): Boolean =
        allowedDomains?.contains(address.domain) ?: true

    companion object {
        /** The policy of an unmanaged phone: open, with no discovery. */
        val UNMANAGED = Policy(isManaged = false)
    }
}

enum class InvitePolicy {
    /** Every invitation waits on S18/S19 (the default). */
    ASK,

    /** Invitations from allowedDomains join silently; everything else still asks. */
    AUTO_ALLOWED,
}

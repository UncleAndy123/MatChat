package org.matchat.feature.invites

import org.matchat.core.model.ErrorText

/**
 * S19 Invitation detail. When [blocked], there is no Accept — only an explanation
 * naming the domain, and Decline. It is never silently hidden (PLAN.md §6.8).
 */
data class InviteDetailState(
    val name: String = "",
    val invitedBy: String = "",
    val address: String = "",
    val server: String = "",
    val isEncrypted: Boolean = true,
    val blocked: Boolean = false,
    val blockedDomain: String = "",
    val error: ErrorText? = null,
    val isBusy: Boolean = false,
)

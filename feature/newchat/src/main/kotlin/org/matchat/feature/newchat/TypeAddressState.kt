package org.matchat.feature.newchat

import org.matchat.core.model.ErrorText
import org.matchat.core.model.UserId

/** The confirmation step (S21): "Send to <name> (<address>)?". */
data class ConfirmTarget(
    val address: UserId,
    val name: String,
    val isEncrypted: Boolean,
    /** True when the profile did not resolve — "Start anyway" is offered, not blocked. */
    val unresolved: Boolean,
)

/**
 * S21 Type an address. The `@`/`:` are field furniture; the second segment
 * defaults to the last server used. Blocked domains are explained inline and go
 * no further (S22 semantics).
 */
data class TypeAddressState(
    val defaultServer: String = "",
    val error: ErrorText? = null,
    val blockedDomain: String? = null,
    val confirm: ConfirmTarget? = null,
    val isBusy: Boolean = false,
)

package org.matchat.core.model

/**
 * A user-facing error. Carries a string-resource key (resolved in the UI layer,
 * so :core:model stays Android-free) plus optional interpolation args and an
 * optional retry hint. Every error a user sees is one of these — no stack traces
 * reach a screen, no silent catches (ARCHITECTURE.md "Error handling").
 */
data class ErrorText(
    val key: Key,
    val args: List<String> = emptyList(),
    val retryable: Boolean = false,
) {
    enum class Key {
        NETWORK,
        SERVER_UNREACHABLE,
        BAD_CREDENTIALS,
        MALFORMED_ADDRESS,
        ADDRESS_NOT_FOUND,
        DOMAIN_NOT_ALLOWED,
        INVITE_GONE,
        SEND_FAILED,
        UNKNOWN,
    }
}

package org.matchat.core.model

/**
 * Someone the user already knows: an admin-pushed contact, or a local one
 * (already chatted with, or a recently typed address). This is never a directory
 * entry — the list is short by construction and has no search (AGENTS.md §0).
 */
data class Contact(
    val address: UserId,
    val name: String?,
    val source: Source,
) {
    enum class Source { ADMIN, LOCAL }
}

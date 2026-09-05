package org.matchat.feature.newchat

import org.matchat.core.model.UserId

/**
 * Local shape check for a Matrix id `@local:domain` (S21 step 1). Pure and
 * unit-tested; it rejects malformed input cheaply before any policy or network
 * step. This is validation, never discovery — it resolves nothing (AGENTS.md §0).
 */
internal object AddressValidator {

    // @local:domain — local part is non-empty, domain has at least one dot.
    private val PATTERN = Regex("^@[^\\s:@]+:[^\\s:@]+\\.[^\\s:@]+$")

    fun parse(raw: String): UserId? {
        val trimmed = raw.trim()
        return if (PATTERN.matches(trimmed)) UserId(trimmed) else null
    }
}

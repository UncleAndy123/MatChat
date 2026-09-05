package org.matchat.feature.onboarding

import org.matchat.core.model.ErrorText

/**
 * S3 Sign in. The homeserver is read-only when policy pins it (shown grey with a
 * lock glyph). loading/error live here, not in a separate code path (AGENTS.md §3).
 */
data class SignInState(
    val homeserver: String = "",
    val homeserverPinned: Boolean = false,
    val isSubmitting: Boolean = false,
    val error: ErrorText? = null,
)

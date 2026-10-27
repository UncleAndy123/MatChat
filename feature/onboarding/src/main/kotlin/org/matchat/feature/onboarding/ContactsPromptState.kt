package org.matchat.feature.onboarding

/**
 * S30 "Save contacts to this phone?" (UX-SPEC §S30): shown once after sign-in.
 * [refused] — the user said no to the contacts permission; the status line
 * says contacts stay in MatChat only.
 */
data class ContactsPromptState(
    val refused: Boolean = false,
)

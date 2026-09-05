package org.matchat.core.model

/** One emoji in the SAS comparison (S6): the glyph and its word label. */
data class SasEmoji(val symbol: String, val name: String)

/**
 * State of an emoji (SAS) device-verification attempt. The SDK drives the
 * transitions through a controller delegate; the UI renders whichever state is
 * current (UX-SPEC S6).
 */
sealed interface SasState {
    data object Idle : SasState
    /** Requested; waiting for the other device to accept. */
    data object Requested : SasState
    /** Compare these emojis with the other device. */
    data class Comparing(val emojis: List<SasEmoji>) : SasState
    data object Success : SasState
    /** Cancelled, declined, timed out, or failed — the UI offers a retry. */
    data object Cancelled : SasState
}

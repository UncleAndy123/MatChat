package org.matchat.feature.verification

import org.matchat.core.model.ErrorText

/** S7 recovery-key entry. loading/error/done are fields, not separate paths. */
data class VerificationState(
    val isSubmitting: Boolean = false,
    val error: ErrorText? = null,
)

sealed interface VerificationAction {
    /** Field value passed at submit time — render never reads it back. */
    data class Submit(val recoveryKey: String) : VerificationAction
    data object DismissError : VerificationAction
}

sealed interface VerificationNav {
    data object Verified : VerificationNav
}

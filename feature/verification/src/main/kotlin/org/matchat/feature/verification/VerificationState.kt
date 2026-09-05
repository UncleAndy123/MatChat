package org.matchat.feature.verification

import org.matchat.core.model.ErrorText
import org.matchat.core.model.SasEmoji

/** The phase the verification screen is showing (S5/S6/S7). */
enum class Phase { CHOOSE, WAITING_FOR_DEVICE, COMPARING, RECOVERY_KEY }

data class VerificationState(
    val phase: Phase = Phase.CHOOSE,
    val emojis: List<SasEmoji> = emptyList(),
    val busy: Boolean = false,
    val error: ErrorText? = null,
)

sealed interface VerificationAction {
    data object StartSas : VerificationAction
    data object ApproveSas : VerificationAction
    data object DeclineSas : VerificationAction
    data object ChooseRecovery : VerificationAction
    data class SubmitRecovery(val recoveryKey: String) : VerificationAction
    data object Cancel : VerificationAction
}

sealed interface VerificationNav {
    data object Verified : VerificationNav
}

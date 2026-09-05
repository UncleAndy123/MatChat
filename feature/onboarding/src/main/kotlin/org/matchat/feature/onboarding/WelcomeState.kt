package org.matchat.feature.onboarding

/**
 * S2 Welcome. The QR button is hidden when QR login is off — in v1 it is a v1.1
 * candidate (PLAN.md §7), so it defaults off and the password path is primary.
 */
data class WelcomeState(
    val qrEnabled: Boolean = false,
)

package org.matchat.feature.onboarding

sealed interface WelcomeAction {
    data object SignInWithQr : WelcomeAction
    data object SignInWithPassword : WelcomeAction
    data object Help : WelcomeAction
}

/** One-shot navigation out of Welcome. */
sealed interface WelcomeNav {
    data object Password : WelcomeNav
    data object Qr : WelcomeNav
    data object Help : WelcomeNav
}

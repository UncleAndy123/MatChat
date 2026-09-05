package org.matchat.feature.onboarding

sealed interface SignInAction {
    /** Field values are passed at submit time — render never reads them back. */
    data class Submit(val username: String, val password: String) : SignInAction
    data object DismissError : SignInAction
}

sealed interface SignInNav {
    data object Success : SignInNav
}

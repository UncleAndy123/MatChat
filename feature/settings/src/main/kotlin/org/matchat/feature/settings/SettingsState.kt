package org.matchat.feature.settings

/** S13 Settings. The Policy row states whether the phone is managed so a user who
 *  cannot message someone can find out why without calling anyone (UX-SPEC S13). */
data class SettingsState(
    val isManaged: Boolean = false,
)

sealed interface SettingsAction {
    data object OpenEncryption : SettingsAction
    data object OpenPolicy : SettingsAction
    data object OpenHelp : SettingsAction
    data object ConfirmSignOut : SettingsAction
}

sealed interface SettingsNav {
    data object Encryption : SettingsNav
    data object Policy : SettingsNav
    data object Help : SettingsNav
    data object SignedOut : SettingsNav
}

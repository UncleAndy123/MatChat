package org.matchat.feature.settings

/** S13 Settings. The Policy row states whether the phone is managed so a user who
 *  cannot message someone can find out why without calling anyone (UX-SPEC S13). */
data class SettingsState(
    val isManaged: Boolean = false,
    /** True once the launch auto-check found a newer release, so the Software
     *  update row can flag it without the user opening the screen. */
    val updateAvailable: Boolean = false,
)

sealed interface SettingsAction {
    data object OpenEncryption : SettingsAction
    data object OpenTextSize : SettingsAction
    data object OpenTheme : SettingsAction
    data object OpenAdvanced : SettingsAction
    data object OpenNotifications : SettingsAction
    data object OpenPolicy : SettingsAction
    data object OpenUpdate : SettingsAction
    data object OpenHelp : SettingsAction
    data object ConfirmSignOut : SettingsAction
}

sealed interface SettingsNav {
    data object Encryption : SettingsNav
    data object TextSize : SettingsNav
    data object Theme : SettingsNav
    data object Advanced : SettingsNav
    data object Notifications : SettingsNav
    data object Policy : SettingsNav
    data object Update : SettingsNav
    data object Help : SettingsNav
    data object SignedOut : SettingsNav
}

package org.matchat.feature.settings

/** S13 Settings. The Policy row states whether the phone is managed so a user who
 *  cannot message someone can find out why without calling anyone (UX-SPEC S13). */
data class SettingsState(
    val isManaged: Boolean = false,
    /** True once the launch auto-check found a newer release, so the Software
     *  update row can flag it without the user opening the screen. */
    val updateAvailable: Boolean = false,
    /** "Save contacts to phone" (UX-SPEC S13): on only while the user wants
     *  it and the contacts permission is still granted. */
    val phoneContactsOn: Boolean = false,
    /** The user just refused the contacts permission: the row's caption says
     *  "Contacts stay in MatChat only" (never a silent no-op). */
    val phoneContactsRefused: Boolean = false,
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

    /** CENTER on "Save contacts to phone": turn it on, or off. */
    data object TogglePhoneContacts : SettingsAction

    /** Android's answer to the contacts-permission request. */
    data class PhoneContactsPermissionResult(val granted: Boolean) : SettingsAction

    /** The screen is visible again: re-read whether the permission still holds. */
    data object Refresh : SettingsAction
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

    /** Ask Android for the contacts permission (one system dialog). */
    data object RequestContactsPermission : SettingsNav
}

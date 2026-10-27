package org.matchat.feature.onboarding

sealed interface ContactsPromptAction {
    /** CENTER on "Save to phone contacts". */
    data object SaveToPhone : ContactsPromptAction

    /** Android's answer to the contacts-permission request. */
    data class PermissionResult(val granted: Boolean) : ContactsPromptAction
}

/** One-shot navigation out of S30. */
sealed interface ContactsPromptNav {
    /** Ask Android for the contacts permission (one system dialog). */
    data object RequestPermission : ContactsPromptNav

    /** Turned on: close the prompt. */
    data object Done : ContactsPromptNav
}

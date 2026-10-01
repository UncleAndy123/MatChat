package org.matchat.feature.settings

/** Settings > Notifications: a toggle for whether the incoming-message
 *  notification fires at all, plus the sound it plays when it does. [sound]
 *  is Context-free (no ViewModel here touches Android APIs, same rule as
 *  every other screen this session) — resolving [SoundChoice.Custom]'s
 *  display title via RingtoneManager is the Fragment's job, in render(). */
data class NotificationsState(
    val enabled: Boolean = true,
    val sound: SoundChoice = SoundChoice.Default,
    /** Android 7–9: storage access was refused, so MatChat's bundled sounds
     *  (docs/SOUNDS.md) are missing from the picker. Shows a note. */
    val bundledSoundsNeedAccess: Boolean = false,
)

sealed interface SoundChoice {
    data object Default : SoundChoice
    data object Silent : SoundChoice
    data class Custom(val uri: String) : SoundChoice
}

sealed interface NotificationsAction {
    data object ToggleEnabled : NotificationsAction
    data class SelectSound(val uri: String?) : NotificationsAction

    /** CENTER on the Sound row. */
    data object OpenSoundPicker : NotificationsAction

    /** The answer to the storage-permission request (Android 7–9). */
    data class StoragePermissionResult(val granted: Boolean) : NotificationsAction
}

/** One-shot navigation out of S26. */
sealed interface NotificationsNav {
    /** Ask for storage access first, to copy the bundled sounds out. */
    data object RequestStoragePermission : NotificationsNav
    data object OpenPicker : NotificationsNav
}

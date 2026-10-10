package org.matchat.core.testing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.matchat.core.ui.prefs.AccentColor
import org.matchat.core.ui.prefs.TextSizePreference
import org.matchat.core.ui.prefs.ThemeMode
import org.matchat.core.ui.prefs.UserPreferences

/** An in-memory [UserPreferences] for ViewModel tests. */
class FakeUserPreferences(
    initialThemeMode: ThemeMode = ThemeMode.LIGHT,
    initialAccentColor: AccentColor = AccentColor.GREEN,
    initialTextSize: TextSizePreference = TextSizePreference.NORMAL,
    initialSoftkeysSwapped: Boolean = false,
    initialNotificationsEnabled: Boolean = true,
    initialNotificationSoundUri: String? = null,
    initialNotificationChannelVersion: Int = 0,
    initialCoverMessageHidden: Boolean = false,
) : UserPreferences {

    private val themeModeState = MutableStateFlow(initialThemeMode)
    override val themeMode: StateFlow<ThemeMode> = themeModeState

    private val accentColorState = MutableStateFlow(initialAccentColor)
    override val accentColor: StateFlow<AccentColor> = accentColorState

    private val textSizeState = MutableStateFlow(initialTextSize)
    override val textSize: StateFlow<TextSizePreference> = textSizeState

    private val softkeysSwappedState = MutableStateFlow(initialSoftkeysSwapped)
    override val softkeysSwapped: StateFlow<Boolean> = softkeysSwappedState

    private val notificationsEnabledState = MutableStateFlow(initialNotificationsEnabled)
    override val notificationsEnabled: StateFlow<Boolean> = notificationsEnabledState

    private val notificationSoundUriState = MutableStateFlow(initialNotificationSoundUri)
    override val notificationSoundUri: StateFlow<String?> = notificationSoundUriState

    private val notificationChannelVersionState = MutableStateFlow(initialNotificationChannelVersion)
    override val notificationChannelVersion: StateFlow<Int> = notificationChannelVersionState

    private val coverMessageHiddenState = MutableStateFlow(initialCoverMessageHidden)
    override val coverMessageHidden: StateFlow<Boolean> = coverMessageHiddenState

    override suspend fun setThemeMode(mode: ThemeMode) {
        themeModeState.value = mode
    }

    override suspend fun setAccentColor(color: AccentColor) {
        accentColorState.value = color
    }

    override suspend fun setTextSize(size: TextSizePreference) {
        textSizeState.value = size
    }

    override suspend fun setSoftkeysSwapped(swapped: Boolean) {
        softkeysSwappedState.value = swapped
    }

    override suspend fun setNotificationsEnabled(enabled: Boolean) {
        notificationsEnabledState.value = enabled
    }

    override suspend fun setCoverMessageHidden(hidden: Boolean) {
        coverMessageHiddenState.value = hidden
    }

    override suspend fun setNotificationSoundUri(uri: String?) {
        notificationSoundUriState.value = uri
        notificationChannelVersionState.value = notificationChannelVersionState.value + 1
    }
}

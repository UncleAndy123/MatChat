package org.matchat.core.ui.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Plain [SharedPreferences]-backed [UserPreferences]. Falls back to LIGHT /
 *  GREEN for a value that's missing, or that no longer names an enum
 *  constant (an old build's preference on a downgrade). */
@Singleton
internal class SharedPreferencesUserPreferences @Inject constructor(
    @ApplicationContext context: Context,
) : UserPreferences {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val themeModeState = MutableStateFlow(readEnum(KEY_THEME_MODE, ThemeMode.LIGHT))
    override val themeMode: StateFlow<ThemeMode> = themeModeState

    private val accentColorState = MutableStateFlow(readEnum(KEY_ACCENT_COLOR, AccentColor.RUST))
    override val accentColor: StateFlow<AccentColor> = accentColorState

    private val textSizeState = MutableStateFlow(readEnum(KEY_TEXT_SIZE, TextSizePreference.NORMAL))
    override val textSize: StateFlow<TextSizePreference> = textSizeState

    private val softkeysSwappedState = MutableStateFlow(prefs.getBoolean(KEY_SOFTKEYS_SWAPPED, false))
    override val softkeysSwapped: StateFlow<Boolean> = softkeysSwappedState

    private val notificationsEnabledState = MutableStateFlow(prefs.getBoolean(KEY_NOTIFICATIONS_ENABLED, true))
    override val notificationsEnabled: StateFlow<Boolean> = notificationsEnabledState

    private val notificationSoundUriState = MutableStateFlow(prefs.getString(KEY_NOTIFICATION_SOUND_URI, null))
    override val notificationSoundUri: StateFlow<String?> = notificationSoundUriState

    private val notificationChannelVersionState =
        MutableStateFlow(prefs.getInt(KEY_NOTIFICATION_CHANNEL_VERSION, 0))
    override val notificationChannelVersion: StateFlow<Int> = notificationChannelVersionState

    private val coverMessageHiddenState = MutableStateFlow(prefs.getBoolean(KEY_COVER_MESSAGE_HIDDEN, false))
    override val coverMessageHidden: StateFlow<Boolean> = coverMessageHiddenState

    override suspend fun setThemeMode(mode: ThemeMode) {
        prefs.edit { putString(KEY_THEME_MODE, mode.name) }
        themeModeState.value = mode
    }

    override suspend fun setAccentColor(color: AccentColor) {
        prefs.edit { putString(KEY_ACCENT_COLOR, color.name) }
        accentColorState.value = color
    }

    override suspend fun setTextSize(size: TextSizePreference) {
        prefs.edit { putString(KEY_TEXT_SIZE, size.name) }
        textSizeState.value = size
    }

    override suspend fun setSoftkeysSwapped(swapped: Boolean) {
        prefs.edit { putBoolean(KEY_SOFTKEYS_SWAPPED, swapped) }
        softkeysSwappedState.value = swapped
    }

    override suspend fun setNotificationsEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_NOTIFICATIONS_ENABLED, enabled) }
        notificationsEnabledState.value = enabled
    }

    override suspend fun setCoverMessageHidden(hidden: Boolean) {
        prefs.edit { putBoolean(KEY_COVER_MESSAGE_HIDDEN, hidden) }
        coverMessageHiddenState.value = hidden
    }

    override suspend fun setNotificationSoundUri(uri: String?) {
        val nextVersion = notificationChannelVersionState.value + 1
        prefs.edit {
            putString(KEY_NOTIFICATION_SOUND_URI, uri)
            putInt(KEY_NOTIFICATION_CHANNEL_VERSION, nextVersion)
        }
        notificationSoundUriState.value = uri
        notificationChannelVersionState.value = nextVersion
    }

    private inline fun <reified T : Enum<T>> readEnum(key: String, default: T): T =
        prefs.getString(key, null)?.let { stored ->
            enumValues<T>().firstOrNull { it.name == stored }
        } ?: default

    private companion object {
        const val PREFS_NAME = "user_preferences"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_ACCENT_COLOR = "accent_color"
        const val KEY_TEXT_SIZE = "text_size"
        const val KEY_SOFTKEYS_SWAPPED = "softkeys_swapped"
        const val KEY_NOTIFICATIONS_ENABLED = "notifications_enabled"
        const val KEY_NOTIFICATION_SOUND_URI = "notification_sound_uri"
        const val KEY_NOTIFICATION_CHANNEL_VERSION = "notification_channel_version"
        const val KEY_COVER_MESSAGE_HIDDEN = "cover_message_hidden"
    }
}

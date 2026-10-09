package org.matchat.core.ui.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.matchat.core.model.RoomId
import org.matchat.core.model.notify.RoomNotificationSounds
import org.matchat.core.model.notify.RoomSound
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [SharedPreferences]-backed per-room sounds, in their own file. Two keys per
 * room: the sound uri (removed when the room goes back to the app sound) and
 * a version that only ever grows. The version outlives the override on
 * purpose: Android restores a deleted notification channel's old settings if
 * a channel with the same id is created again, so a room that goes back to a
 * custom sound must get a channel id it has never used.
 */
@Singleton
internal class SharedPreferencesRoomNotificationSounds @Inject constructor(
    @ApplicationContext context: Context,
) : RoomNotificationSounds {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val state = MutableStateFlow(read())
    override val overrides: StateFlow<Map<RoomId, RoomSound>> = state

    override suspend fun set(roomId: RoomId, uri: String?) {
        val nextVersion = prefs.getInt(KEY_VERSION + roomId.value, 0) + 1
        prefs.edit {
            putInt(KEY_VERSION + roomId.value, nextVersion)
            if (uri == null) remove(KEY_URI + roomId.value) else putString(KEY_URI + roomId.value, uri)
        }
        state.value = read()
    }

    private fun read(): Map<RoomId, RoomSound> = prefs.all.mapNotNull { (key, value) ->
        if (!key.startsWith(KEY_URI) || value !is String) return@mapNotNull null
        val room = key.removePrefix(KEY_URI)
        RoomId(room) to RoomSound(value, prefs.getInt(KEY_VERSION + room, 0))
    }.toMap()

    private companion object {
        const val PREFS_NAME = "room_notification_sounds"
        const val KEY_URI = "uri:"
        const val KEY_VERSION = "ver:"
    }
}

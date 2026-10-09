package org.matchat.core.model.notify

import kotlinx.coroutines.flow.StateFlow
import org.matchat.core.model.RoomId

/** Stored sound value meaning "no sound", as distinct from "no choice made".
 *  Android's ringtone picker returns null for both "Silent" and "cancelled",
 *  so the app needs its own marker. Same value as `:core:ui`'s
 *  `SILENT_NOTIFICATION_SOUND`, which now points here. */
const val SILENT_SOUND: String = "matchat:silent"

/**
 * A room's own notification sound, overriding the app-wide one (UX-SPEC S12).
 *
 * @property uri the sound's URI, or [SILENT_SOUND].
 * @property version bumped on every change: on Android 8+ a notification
 *   channel's sound can't be changed once created, so each change gets a new
 *   channel (MessageNotifier).
 */
data class RoomSound(val uri: String, val version: Int)

/**
 * Per-room notification sounds. Rooms without an entry use the app-wide sound
 * from Settings > Notifications. Pure Kotlin so ViewModels can depend on it.
 */
interface RoomNotificationSounds {
    val overrides: StateFlow<Map<RoomId, RoomSound>>

    /** [uri] null removes the room's override (back to the app sound). */
    suspend fun set(roomId: RoomId, uri: String?)
}

/** What the Room info row shows (S12). */
sealed interface RoomSoundChoice {
    /** No override: the app-wide sound. */
    data object AppDefault : RoomSoundChoice
    data object Silent : RoomSoundChoice
    data class Custom(val uri: String) : RoomSoundChoice

    companion object {
        fun of(override: RoomSound?): RoomSoundChoice = when (override?.uri) {
            null -> AppDefault
            SILENT_SOUND -> Silent
            else -> Custom(override.uri)
        }
    }
}

/** Turns the system ringtone picker's result into a room override. */
object RoomSoundPick {
    /**
     * @param pickedUri the picker's EXTRA_RINGTONE_PICKED_URI, as a string.
     * @param cancelled the user backed out of the picker.
     * @param current the room's current override uri (null = app sound).
     * @param systemDefaultUri the picker's "Default" entry
     *   (Settings.System.DEFAULT_NOTIFICATION_URI), which for a room means
     *   "use the app's sound".
     * @return the new override uri, or null for "use the app's sound".
     */
    fun resolve(pickedUri: String?, cancelled: Boolean, current: String?, systemDefaultUri: String): String? = when {
        cancelled -> current
        pickedUri == null -> SILENT_SOUND
        pickedUri == systemDefaultUri -> null
        else -> pickedUri
    }
}

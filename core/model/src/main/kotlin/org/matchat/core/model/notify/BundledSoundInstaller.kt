package org.matchat.core.model.notify

/**
 * Copies the sounds bundled in the APK (`app/src/main/res/raw/`, see
 * docs/SOUNDS.md) into the phone's Notifications sound list, so the system
 * sound picker offers them. Implemented in `:app`; screens with a sound picker
 * call [install] before opening it.
 */
interface BundledSoundInstaller {
    /** True on Android 7–9 when storage access hasn't been granted yet: the
     *  screen must ask for it before [install] can copy anything. */
    val needsStoragePermission: Boolean

    /** Idempotent; a no-op when there is nothing new to copy or no access. */
    suspend fun install()
}

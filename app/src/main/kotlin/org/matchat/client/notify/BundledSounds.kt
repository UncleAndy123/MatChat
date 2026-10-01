package org.matchat.client.notify

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.util.TypedValue
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.matchat.client.R
import org.matchat.core.model.notify.BundledSoundInstaller
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Copies the notification sounds bundled in `app/src/main/res/raw/` (see
 * docs/SOUNDS.md) into the phone's Notifications sound list, under a "MatChat"
 * folder, so Android's own sound picker offers them in Settings > Notifications
 * and Room info. The picker can't look inside an APK, which is why they are
 * copied out at all.
 *
 * - Android 10+: a MediaStore insert into `Notifications/MatChat/`. An app may
 *   always add its own media, so no permission is needed.
 * - Android 7–9: a file in the public Notifications folder, then a media scan.
 *   That needs WRITE_EXTERNAL_STORAGE, which the sound screens request the first
 *   time a picker is opened ([needsStoragePermission]).
 *
 * Runs once per app version (new sounds in an update get copied after it), and
 * skips any sound already there, so it is cheap to call before every picker.
 */
@Singleton
class BundledSounds @Inject constructor(
    @ApplicationContext private val context: Context,
) : BundledSoundInstaller {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override val needsStoragePermission: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED

    override suspend fun install() = withContext(Dispatchers.IO) {
        if (needsStoragePermission) return@withContext
        val version = appVersion()
        if (prefs.getLong(KEY_DONE_VERSION, -1L) == version) return@withContext
        val results = bundled().map { sound -> runCatching { copy(sound) }.onFailure { logFailure(sound, it) } }
        if (results.all { it.isSuccess }) prefs.edit { putLong(KEY_DONE_VERSION, version) }
    }

    private data class Sound(val resId: Int, val fileName: String) {
        val title: String
            get() = fileName.substringBeforeLast('.').replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    /** Every resource in `res/raw/`. Read by reflection so the build still
     *  works when the folder is empty (then there is no R.raw class at all). */
    private fun bundled(): List<Sound> {
        val raw = runCatching { Class.forName("${R::class.java.name}\$raw") }.getOrNull() ?: return emptyList()
        return raw.fields.mapNotNull { field ->
            runCatching {
                val id = field.getInt(null)
                val value = TypedValue().also { context.resources.getValue(id, it, true) }
                Sound(id, value.string.toString().substringAfterLast('/'))
            }.getOrNull()
        }
    }

    private fun copy(sound: Sound) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) copyToMediaStore(sound) else copyToPublicFolder(sound)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun copyToMediaStore(sound: Sound) {
        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val exists = resolver.query(
            collection,
            arrayOf(MediaStore.Audio.Media._ID),
            "${MediaStore.Audio.Media.RELATIVE_PATH} = ? AND ${MediaStore.Audio.Media.DISPLAY_NAME} = ?",
            arrayOf(RELATIVE_PATH, sound.fileName),
            null,
        )?.use { it.count > 0 } ?: false
        if (exists) return
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, sound.fileName)
            put(MediaStore.Audio.Media.TITLE, sound.title)
            put(MediaStore.Audio.Media.MIME_TYPE, mimeOf(sound.fileName))
            put(MediaStore.Audio.Media.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Audio.Media.IS_NOTIFICATION, 1)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(collection, values)) { "MediaStore insert returned null" }
        try {
            resolver.openOutputStream(uri).use { out ->
                context.resources.openRawResource(sound.resId).use { it.copyTo(requireNotNull(out)) }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    @Suppress("DEPRECATION") // the only way to reach the public folder before API 29
    private fun copyToPublicFolder(sound: Sound) {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_NOTIFICATIONS), FOLDER)
        dir.mkdirs()
        val file = File(dir, sound.fileName)
        if (file.exists() && file.length() > 0) return
        context.resources.openRawResource(sound.resId).use { input -> file.outputStream().use { input.copyTo(it) } }
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mimeOf(sound.fileName)), null)
    }

    private fun mimeOf(fileName: String): String = when (fileName.substringAfterLast('.').lowercase()) {
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/x-wav"
        else -> "audio/ogg"
    }

    private fun appVersion(): Long = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
    }.getOrDefault(0L)

    private fun logFailure(sound: Sound, e: Throwable) {
        // File names only — they are our own bundled resources, not user data.
        Log.w(TAG, "could not add bundled sound ${sound.fileName}: ${e.message}")
    }

    private companion object {
        const val TAG = "BundledSounds"
        const val PREFS_NAME = "bundled_sounds"
        const val KEY_DONE_VERSION = "done_version"
        const val FOLDER = "MatChat"
        const val RELATIVE_PATH = "Notifications/$FOLDER/"
    }
}

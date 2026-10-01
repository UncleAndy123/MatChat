package org.matchat.feature.timeline

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Saves downloaded media bytes to the device's public storage: images into the
 * gallery (Pictures/MatChat), any attachment into Downloads/MatChat. On API 29+
 * this uses scoped-storage MediaStore inserts (no permission). On API 24–28 it
 * writes straight to the public directory (needs WRITE_EXTERNAL_STORAGE, declared
 * `maxSdkVersion="28"` in the manifest) and asks the media scanner to index it.
 *
 * All disk/resolver work is blocking — call from a background dispatcher. Returns
 * false on any failure rather than throwing, so the caller shows one plain
 * "couldn't save" message (AGENTS.md §9: no silent failure, no crash).
 */
internal object MediaSaver {

    /** Where a save lands — picks the MediaStore collection and public folder. */
    enum class Target { GALLERY, DOWNLOADS }

    private const val SUBDIR = "MatChat"
    private const val DEFAULT_IMAGE_MIME = "image/jpeg"
    private const val DEFAULT_FILE_MIME = "application/octet-stream"
    private const val TAG = "MediaSaver"

    /** True when saving needs the legacy WRITE_EXTERNAL_STORAGE permission first. */
    fun needsLegacyPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    /** The real image MIME sniffed from [bytes] (gallery needs an image type),
     *  falling back to image/jpeg when the header can't be read. */
    fun imageMime(bytes: ByteArray): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return bounds.outMimeType ?: DEFAULT_IMAGE_MIME
    }

    fun save(
        context: Context,
        target: Target,
        displayName: String,
        mimeType: String?,
        bytes: ByteArray,
    ): Boolean {
        val name = displayName.substringAfterLast('/').ifBlank { "attachment" }
        val mime = mimeType?.takeIf { it.isNotBlank() }
            ?: if (target == Target.GALLERY) DEFAULT_IMAGE_MIME else DEFAULT_FILE_MIME
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(context, target, name, mime, bytes)
            } else {
                saveLegacy(context, target, name, mime, bytes)
            }
        }.onFailure { android.util.Log.w(TAG, "save failed: ${it.message}") }.getOrDefault(false)
    }

    /** API 29+: insert a pending row, stream the bytes, then clear the flag. */
    private fun saveViaMediaStore(
        context: Context,
        target: Target,
        name: String,
        mime: String,
        bytes: ByteArray,
    ): Boolean {
        val resolver = context.contentResolver
        val collection = when (target) {
            Target.GALLERY -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            Target.DOWNLOADS -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
        }
        val relativeRoot = when (target) {
            Target.GALLERY -> Environment.DIRECTORY_PICTURES
            Target.DOWNLOADS -> Environment.DIRECTORY_DOWNLOADS
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$relativeRoot/$SUBDIR")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: return false
        resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return true
    }

    /** API 24–28: write into the public folder and let the scanner index it so it
     *  shows up in the gallery / file manager. */
    @Suppress("DEPRECATION")
    private fun saveLegacy(
        context: Context,
        target: Target,
        name: String,
        mime: String,
        bytes: ByteArray,
    ): Boolean {
        val root = when (target) {
            Target.GALLERY -> Environment.DIRECTORY_PICTURES
            Target.DOWNLOADS -> Environment.DIRECTORY_DOWNLOADS
        }
        val dir = File(Environment.getExternalStoragePublicDirectory(root), SUBDIR).apply { mkdirs() }
        val file = uniqueFile(dir, name)
        file.writeBytes(bytes)
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mime), null)
        return true
    }

    /** `name`, or `name (1)`, `name (2)`… when the file already exists, so a
     *  second save of the same image doesn't clobber the first. Internal (not
     *  private) so the pure File logic is unit-testable without a device. */
    internal fun uniqueFile(dir: File, name: String): File {
        val candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var i = 1
        while (true) {
            val next = File(dir, "$base ($i)$ext")
            if (!next.exists()) return next
            i++
        }
    }
}

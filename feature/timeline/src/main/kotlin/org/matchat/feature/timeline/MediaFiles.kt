package org.matchat.feature.timeline

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

/**
 * Local media helpers for the timeline: downsample image bytes to the QVGA screen
 * (no Coil, to keep the APK small on a 2 GB device — PLAN.md §4), cache attachment
 * bytes, and hand them to the system viewer/player via a FileProvider content URI.
 */
internal object MediaFiles {

    /** Decode [bytes] with an inSampleSize so the result fits within [maxPx]. */
    fun decodeSampled(bytes: ByteArray, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        var w = bounds.outWidth
        var h = bounds.outHeight
        while (w / 2 >= maxPx || h / 2 >= maxPx) {
            sample *= 2
            w /= 2
            h /= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) }.getOrNull()
    }

    /** The user-facing name of a content [uri] (DISPLAY_NAME), or a fallback. */
    fun displayName(context: Context, uri: Uri): String {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        runCatching {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx)?.let { return it }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"
    }

    /** Appends an extension derived from [mimeType] when [name] has none. A system
     *  viewer keyed on extension (common on locked-down builds) needs one. */
    fun ensureExtension(name: String, mimeType: String?): String {
        if (name.substringAfterLast('/').contains('.')) return name
        val ext = mimeType?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        return if (ext.isNullOrBlank()) name else "$name.$ext"
    }

    /** A fresh cache/media file for a camera capture (under the FileProvider path). */
    fun newCameraFile(context: Context): File {
        val dir = File(context.cacheDir, "media").apply { mkdirs() }
        return File(dir, "camera_${System.currentTimeMillis()}.jpg")
    }

    /** Write bytes to the app cache under media/, returning the file. */
    fun writeToCache(context: Context, filename: String, bytes: ByteArray): File {
        val dir = File(context.cacheDir, "media").apply { mkdirs() }
        val safe = filename.substringAfterLast('/').ifBlank { "attachment" }
        return File(dir, safe).apply { writeBytes(bytes) }
    }

    /** Open [file] in the system viewer via a chooser; [onNoApp] runs if nothing
     *  handles it. Falls back from the specific MIME to a generic one, since some
     *  locked-down builds register a handler only for wildcard types. */
    fun open(context: Context, file: File, mimeType: String?, onNoApp: () -> Unit) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        if (startView(context, uri, mimeType ?: "*/*")) return
        if (mimeType != null && startView(context, uri, "*/*")) return
        android.util.Log.w("MediaFiles", "no app to open ${mimeType ?: "file"}")
        onNoApp()
    }

    private fun startView(context: Context, uri: Uri, type: String): Boolean {
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, type)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // resolveActivity returns null when nothing on the device handles the type;
        // a chooser would otherwise pop an empty "no apps" sheet without throwing.
        if (view.resolveActivity(context.packageManager) == null) return false
        val chooser = Intent.createChooser(view, null).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(chooser)
            true
        } catch (e: ActivityNotFoundException) {
            android.util.Log.w("MediaFiles", "no handler for $type: ${e.message}")
            false
        }
    }
}

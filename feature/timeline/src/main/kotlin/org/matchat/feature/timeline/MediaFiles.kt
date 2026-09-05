package org.matchat.feature.timeline

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
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

    /** Write bytes to the app cache under media/, returning the file. */
    fun writeToCache(context: Context, filename: String, bytes: ByteArray): File {
        val dir = File(context.cacheDir, "media").apply { mkdirs() }
        val safe = filename.substringAfterLast('/').ifBlank { "attachment" }
        return File(dir, safe).apply { writeBytes(bytes) }
    }

    /** Open [file] in the system viewer/player; [onNoApp] runs if nothing handles it. */
    fun open(context: Context, file: File, mimeType: String?, onNoApp: () -> Unit) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            android.util.Log.w("MediaFiles", "no app to open ${mimeType ?: "file"}: ${e.message}")
            onNoApp()
        }
    }
}

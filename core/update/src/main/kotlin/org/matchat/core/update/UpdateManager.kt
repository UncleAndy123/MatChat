package org.matchat.core.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one owner of update state (Settings > Software update). Checks the latest
 * GitHub Release, downloads its universal APK, and hands it to the system
 * installer. Plain HttpURLConnection + org.json, mirroring
 * :core:rtc's HttpTokenService — no extra HTTP client pulled in.
 *
 * A [Singleton] with one [state] StateFlow: [MatChatApp]/MainActivity kicks off
 * an auto-check on launch, and the Software-update screen renders and drives the
 * same instance, so the result is shared rather than re-fetched per screen.
 */
@Singleton
class UpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _state = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val state: StateFlow<UpdateStatus> = _state.asStateFlow()

    /** The running app's versionName (BuildConfig is disabled app-wide). */
    fun currentVersion(): String = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty()

    /**
     * Checks GitHub for a newer release. Auto-checks (launch) pass
     * [force] = false and are throttled to [CHECK_THROTTLE_MS] so a cold start
     * doesn't hit the network every time; a manual check passes [force] = true.
     * Never overwrites an in-progress download.
     */
    suspend fun checkForUpdate(force: Boolean) {
        when (_state.value) {
            is UpdateStatus.Downloading, is UpdateStatus.Downloaded -> return
            else -> Unit
        }
        if (!force && !dueForAutoCheck()) return

        _state.value = UpdateStatus.Checking
        val result = withContext(Dispatchers.IO) { runCatching { fetchLatest() } }
        markChecked()
        result
            .onFailure {
                val error = if (it is NoCompatibleAssetException) UpdateError.NO_ASSET else UpdateError.NETWORK
                Log.w(TAG, "update check failed ($error): ${it.message}")
                _state.value = UpdateStatus.Failed(error)
            }
            .onSuccess { info ->
                _state.value = when {
                    info == null -> {
                        Log.i(TAG, "no published release found")
                        UpdateStatus.Failed(UpdateError.NO_RELEASE)
                    }
                    VersionCompare.isNewer(info.latestVersion, info.currentVersion) ->
                        UpdateStatus.Available(info)
                    else -> UpdateStatus.UpToDate
                }
            }
    }

    /** GET releases/latest. Returns null when GitHub reports no release (404);
     *  throws [NoCompatibleAssetException] when a release exists but carries no
     *  APK this device can install; other failures propagate (→ NETWORK). */
    private fun fetchLatest(): UpdateInfo? {
        val url = URL("https://api.github.com/repos/$OWNER/$REPO/releases/latest")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "MatChat-Updater")
        }
        return try {
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) return null
            if (code !in 200..299) error("releases/latest HTTP $code")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = json.optString("tag_name").ifBlank { return null }
            val asset = pickApk(json.optJSONArray("assets")) ?: run {
                Log.w(TAG, "release $tag has no installable APK for ${Build.SUPPORTED_ABIS.joinToString()}")
                throw NoCompatibleAssetException()
            }
            UpdateInfo(
                currentVersion = currentVersion(),
                latestVersion = tag,
                downloadUrl = asset.optString("browser_download_url"),
                notes = json.optString("body").trim(),
                sizeBytes = asset.optLong("size"),
            )
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Picks the APK asset to install: the split matching this device's ABI first
     * (most-preferred ABI first) — that is the smallest download that runs here,
     * far smaller than the all-ABI universal APK — then the universal APK as a
     * fallback (installs on any ABI, e.g. an odd ABI with no split), then the sole
     * APK when only one is attached. Null when nothing fits.
     */
    private fun pickApk(assets: JSONArray?): JSONObject? {
        if (assets == null) return null
        val apks = (0 until assets.length())
            .map { assets.getJSONObject(it) }
            .filter { it.optString("name").endsWith(".apk", ignoreCase = true) }
        if (apks.isEmpty()) return null
        for (abi in Build.SUPPORTED_ABIS) {
            apks.firstOrNull { it.optString("name").contains(abi, ignoreCase = true) }
                ?.let { return it }
        }
        apks.firstOrNull { it.optString("name").contains("universal", ignoreCase = true) }
            ?.let { return it }
        return apks.singleOrNull()
    }

    /** A release exists but has no APK installable on this device's ABI. */
    private class NoCompatibleAssetException : Exception()

    /**
     * Downloads the [UpdateInfo.downloadUrl] APK to cache, streaming progress
     * into [state]. Only valid from an [UpdateStatus.Available] (or a retried
     * [UpdateStatus.Failed]) state; no-ops otherwise.
     */
    suspend fun download() {
        val info = when (val s = _state.value) {
            is UpdateStatus.Available -> s.info
            is UpdateStatus.Failed -> return
            else -> return
        }
        _state.value = UpdateStatus.Downloading(info, percent = 0)
        val result = withContext(Dispatchers.IO) { runCatching { streamApk(info) } }
        result
            .onFailure {
                Log.w(TAG, "download failed: ${it.message}")
                _state.value = UpdateStatus.Failed(UpdateError.DOWNLOAD)
            }
            .onSuccess { apk -> _state.value = UpdateStatus.Downloaded(info, apk) }
    }

    private suspend fun streamApk(info: UpdateInfo): File {
        val dir = File(context.cacheDir, UPDATES_DIR).apply {
            mkdirs()
            listFiles()?.forEach { it.delete() } // keep only the current download
        }
        val apk = File(dir, "matchat-${info.latestVersion}.apk")
        val conn = (URL(info.downloadUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true // GitHub redirects to its asset CDN
            setRequestProperty("User-Agent", "MatChat-Updater")
        }
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            val total = if (conn.contentLength > 0) conn.contentLength.toLong() else info.sizeBytes
            var read = 0L
            conn.inputStream.use { input ->
                apk.outputStream().use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        read += n
                        val pct = if (total > 0) ((read * 100) / total).toInt().coerceIn(0, 100) else -1
                        _state.value = UpdateStatus.Downloading(info, pct)
                    }
                }
            }
            return apk
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Hands the downloaded APK to the system installer. On API 26+ the user must
     * have granted "install unknown apps" to MatChat; when they haven't, this
     * opens that system screen instead and returns false so the caller can tell
     * the user to grant it and press Install again. Requires the update APK to be
     * signed with the same key as the running app (release.yml's RELEASE_KEYSTORE);
     * a debug build cannot self-update to a release APK.
     */
    fun installDownloaded(): Boolean {
        val apk = (_state.value as? UpdateStatus.Downloaded)?.apk ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val settings = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(settings) }
                .onFailure { _state.value = UpdateStatus.Failed(UpdateError.INSTALL) }
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val install = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return runCatching {
            context.startActivity(install)
            true
        }
            .getOrElse {
                Log.w(TAG, "install intent failed: ${it.message}")
                _state.value = UpdateStatus.Failed(UpdateError.INSTALL)
                false
            }
    }

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun dueForAutoCheck(): Boolean =
        System.currentTimeMillis() - prefs().getLong(KEY_LAST_CHECK, 0L) >= CHECK_THROTTLE_MS
    private fun markChecked() = prefs().edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()

    private companion object {
        // The published repo the release workflow tags and uploads APKs to.
        const val OWNER = "uncleandy123"
        const val REPO = "matchat"

        const val UPDATES_DIR = "updates"
        const val PREFS = "matchat.update"
        const val KEY_LAST_CHECK = "last_check_ms"
        const val CHECK_THROTTLE_MS = 6L * 60 * 60 * 1000 // auto-check at most every 6h
        const val TIMEOUT_MS = 30_000
        const val DOWNLOAD_BUFFER = 16 * 1024
        const val TAG = "UpdateManager"
    }
}

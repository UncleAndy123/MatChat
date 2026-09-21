package org.matchat.core.update

import java.io.File

/** A newer release found on GitHub, with what the UI and installer need. */
data class UpdateInfo(
    val currentVersion: String,
    val latestVersion: String,
    /** The universal APK asset's download URL (release.yml publishes one). */
    val downloadUrl: String,
    /** The release body (Markdown), shown verbatim as "what's new". */
    val notes: String,
    /** Asset size in bytes, 0 when GitHub didn't report it. */
    val sizeBytes: Long,
)

/** Why a check or download gave up — mapped to a plain user-facing line. */
enum class UpdateError {
    /** Couldn't reach GitHub (offline, or the corporate proxy's CA is not
     *  trusted on this device — HttpURLConnection uses the system trust store,
     *  so a PKIX failure surfaces here; see gradle.properties / PLAN.md §11). */
    NETWORK,

    /** Reached GitHub but there's no published release yet. */
    NO_RELEASE,

    /** There's a release, but it carries no APK this phone can install
     *  (no universal APK and no split matching the device ABI). */
    NO_ASSET,

    /** The download did not complete. */
    DOWNLOAD,

    /** Couldn't hand the APK to the system installer. */
    INSTALL,
}

/**
 * The single observable state of the updater. [UpdateManager] owns one
 * StateFlow of this; the Settings screen and the Software-update screen both
 * render off it, so an auto-check on launch and a manual check share one result.
 */
sealed interface UpdateStatus {
    /** Nothing checked yet this process. */
    data object Idle : UpdateStatus

    /** A check is in flight. */
    data object Checking : UpdateStatus

    /** Checked; the running build is the latest. */
    data object UpToDate : UpdateStatus

    /** A newer release is available to download. */
    data class Available(val info: UpdateInfo) : UpdateStatus

    /** Downloading the APK; [percent] is -1 when total size is unknown. */
    data class Downloading(val info: UpdateInfo, val percent: Int) : UpdateStatus

    /** APK on disk, ready to hand to the system installer. */
    data class Downloaded(val info: UpdateInfo, val apk: File) : UpdateStatus

    /** A check or download failed. */
    data class Failed(val error: UpdateError) : UpdateStatus
}

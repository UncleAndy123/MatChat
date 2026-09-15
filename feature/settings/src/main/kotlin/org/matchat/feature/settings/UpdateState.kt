package org.matchat.feature.settings

/** Settings > Software update. A thin view of [org.matchat.core.update]'s
 *  UpdateStatus — the Fragment renders these labels; CENTER on the action row
 *  runs [UpdateAction], whose meaning depends on the current phase. */
enum class UpdatePhase { CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, READY, FAILED }

data class UpdateState(
    val phase: UpdatePhase = UpdatePhase.CHECKING,
    val currentVersion: String = "",
    val latestVersion: String = "",
    val notes: String = "",
    /** 0..100 while downloading, -1 when total size is unknown. */
    val percent: Int = 0,
)

sealed interface UpdateAction {
    /** Re-check now (from Up-to-date or Failed). */
    data object Check : UpdateAction

    /** Download the available release. */
    data object Download : UpdateAction

    /** Install the downloaded APK. */
    data object Install : UpdateAction
}

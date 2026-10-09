package org.matchat.client.sync

import android.content.Context
import android.os.PowerManager
import androidx.core.content.edit
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.matchat.core.model.background.BackgroundSync
import org.matchat.core.model.background.BackgroundSyncStatus
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `:app`'s [BackgroundSync]: battery exemption read from [PowerManager],
 * helper connection reported by the accessibility service via [SyncHosts].
 * Also remembers whether the one-time background setup (battery dialog, then the
 * helper prompt, UX-SPEC S27) has been offered.
 */
@Singleton
class BackgroundSyncStatusProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : BackgroundSync {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _status = MutableStateFlow(BackgroundSyncStatus(batteryExempt = readBatteryExempt()))
    override val status: StateFlow<BackgroundSyncStatus> = _status.asStateFlow()

    override fun refresh() {
        _status.update { it.copy(batteryExempt = readBatteryExempt()) }
    }

    fun setHelperConnected(connected: Boolean) {
        _status.update { it.copy(helperConnected = connected) }
    }

    /** True once the post-sign-in background setup has been offered (asked once). */
    val setupOffered: Boolean get() = prefs.getBoolean(KEY_SETUP_OFFERED, false)

    fun markSetupOffered() = prefs.edit { putBoolean(KEY_SETUP_OFFERED, true) }

    private fun readBatteryExempt(): Boolean {
        val power = context.getSystemService<PowerManager>() ?: return false
        return runCatching { power.isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(false)
    }

    private companion object {
        const val PREFS_NAME = "matchat_background_sync"
        const val KEY_SETUP_OFFERED = "setup_offered"
    }
}

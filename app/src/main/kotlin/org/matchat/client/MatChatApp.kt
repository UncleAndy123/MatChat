package org.matchat.client

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.matchat.client.notify.MessageNotifier
import org.matchat.core.ui.prefs.UserPreferences
import javax.inject.Inject

/** Hilt graph root. The sync foreground service (not this class) owns the SDK
 *  client; the app just constructs the graph (ARCHITECTURE.md "Sync lifecycle").
 *  Also supplies WorkManager's [Configuration] so the Hilt-injected [SyncWorker]
 *  fallback (ADR 0004) can be constructed. */
@HiltAndroidApp
class MatChatApp : Application(), Configuration.Provider {

    // Populated by Hilt during super.onCreate() (SyncForegroundService's
    // @Inject session field is the same pattern for a non-Activity class) —
    // safe to read right after that call returns, below.
    @Inject lateinit var userPreferences: UserPreferences

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // The incoming-message channel, at whatever sound version is already
        // stored (Notifications settings round) — ensureChannel is idempotent,
        // so this just confirms the channel exists before the sync service's
        // first notification; it does not create a new version on its own.
        // suspend (Bluetooth wake-up silent lead-in round: channel creation
        // now does audio decode work, SilentLeadInSound) — fire-and-forget in
        // a short-lived scope so app cold start is never blocked on it; this
        // is a non-critical warmup nothing else waits on.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            MessageNotifier.ensureChannel(
                this@MatChatApp,
                userPreferences.notificationChannelVersion.value,
                userPreferences.notificationSoundUri.value,
            )
        }
    }
}

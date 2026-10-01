package org.matchat.client.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.matchat.core.matrix.MatrixAuth
import org.matchat.core.matrix.MatrixSession
import org.matchat.core.matrix.MatrixSessionStore
import java.util.concurrent.TimeUnit

/**
 * The sync watchdog and the Android 15 fallback (docs/adr/0004, "always-on"
 * amendment). Scheduled whenever a session exists — by [SyncHosts.ensureRunning],
 * the boot receiver and sign-in — and cancelled only at sign-out. Every run:
 *
 * - a host already owns sync → nudge the loop (restarts a paused/dead one) and stop;
 * - no host (the process was killed and START_STICKY didn't bring it back, or
 *   the helper went away) → start one through [SyncHosts];
 * - the foreground service was timed out by the OS (Android 15's ~6h/24h
 *   `dataSync` cap) or refused to start → restore the session if needed, run a
 *   bounded catch-up sync and let [MessageNotifications] post anything new.
 *   WorkManager jobs are exempt from the FGS cap, so messages keep flowing,
 *   delayed by the interval, until the app is foregrounded again.
 *
 * The decision is [SyncWatchdog.decide], a pure function.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val auth: MatrixAuth,
    private val session: MatrixSession,
    private val sessionStore: MatrixSessionStore,
    private val messageNotifications: MessageNotifications,
    private val owner: SyncOwner,
    private val hosts: SyncHosts,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val decision = SyncWatchdog.decide(
            signedIn = sessionStore.hasSession(),
            hostActive = owner.activeHost.value != null,
            fgsTimedOut = SyncForegroundService.timedOut,
        )
        return when (decision) {
            // Signed out since this was scheduled — stop the periodic chain
            // rather than waking every interval to do nothing.
            SyncWatchdog.Decision.CANCEL -> {
                cancel(applicationContext)
                Result.success()
            }
            SyncWatchdog.Decision.NUDGE -> {
                runCatching { if (session.isActive()) session.ensureSyncing() }
                Result.success()
            }
            SyncWatchdog.Decision.START_HOST ->
                if (hosts.ensureRunning()) Result.success() else catchUp()
            SyncWatchdog.Decision.CATCH_UP -> catchUp()
        }
    }

    private suspend fun catchUp(): Result = runCatching {
        if (!session.isActive()) auth.restoreSession()
        if (!session.isActive()) {
            Log.w(TAG, "fallback sync: session not restorable; will retry")
            return@runCatching Result.retry()
        }
        coroutineScope {
            val observer = launch { session.rooms.collect { messageNotifications.onRooms(it) } }
            session.catchUpSync(WINDOW_MILLIS)
            observer.cancel()
        }
        Result.success()
    }.getOrElse {
        Log.w(TAG, "fallback sync failed; will retry: ${it.message}")
        Result.retry()
    }

    companion object {
        private const val TAG = "SyncWorker"
        private const val UNIQUE_NAME = "matchat-sync-fallback"

        /** Long enough for the SDK to drain a sliding-sync catch-up and emit the
         *  updated room list; short enough to stay well inside a background job's
         *  execution window and keep idle drain low. */
        private const val WINDOW_MILLIS = 20_000L

        /** WorkManager's minimum periodic interval is 15 minutes; a shorter
         *  request is silently clamped to it, so this is the real cadence. */
        private const val INTERVAL_MINUTES = 15L

        /** Schedule the periodic watchdog, keeping any existing schedule (so
         *  calling it on every resume doesn't push the next run back). */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Stop the watchdog — only at sign-out. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}

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
 * The WorkManager fallback for the Android 15 `dataSync` foreground-service
 * runtime cap (ADR 0004). Once [SyncForegroundService] hits the ~6h/24h ceiling
 * the OS bars it from running, so this periodic job takes over: each run restores
 * the session if the process was reclaimed, runs a bounded catch-up sync, and
 * lets [MessageNotifications] post any new-message notifications. WorkManager
 * jobs are exempt from the FGS cap, so this keeps messages flowing (delayed by
 * the interval) until the app is foregrounded and the service reclaims sync.
 *
 * Enqueued from [SyncForegroundService.onTimeout]; cancelled when the service
 * (re)starts and reclaims ownership, so the two never sync in parallel.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val auth: MatrixAuth,
    private val session: MatrixSession,
    private val sessionStore: MatrixSessionStore,
    private val messageNotifications: MessageNotifications,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Signed out since the fallback was scheduled — stop the periodic chain
        // rather than waking every interval to do nothing.
        if (!sessionStore.hasSession()) {
            cancel(applicationContext)
            return Result.success()
        }
        return runCatching {
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

        /** Start the periodic fallback (keeping any existing schedule). Called
         *  when the foreground service is timed out by the OS. */
        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Stop the periodic fallback — the foreground service owns sync again. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}

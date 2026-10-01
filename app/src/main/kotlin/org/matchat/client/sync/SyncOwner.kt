package org.matchat.client.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.matchat.client.notify.CallNotifier
import org.matchat.core.matrix.MatrixAuth
import org.matchat.core.matrix.MatrixSession
import org.matchat.core.matrix.MatrixSessionStore
import org.matchat.core.model.RoomSummary
import org.matchat.core.rtc.CallController
import org.matchat.core.rtc.CallPhase
import org.matchat.core.rtc.IncomingCall
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single owner of the SDK sync loop (ARCHITECTURE.md "Sync lifecycle"),
 * independent of what keeps the process alive. A [SyncHost] — the foreground
 * service or the background helper (docs/adr/0008) — [attach]es with its own
 * scope; this restores the session if needed and observes the room list for
 * message notifications and incoming calls. Screens observe; they never start
 * or stop sync.
 *
 * A [Singleton] so the call baseline (like [MessageNotifications]' unread
 * baseline) survives a hand-off between hosts: a room already ringing or already
 * in a call is not re-rung because the other host took over.
 */
@Singleton
class SyncOwner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: MatrixSession,
    private val auth: MatrixAuth,
    private val sessionStore: MatrixSessionStore,
    private val callController: CallController,
    private val messageNotifications: MessageNotifications,
) {
    private val _activeHost = MutableStateFlow<SyncHost?>(null)

    /** The host currently owning sync, or null when none does (the
     *  [SyncWorker] watchdog then restarts one, or runs a catch-up). */
    val activeHost: StateFlow<SyncHost?> = _activeHost.asStateFlow()

    private val jobs = mutableListOf<Job>()

    private val lastCall = HashMap<String, Boolean>()

    /** Rooms we raised a ring for, roomId -> caller; cleared on answer or end. */
    private val ringingRooms = HashMap<String, String>()

    /** True while we are in any call (our own or a ring we raised) — suppresses a
     *  second ring and our own outgoing call from ringing us. */
    private var ownCallActive = false
    private var seeded = false

    /**
     * Make [host] the owner, running the observers in [scope] (the host's own
     * lifecycle). Replaces any other host's observers so two never run at once.
     * Re-attaching the current host is a no-op beyond re-checking the loop.
     */
    @Synchronized
    fun attach(host: SyncHost, scope: CoroutineScope) {
        if (_activeHost.value == host && jobs.any { it.isActive }) {
            scope.launch { ensureSessionRestored() }
            return
        }
        cancelJobs()
        _activeHost.value = host
        jobs += scope.launch { ensureSessionRestored() }
        jobs += scope.launch { session.rooms.collect { rooms -> onRooms(rooms) } }
        // Track our call phase: once a ringing call connects it's answered (drop
        // it from the missed-call set); isActive gates a second ring.
        jobs += scope.launch {
            callController.session.collect { s ->
                ownCallActive = s.isActive
                if (s.phase == CallPhase.CONNECTED) s.roomId?.let { ringingRooms.remove(it.value) }
            }
        }
    }

    /** [host] is going away. Only clears ownership if it still holds it — a host
     *  already replaced by the other one must not tear down its successor. */
    @Synchronized
    fun detach(host: SyncHost) {
        if (_activeHost.value != host) return
        cancelJobs()
        _activeHost.value = null
    }

    private fun cancelJobs() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    /**
     * The SDK loop is normally started by MainActivity (sign-in, or its cold-start
     * restore). A process relaunched by START_STICKY, by the boot receiver, or by
     * the system re-binding the background helper never runs MainActivity, so the
     * owner restores too — otherwise "MatChat is running" would show over a dead
     * loop. An active client whose loop was paused by the fallback's last
     * catch-up is resumed with ensureSyncing() (idempotent if already running);
     * no client at all → restore (which starts sync). restore() itself is
     * serialized in :core:matrix, so a concurrent MainActivity restore is safe.
     */
    private suspend fun ensureSessionRestored() {
        if (!sessionStore.hasSession()) return
        if (session.isActive()) session.ensureSyncing() else auth.restoreSession()
    }

    private suspend fun onRooms(rooms: List<RoomSummary>) {
        // Message notifications go through the shared, @Singleton
        // MessageNotifications so the unread baseline survives any hand-off
        // (host to host, or to the SyncWorker fallback). Call ringing stays here
        // — a delayed background job can't usefully ring a live call.
        messageNotifications.onRooms(rooms)

        if (!seeded) {
            // Seed the call baseline so an already-ongoing call (app just launched
            // into it) never rings.
            rooms.forEach { lastCall[it.id.value] = it.hasActiveCall }
            seeded = true
            return
        }
        rooms.forEach { room ->
            val hadCall = lastCall[room.id.value] ?: false
            when {
                room.hasActiveCall && !hadCall -> onCallAppeared(room)
                !room.hasActiveCall && hadCall -> onCallDisappeared(room)
            }
            lastCall[room.id.value] = room.hasActiveCall
        }
    }

    /** A call appeared in a room: ring, unless we are already in a call (our own
     *  outgoing call lights up the same room, and we don't ring ourselves). */
    private fun onCallAppeared(room: RoomSummary) {
        if (ownCallActive) return
        val caller = room.name.ifBlank { room.id.value }
        ringingRooms[room.id.value] = caller
        callController.onIncomingCall(IncomingCall(room.id, caller))
        CallNotifier.showIncoming(context, room.id, caller)
    }

    /** A call ended: drop the ring. If we raised it and never answered (still in
     *  ringingRooms — the call collector removes answered ones), show missed. */
    private fun onCallDisappeared(room: RoomSummary) {
        CallNotifier.cancel(context)
        val caller = ringingRooms.remove(room.id.value) ?: return
        CallNotifier.showMissed(context, room.id, caller)
    }
}

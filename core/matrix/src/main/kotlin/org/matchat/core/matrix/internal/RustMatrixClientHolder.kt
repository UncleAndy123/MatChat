package org.matchat.core.matrix.internal

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.matchat.core.matrix.DraftStore
import org.matchat.core.matrix.MatrixDevConfig
import org.matchat.core.model.RoomId
import org.matchat.core.model.RoomSummary
import org.matchat.core.model.SyncState
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.RoomList
import org.matrix.rustcomponents.sdk.RoomListEntriesDynamicFilterKind
import org.matrix.rustcomponents.sdk.RoomListEntriesListener
import org.matrix.rustcomponents.sdk.RoomListEntriesUpdate
import org.matrix.rustcomponents.sdk.RoomListEntriesWithDynamicAdaptersResult
import org.matrix.rustcomponents.sdk.SlidingSyncVersionBuilder
import org.matrix.rustcomponents.sdk.SyncService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the single SDK [Client] and its [SyncService] for the process, and turns
 * the room-list sliding-sync stream into a [RoomSummary] flow the app collects
 * (ARCHITECTURE.md "Sync lifecycle" — one owner of the client). This is the only
 * place SDK objects live.
 *
 * FFI notes are marked inline; those are the version-sensitive calls to confirm
 * against the AAR on the first Android Studio compile.
 */
@Singleton
internal class RustMatrixClientHolder @Inject constructor(
    private val store: SessionFileStore,
    private val draftStore: DraftStore,
    private val devConfig: MatrixDevConfig,
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var client: Client? = null
    private var syncService: SyncService? = null
    private var roomList: RoomList? = null
    private var entriesResult: RoomListEntriesWithDynamicAdaptersResult? = null

    /** Ordered room entries maintained from the sliding-sync diff stream. */
    private val entries = mutableListOf<Room>()

    val rooms = MutableStateFlow<List<RoomSummary>>(emptyList())

    /** SYNCING means "session active, sync loop running" in this codebase (set
     *  once in [startSync], never cleared except by [logout]) — there's no
     *  finer-grained "caught up" signal from the SDK surfaced here yet, so
     *  SYNCING is the ordinary connected steady state, not a transient one
     *  (Online indicator round: SoftkeyFragment shows it as "connected"). */
    val syncState = MutableStateFlow(SyncState.IDLE)

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // Serializes restore so two callers (MainActivity's cold-start restore and the
    // sync service's ensureSessionRestored) can't build two clients against the
    // same crypto store at once — that race corrupts the olm/verification state
    // (messaging survives it; device verification does not).
    private val restoreMutex = Mutex()

    fun requireClient(): Client = requireNotNull(client) { "no active Matrix client" }

    fun isActive(): Boolean = client != null

    /** Our own Matrix user id, or null before login. */
    fun ownUserId(): String? = runCatching { client?.userId() }.getOrNull()

    /** Builds a client for [homeserver] (a server name or a full URL — well-known
     *  discovery resolves it). [resetStore] wipes the SDK store first, which a
     *  fresh login needs so a new device does not clash with a stored crypto
     *  account (restore, once enabled, will pass false). */
    suspend fun buildClient(homeserver: String, resetStore: Boolean = true): Client =
        // All SDK + file work is on IO, never the main thread (ARCHITECTURE.md).
        withContext(Dispatchers.IO) {
            // Close any live SDK objects (and their native SQLite handles) BEFORE we
            // touch the store. Without this, a leftover Client from a prior build or
            // a crashed session still holds the store's DB files open; deleting the
            // directory (resetStore) and recreating a DB at the same path then fails
            // migrations with "disk I/O error" — the login failure we saw on device.
            teardownClient()
            val path = if (resetStore) store.resetSdkStore() else store.sdkStorePath
            // FFI: sessionPaths(dataPath, cachePath) is deprecated but present in
            // 26.09.x; if removed, switch to sqliteStore(SqliteStoreBuilder(path)).
            var builder = ClientBuilder()
                .sessionPaths(dataPath = path, cachePath = path)
                .serverNameOrHomeserverUrl(homeserver)
                // Discover native sliding sync (MSC4186). Flat uniffi enums generate
                // UPPER_SNAKE_CASE Kotlin entries, hence DISCOVER_NATIVE. Without a
                // version builder the room list fails with VersionIsMissing.
                .slidingSyncVersionBuilder(SlidingSyncVersionBuilder.DISCOVER_NATIVE)
            if (devConfig.allowInsecureTls) {
                // Debug builds only (see MatrixDevConfig): lets on-device testing
                // work behind an SSL-inspecting proxy and sidesteps the rustls-
                // platform-verifier init requirement. Never enabled in release.
                builder = builder.disableSslVerification()
            }
            val built = builder.build()
            client = built
            built
        }

    /** Persist the session (encrypted) after a successful login so the next cold
     *  start can restore it and reuse the same device/crypto store. */
    suspend fun persistSession() {
        store.persist(SessionCodec.encode(requireClient().session()))
    }

    /**
     * Cold-start restore: rebuild the client against the EXISTING store (no reset,
     * so the persisted device's crypto account matches) and restore the session.
     * Returns false — routing the app to Welcome — when there is nothing to
     * restore or restoration fails (self-healing: the next login resets the store).
     */
    suspend fun restore(): Boolean = restoreMutex.withLock {
        // Already restored (e.g. the other caller won the race) — don't rebuild the
        // client, which would tear down a live sync loop and crypto session.
        if (client != null) return@withLock true
        val blob = store.load() ?: return@withLock false
        runCatching {
            val session = SessionCodec.decode(blob)
            buildClient(session.homeserverUrl, resetStore = false)
            requireClient().restoreSession(session)
            startSync()
            true
        }.getOrElse {
            // buildClient() sets `client` before restoreSession()/startSync() run, so
            // a failure here leaves a half-built client with no sync loop. Left as-is,
            // the `client != null` guard above and isActive() both report that zombie
            // as a live, syncing session, so neither this path nor the sync service
            // ever retries — sync stays dead. Tear it back down so the next restore
            // genuinely rebuilds (mirrors buildClient()'s own teardown-on-entry).
            teardownClient()
            false
        }
    }

    /**
     * Start the sync loop and begin observing the room list. If a [SyncService]
     * already exists it is *resumed* rather than rebuilt: [SyncService.start] and
     * [stopSync]'s [SyncService.stop] are designed to be cycled (the room-list
     * stream stays subscribed across a pause), which is how the WorkManager
     * fallback (ADR 0004) and a foreground resume re-arm sync without touching
     * the crypto store. Idempotent for an already-running loop.
     */
    suspend fun startSync() = withContext(Dispatchers.IO) {
        observeConnectivity()
        val existing = syncService
        if (existing != null) {
            // Resume a paused (or already-running) loop — start() is safe to
            // re-issue and the entriesWithDynamicAdapters stream is still live.
            runCatching { existing.start() }
            syncState.value = SyncState.SYNCING
            return@withContext
        }
        syncState.value = SyncState.SYNCING
        val svc = requireClient().syncService().finish()
        svc.start()
        syncService = svc

        val list = svc.roomListService().allRooms()
        roomList = list

        val listener = object : RoomListEntriesListener {
            override fun onUpdate(roomEntriesUpdate: List<RoomListEntriesUpdate>) {
                applyUpdates(roomEntriesUpdate)
                recompute()
            }
        }
        val result = list.entriesWithDynamicAdapters(pageSize = PAGE_SIZE, listener = listener)
        // Joined = the user's joined rooms (no args); All takes a filter list.
        result.controller().setFilter(RoomListEntriesDynamicFilterKind.Joined)
        entriesResult = result // keep alive so the stream is not dropped
    }

    /**
     * Pause the sync loop, keeping the [SyncService], room-list stream, and
     * [client] alive so [startSync] resumes it with a plain [SyncService.start]
     * — no rebuild, no crypto-store churn. Used when the foreground service hits
     * the Android 15 `dataSync` runtime cap (ADR 0004) and between the fallback
     * worker's bounded catch-up windows. Leaves [syncState] and the last [rooms]
     * snapshot untouched (SYNCING still means "session active" here — see its
     * doc) for a fast foreground resume. Idempotent — no-op when nothing is
     * running. Full teardown remains [logout]'s job via [teardownClient].
     */
    suspend fun stopSync(): Unit = withContext(Dispatchers.IO) {
        val svc = syncService ?: return@withContext
        runCatching { svc.stop() }
        Unit
    }

    /**
     * Run the sync loop for [windowMillis] then pause it — one bounded catch-up
     * for the WorkManager fallback (ADR 0004). No-op when no client is built
     * (the caller restores first). Successive worker runs just cycle
     * [startSync]/[stopSync] on the same live service.
     */
    suspend fun catchUpSync(windowMillis: Long) = withContext(Dispatchers.IO) {
        if (client == null) return@withContext
        startSync()
        delay(windowMillis)
        stopSync()
    }

    private fun observeConnectivity() {
        if (networkCallback != null) return
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (syncState.value == SyncState.OFFLINE) syncState.value = SyncState.SYNCING
            }

            override fun onLost(network: Network) {
                // onLost fires per-network; only declare OFFLINE once nothing
                // else is active (e.g. Wi-Fi drops but mobile data is still up).
                if (cm.activeNetwork == null) syncState.value = SyncState.OFFLINE
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(callback) }
            .onSuccess { networkCallback = callback }
    }

    fun roomFor(roomId: RoomId): Room? = runCatching { roomList?.room(roomId.value) }.getOrNull()

    /** Restore keys from a recovery key so encrypted history can be decrypted. */
    suspend fun recover(recoveryKey: String) = withContext(Dispatchers.IO) {
        requireClient().encryption().recover(recoveryKey)
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        runCatching { syncService?.stop() }
        runCatching { requireClient().logout() }
        networkCallback?.let { cb ->
            val cm = context.getSystemService(ConnectivityManager::class.java)
            runCatching { cm?.unregisterNetworkCallback(cb) }
        }
        networkCallback = null
        teardownClient()
        synchronized(entries) { entries.clear() }
        rooms.value = emptyList()
        syncState.value = SyncState.IDLE
        draftStore.clearAll()
        store.clear()
    }

    /**
     * Drop all live SDK objects, destroying their native handles so the crypto
     * store's SQLite files are closed deterministically (uniffi objects otherwise
     * linger until GC, keeping the DB open). Order: dependents before the client.
     * Idempotent — safe to call when nothing is built.
     */
    private fun teardownClient() {
        runCatching { entriesResult?.destroy() }
        runCatching { roomList?.destroy() }
        runCatching { syncService?.destroy() }
        runCatching { client?.destroy() }
        entriesResult = null
        roomList = null
        syncService = null
        client = null
    }

    private fun applyUpdates(updates: List<RoomListEntriesUpdate>) = synchronized(entries) {
        updates.forEach { update ->
            when (update) {
                is RoomListEntriesUpdate.Append -> entries.addAll(update.values)
                is RoomListEntriesUpdate.PushBack -> entries.add(update.value)
                is RoomListEntriesUpdate.PushFront -> entries.add(0, update.value)
                is RoomListEntriesUpdate.Insert -> entries.add(update.index.toInt(), update.value)
                is RoomListEntriesUpdate.Set -> entries[update.index.toInt()] = update.value
                is RoomListEntriesUpdate.Remove -> entries.removeAt(update.index.toInt())
                is RoomListEntriesUpdate.PopBack -> entries.removeAt(entries.lastIndex)
                is RoomListEntriesUpdate.PopFront -> entries.removeAt(0)
                is RoomListEntriesUpdate.Truncate -> entries.subList(update.length.toInt(), entries.size).clear()
                is RoomListEntriesUpdate.Reset -> {
                    entries.clear()
                    entries.addAll(update.values)
                }
                is RoomListEntriesUpdate.Clear -> entries.clear()
            }
        }
    }

    private fun recompute() {
        val snapshot = synchronized(entries) { entries.toList() }
        scope.launch {
            rooms.value = snapshot.map { Mappers.toRoomSummary(it) }
        }
    }

    private companion object {
        const val PAGE_SIZE: UInt = 100u
    }
}

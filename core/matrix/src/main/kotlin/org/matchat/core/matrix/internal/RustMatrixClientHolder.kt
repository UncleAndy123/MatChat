package org.matchat.core.matrix.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val devConfig: MatrixDevConfig,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var client: Client? = null
    private var syncService: SyncService? = null
    private var roomList: RoomList? = null
    private var entriesResult: RoomListEntriesWithDynamicAdaptersResult? = null

    /** Ordered room entries maintained from the sliding-sync diff stream. */
    private val entries = mutableListOf<Room>()

    val rooms = MutableStateFlow<List<RoomSummary>>(emptyList())
    val syncState = MutableStateFlow(SyncState.IDLE)

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
    suspend fun restore(): Boolean {
        val blob = store.load() ?: return false
        return runCatching {
            val session = SessionCodec.decode(blob)
            buildClient(session.homeserverUrl, resetStore = false)
            requireClient().restoreSession(session)
            startSync()
            true
        }.getOrDefault(false)
    }

    /** Start the sync loop and begin observing the room list. */
    suspend fun startSync() = withContext(Dispatchers.IO) {
        if (syncService != null) return@withContext
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

    fun roomFor(roomId: RoomId): Room? =
        runCatching { roomList?.room(roomId.value) }.getOrNull()

    /** Restore keys from a recovery key so encrypted history can be decrypted. */
    suspend fun recover(recoveryKey: String) = withContext(Dispatchers.IO) {
        requireClient().encryption().recover(recoveryKey)
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        runCatching { syncService?.stop() }
        runCatching { requireClient().logout() }
        client = null
        syncService = null
        roomList = null
        entriesResult = null
        synchronized(entries) { entries.clear() }
        rooms.value = emptyList()
        syncState.value = SyncState.IDLE
        store.clear()
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

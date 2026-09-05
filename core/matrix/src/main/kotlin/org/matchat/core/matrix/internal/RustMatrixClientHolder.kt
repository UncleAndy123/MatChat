package org.matchat.core.matrix.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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

    /** Builds a client rooted at [homeserverUrl] using the on-disk SDK store. */
    suspend fun buildClient(homeserverUrl: String): Client {
        val path = store.sdkStorePath
        // FFI: sessionPaths(dataPath, cachePath) is deprecated but present in
        // 26.09.x; if removed, switch to sqliteStore(SqliteStoreBuilder(path)).
        val built = ClientBuilder()
            .sessionPaths(dataPath = path, cachePath = path)
            .homeserverUrl(homeserverUrl)
            .build()
        client = built
        return built
    }

    /** Persist the current session after a successful login. */
    suspend fun persistSession() {
        val session = requireClient().session()
        store.persist(SessionCodec.encode(session))
    }

    /** Rebuild + restore a persisted session on cold start. Returns false when
     *  there is nothing to restore or restoration fails. */
    suspend fun restore(): Boolean {
        val blob = store.load() ?: return false
        return runCatching {
            val session = SessionCodec.decode(blob)
            val built = buildClient(session.homeserverUrl)
            built.restoreSession(session)
            startSync()
            true
        }.getOrDefault(false)
    }

    /** Start the sync loop and begin observing the room list. */
    suspend fun startSync() {
        if (syncService != null) return
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
        // FFI: filter kind enum name — .All shows every joined room; confirm the
        // variant against the AAR (may be AllNonLeft/NonLeftOrInvited).
        result.controller().setFilter(RoomListEntriesDynamicFilterKind.All)
        entriesResult = result // keep alive so the stream is not dropped
    }

    fun roomFor(roomId: RoomId): Room? =
        runCatching { roomList?.room(roomId.value) }.getOrNull()

    suspend fun logout() {
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

package org.matchat.core.matrix.internal

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.matchat.core.matrix.DraftStore
import org.matchat.core.matrix.MatrixDevConfig
import org.matchat.core.model.InviteSummary
import org.matchat.core.model.RoomId
import org.matchat.core.model.RoomSummary
import org.matchat.core.model.SpaceSummary
import org.matchat.core.model.SyncState
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.LogLevel
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.RoomList
import org.matrix.rustcomponents.sdk.RoomListEntriesDynamicFilterKind
import org.matrix.rustcomponents.sdk.RoomListEntriesListener
import org.matrix.rustcomponents.sdk.RoomListEntriesUpdate
import org.matrix.rustcomponents.sdk.RoomListEntriesWithDynamicAdaptersResult
import org.matrix.rustcomponents.sdk.SlidingSyncVersionBuilder
import org.matrix.rustcomponents.sdk.SpaceListUpdate
import org.matrix.rustcomponents.sdk.SpaceService
import org.matrix.rustcomponents.sdk.SpaceServiceJoinedSpacesListener
import org.matrix.rustcomponents.sdk.SyncService
import org.matrix.rustcomponents.sdk.SyncServiceState
import org.matrix.rustcomponents.sdk.SyncServiceStateObserver
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.TracingConfiguration
import org.matrix.rustcomponents.sdk.initPlatform
import java.util.concurrent.atomic.AtomicBoolean
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
    private var invitesResult: RoomListEntriesWithDynamicAdaptersResult? = null
    private var spacesResult: RoomListEntriesWithDynamicAdaptersResult? = null
    private var spaceService: SpaceService? = null
    private var spacesHandle: TaskHandle? = null // keeps the space graph current

    /** Ordered room entries maintained from the sliding-sync diff stream. */
    private val entries = mutableListOf<Room>()

    /** Invited rooms, from a second stream over the same list (Invite filter). */
    private val inviteEntries = mutableListOf<Room>()

    /** Joined spaces, from a third stream over the same list (Joined + Space). */
    private val spaceEntries = mutableListOf<Room>()

    val rooms = MutableStateFlow<List<RoomSummary>>(emptyList())

    /** Pending invitations (S18), untagged by policy — RustMatrixSession tags them. */
    val invites = MutableStateFlow<List<InviteSummary>>(emptyList())

    /** Joined spaces (S29), each with its joined parent spaces. */
    val spaces = MutableStateFlow<List<SpaceSummary>>(emptyList())

    /** SYNCING means "session active, sync loop running" in this codebase (set
     *  once in [startSync], never cleared except by [logout]) — there's no
     *  finer-grained "caught up" signal from the SDK surfaced here yet, so
     *  SYNCING is the ordinary connected steady state, not a transient one
     *  (Online indicator round: SoftkeyFragment shows it as "connected"). */
    val syncState = MutableStateFlow(SyncState.IDLE)

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // Sync-loop watchdog (docs/adr/0004, "always-on" amendment): the SDK reports
    // its loop state through syncStateHandle; SyncRestartPolicy decides when a
    // dead loop (ERROR/TERMINATED) is restarted. [paused] marks an intentional
    // stop — stopSync()/logout() — which must never be "recovered".
    private var syncStateHandle: TaskHandle? = null // held so the state stream is not dropped
    private val restartPolicy = SyncRestartPolicy()
    private var restartJob: Job? = null

    @Volatile private var paused = false

    // Serializes restore so two callers (MainActivity's cold-start restore and the
    // sync service's ensureSessionRestored) can't build two clients against the
    // same crypto store at once — that race corrupts the olm/verification state
    // (messaging survives it; device verification does not).
    private val restoreMutex = Mutex()

    // The SDK's TLS layer (rustls-platform-verifier) must be initialized once per
    // process, before any client is built, or sign-in fails with "Expect
    // rustls-platform-verifier to be initialized" on a real (release) TLS path.
    private val platformInitialized = AtomicBoolean(false)

    /** The bundled public CA roots (docs/adr/0010), parsed once into one DER/PEM
     *  blob per certificate for ClientBuilder.addRootCertificates. Empty when the
     *  resource can't be read — the build then falls back to the OS store alone. */
    private val caBundle: List<ByteArray> by lazy { loadCaBundle() }

    private fun loadCaBundle(): List<ByteArray> = runCatching {
        val text = context.resources.openRawResource(org.matchat.core.matrix.R.raw.ca_bundle)
            .bufferedReader().use { it.readText() }
        // One ByteArray per PEM block — the SDK parses each entry as a single
        // certificate (DER first, then PEM), not as a bundle.
        PEM_CERT.findAll(text).map { it.value.toByteArray(Charsets.US_ASCII) }.toList()
    }.getOrElse { emptyList() }

    /** Every certificate the device itself trusts — the system roots and any CA
     *  the user installed by hand — read from `AndroidCAStore` as DER. Added
     *  alongside [caBundle] so disabling the SDK's built-in roots does not drop
     *  the device's own trust (a self-hosted homeserver's private CA keeps
     *  working). Empty when the store can't be read. */
    private fun deviceTrustAnchors(): List<ByteArray> = runCatching {
        val store = java.security.KeyStore.getInstance("AndroidCAStore")
        store.load(null)
        store.aliases().toList().mapNotNull { alias ->
            (store.getCertificate(alias) as? java.security.cert.X509Certificate)?.encoded
        }
    }.getOrElse { emptyList() }

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
            ensurePlatformInitialized()
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
            // Fix TLS trust on old Android (7–11, confirmed on real devices,
            // issue #41). The SDK verifies against the OS trust store via
            // rustls-platform-verifier, and those aging stores can't anchor the
            // chains homeservers now serve (Google Trust Services, Let's Encrypt),
            // so release sign-in fails with "trust anchor not found" while Chrome —
            // carrying its own roots — works. We can't MERGE extra roots into the
            // platform verifier (this SDK build rejects that with a reqwest
            // "builder error"), so we rebuild the trust set ourselves and hand it
            // to webpki: the bundled modern public roots (docs/adr/0010) PLUS the
            // device's own AndroidCAStore, so every CA the device/user already
            // trusts — including one the user installed by hand — is still honoured.
            // Only when we actually have roots to supply; otherwise fall back to
            // the OS store (the platform verifier) unchanged.
            val trustRoots = caBundle + deviceTrustAnchors()
            if (trustRoots.isNotEmpty()) {
                builder = builder.disableBuiltInRootCertificates().addRootCertificates(trustRoots)
            }
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

    /**
     * Initializes the SDK platform (its Tokio runtime, tracing, and the
     * rustls-platform-verifier TLS verifier) exactly once per process, before the
     * first client is built. The verifier resolves the vendored
     * org.rustls.platformverifier classes (:app, `libs/`) by name over JNI; without
     * this call a real TLS path fails with "Expect rustls-platform-verifier to be
     * initialized". Debug builds also disableSslVerification() (MatrixDevConfig), so
     * the verifier is unused there, but initializing unconditionally matches how the
     * SDK is meant to start and keeps release and debug on the same path.
     */
    private fun ensurePlatformInitialized() {
        if (!platformInitialized.compareAndSet(false, true)) return
        runCatching {
            initPlatform(
                TracingConfiguration(
                    logLevel = LogLevel.WARN,
                    traceLogPacks = emptyList(),
                    extraTargets = emptyList(),
                    // SDK warnings and errors go to logcat, so
                    // a field report can name the real cause of a failure — e.g.
                    // "invalid peer certificate: UnknownIssuer" vs a DNS error —
                    // which the error shown in the UI does not carry. WARN and up
                    // only: the SDK never logs tokens or message content at these
                    // levels (AGENTS.md §9 records this exception).
                    writeToStdoutOrSystem = true,
                    writeToFiles = null,
                    sentryConfig = null,
                ),
                // useLightweightTokioRuntime: full runtime for the app process
                false,
            )
        }.onSuccess {
            // Confirms the rustls-platform-verifier was set up: without this the
            // SDK's TLS path rejects every chain (even one the device trusts).
            android.util.Log.w("MatrixClientHolder", "initPlatform OK")
        }.onFailure {
            // Don't hard-fail client build: log the full cause (type + stack) and
            // let the TLS error surface at sign-in. A failure here means the
            // verifier never initialized, so every TLS verification then fails.
            android.util.Log.w(
                "MatrixClientHolder",
                "initPlatform FAILED: ${it.javaClass.name}: ${it.message}",
                it,
            )
        }
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
        paused = false
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
        // withOfflineMode: on a network error the SDK enters OFFLINE, probes the
        // server itself and resumes once it answers, instead of ending in ERROR.
        // The state observer below restarts anything that still dies.
        val svc = requireClient().syncService().withOfflineMode().finish()
        syncStateHandle = svc.state(
            object : SyncServiceStateObserver {
                override fun onUpdate(state: SyncServiceState) = onSyncServiceState(svc, state)
            },
        )
        svc.start()
        syncService = svc

        val list = svc.roomListService().allRooms()
        roomList = list

        val listener = object : RoomListEntriesListener {
            override fun onUpdate(roomEntriesUpdate: List<RoomListEntriesUpdate>) {
                applyUpdates(entries, roomEntriesUpdate)
                recompute()
            }
        }
        val result = list.entriesWithDynamicAdapters(pageSize = PAGE_SIZE, listener = listener)
        // Joined rooms that are not spaces — spaces get their own stream below
        // and show as folders (S29), never as chat rows.
        result.controller().setFilter(
            RoomListEntriesDynamicFilterKind.All(
                listOf(RoomListEntriesDynamicFilterKind.Joined, RoomListEntriesDynamicFilterKind.NonSpace),
            ),
        )
        entriesResult = result // keep alive so the stream is not dropped

        // Invitations: a second stream over the same room list, filtered to
        // Invited membership. Accepting one moves the room to the Joined stream.
        val inviteListener = object : RoomListEntriesListener {
            override fun onUpdate(roomEntriesUpdate: List<RoomListEntriesUpdate>) {
                applyUpdates(inviteEntries, roomEntriesUpdate)
                inviteMapper.request()
            }
        }
        val invitesStream = list.entriesWithDynamicAdapters(pageSize = PAGE_SIZE, listener = inviteListener)
        invitesStream.controller().setFilter(RoomListEntriesDynamicFilterKind.Invite)
        invitesResult = invitesStream

        // Spaces: the SpaceService answers "which joined spaces contain this
        // room" from local state. Its joined-spaces subscription keeps that
        // graph current; any change re-maps rooms and spaces so folders follow.
        val spaceSvc = runCatching { requireClient().spaceService() }.getOrNull()
        spaceService = spaceSvc
        spacesHandle = runCatching {
            spaceSvc?.subscribeToTopLevelJoinedSpaces(
                object : SpaceServiceJoinedSpacesListener {
                    override fun onUpdate(roomUpdates: List<SpaceListUpdate>) {
                        recompute()
                        spaceMapper.request()
                    }
                },
            )
        }.getOrNull()
        val spaceListener = object : RoomListEntriesListener {
            override fun onUpdate(roomEntriesUpdate: List<RoomListEntriesUpdate>) {
                applyUpdates(spaceEntries, roomEntriesUpdate)
                spaceMapper.request()
                recompute() // a newly joined space can re-home rooms
            }
        }
        val spacesStream = list.entriesWithDynamicAdapters(pageSize = PAGE_SIZE, listener = spaceListener)
        spacesStream.controller().setFilter(
            RoomListEntriesDynamicFilterKind.All(
                listOf(RoomListEntriesDynamicFilterKind.Joined, RoomListEntriesDynamicFilterKind.Space),
            ),
        )
        spacesResult = spacesStream
    }

    /** Joined spaces that list [roomId] as a child. Local lookup; empty on error. */
    private suspend fun parentSpacesOf(roomId: String): List<RoomId> =
        runCatching { spaceService?.joinedParentsOfChild(roomId).orEmpty().map { RoomId(it.roomId) } }
            .getOrDefault(emptyList())

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
        paused = true
        restartJob?.cancel()
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
                // A loop that died while the network was down would otherwise wait
                // out its backoff; kick it now. start() is safe on a running loop.
                restartPolicy.onNetworkAvailable()
                val svc = syncService
                if (svc != null && !paused) scope.launch { runCatching { svc.start() } }
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
        paused = true
        restartJob?.cancel()
        runCatching { syncService?.stop() }
        runCatching { requireClient().logout() }
        networkCallback?.let { cb ->
            val cm = context.getSystemService(ConnectivityManager::class.java)
            runCatching { cm?.unregisterNetworkCallback(cb) }
        }
        networkCallback = null
        teardownClient()
        synchronized(entries) { entries.clear() }
        synchronized(inviteEntries) { inviteEntries.clear() }
        synchronized(spaceEntries) { spaceEntries.clear() }
        rooms.value = emptyList()
        invites.value = emptyList()
        spaces.value = emptyList()
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
        restartJob?.cancel()
        restartJob = null
        runCatching { syncStateHandle?.cancel() }
        runCatching { syncStateHandle?.destroy() }
        syncStateHandle = null
        runCatching { entriesResult?.destroy() }
        runCatching { invitesResult?.destroy() }
        runCatching { spacesResult?.destroy() }
        runCatching { spacesHandle?.cancel() }
        runCatching { spacesHandle?.destroy() }
        runCatching { spaceService?.destroy() }
        runCatching { roomList?.destroy() }
        runCatching { syncService?.destroy() }
        runCatching { client?.destroy() }
        entriesResult = null
        invitesResult = null
        spacesResult = null
        spacesHandle = null
        spaceService = null
        roomList = null
        syncService = null
        client = null
    }

    /**
     * SDK loop state → the app's [SyncState] plus, when the loop died without us
     * asking, a delayed restart. Runs on an SDK callback thread; the restart is
     * scheduled on [scope] and skipped if the loop was paused or replaced in the
     * meantime. While [paused], [syncState] is left alone (see [stopSync]).
     */
    private fun onSyncServiceState(svc: SyncService, state: SyncServiceState) {
        val loopState = when (state) {
            SyncServiceState.IDLE -> SyncRestartPolicy.LoopState.IDLE
            SyncServiceState.RUNNING -> SyncRestartPolicy.LoopState.RUNNING
            SyncServiceState.OFFLINE -> SyncRestartPolicy.LoopState.OFFLINE
            SyncServiceState.TERMINATED -> SyncRestartPolicy.LoopState.TERMINATED
            SyncServiceState.ERROR -> SyncRestartPolicy.LoopState.ERROR
        }
        if (!paused) {
            when (loopState) {
                SyncRestartPolicy.LoopState.RUNNING -> syncState.value = SyncState.SYNCING
                SyncRestartPolicy.LoopState.OFFLINE -> syncState.value = SyncState.OFFLINE
                SyncRestartPolicy.LoopState.ERROR,
                SyncRestartPolicy.LoopState.TERMINATED,
                -> syncState.value = SyncState.ERROR
                SyncRestartPolicy.LoopState.IDLE -> Unit
            }
        }
        val delayMillis = restartPolicy.onState(loopState, paused) ?: return
        restartJob?.cancel()
        restartJob = scope.launch {
            delay(delayMillis)
            if (!paused && syncService === svc) runCatching { svc.start() }
        }
    }

    private fun applyUpdates(target: MutableList<Room>, updates: List<RoomListEntriesUpdate>) = synchronized(target) {
        updates.forEach { update ->
            when (update) {
                is RoomListEntriesUpdate.Append -> target.addAll(update.values)
                is RoomListEntriesUpdate.PushBack -> target.add(update.value)
                is RoomListEntriesUpdate.PushFront -> target.add(0, update.value)
                is RoomListEntriesUpdate.Insert -> target.add(update.index.toInt(), update.value)
                is RoomListEntriesUpdate.Set -> target[update.index.toInt()] = update.value
                is RoomListEntriesUpdate.Remove -> target.removeAt(update.index.toInt())
                is RoomListEntriesUpdate.PopBack -> target.removeAt(target.lastIndex)
                is RoomListEntriesUpdate.PopFront -> target.removeAt(0)
                is RoomListEntriesUpdate.Truncate -> target.subList(update.length.toInt(), target.size).clear()
                is RoomListEntriesUpdate.Reset -> {
                    target.clear()
                    target.addAll(update.values)
                }
                is RoomListEntriesUpdate.Clear -> target.clear()
            }
        }
    }

    /** Maps the room list one pass at a time, always from the latest entries
     *  (see [LatestOnlyRunner] for the out-of-order bug this replaced). */
    private val roomMapper = LatestOnlyRunner(scope) {
        val snapshot = synchronized(entries) { entries.toList() }
        rooms.value = snapshot.map { Mappers.toRoomSummary(it, parentSpacesOf(it.id())) }
    }

    private fun recompute() = roomMapper.request()

    private val spaceMapper = LatestOnlyRunner(scope) {
        val snapshot = synchronized(spaceEntries) { spaceEntries.toList() }
        spaces.value = snapshot.map { Mappers.toSpaceSummary(it, parentSpacesOf(it.id())) }
    }

    private val inviteMapper = LatestOnlyRunner(scope) {
        val snapshot = synchronized(inviteEntries) { inviteEntries.toList() }
        invites.value = snapshot.map { Mappers.toInviteSummary(it) }
    }

    private companion object {
        const val PAGE_SIZE: UInt = 100u

        /** One PEM certificate block, matched out of the bundled CA file. */
        private val PEM_CERT =
            Regex("-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----", RegexOption.DOT_MATCHES_ALL)
    }
}

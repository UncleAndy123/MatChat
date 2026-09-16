package org.matchat.core.matrix.internal

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

/**
 * Root cause of the pin-persistence bug: [RustMatrixSession.timeline] builds a
 * brand-new [RustRoomTimeline] on every call, so [TimelineViewModel],
 * [PinnedMessagesViewModel] and [MessageInfoViewModel] each hold an independent
 * instance for the same room, each with its own private, in-memory pinnedIds
 * seeded from its own cold `room.roomInfo()` read. A pin made through one
 * instance's [RustRoomTimeline.setPinned] therefore never reaches a sibling or
 * future instance for the same room — no error, because nothing failed.
 *
 * This is a small, process-lifetime, in-memory cache internal to :core:matrix
 * (same "stateful singleton, no DI" shape as [MediaRegistry]) so every
 * [RustRoomTimeline] for a given room shares one truth, live.
 *
 * [RustRoomTimeline.setPinned] also reads [get] as its write baseline
 * (preferred over a cold SDK read, which lags the server's echo of a just-
 * completed write) — a second reason this exists, beyond keeping sibling
 * screens in sync: it's what makes two pins issued close together both
 * stick, instead of the second silently replacing the first.
 *
 * Deliberately narrow scope (bug fix: a room's pins made from another client,
 * e.g. Element, used to never appear here once this cache had ever been
 * populated for that room in this process's life — see [RustRoomTimeline]'s
 * `init` block): this cache is NOT a substitute for a fresh read when a new
 * [RustRoomTimeline] is constructed for a room. Every new instance still does
 * its own cold `fetchPinnedIds` read and treats it as authoritative, then
 * seeds/refreshes this cache from that result — [get] exists only for (a)
 * [updatesFor]'s live cross-screen propagation of a write made through one
 * already-open screen to its siblings, and (b) [RustRoomTimeline.setPinned]'s
 * write baseline above.
 */
internal object PinnedEventsCache {
    private val state = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

    /** Null means nothing has been cached for this room yet this process. */
    fun get(roomId: String): Set<String>? = state.value[roomId]

    fun put(roomId: String, ids: Set<String>) {
        state.value = state.value + (roomId to ids)
    }

    /** Emits only values published *after* collection starts — a fresh
     *  subscriber gets its own seed via [get], not a replay of this. */
    fun updatesFor(roomId: String): Flow<Set<String>> = state.map { it[roomId] }
        .drop(1)
        .filterNotNull()
        .distinctUntilChanged()
}

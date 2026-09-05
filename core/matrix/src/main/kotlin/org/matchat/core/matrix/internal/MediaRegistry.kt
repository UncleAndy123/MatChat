package org.matchat.core.matrix.internal

import org.matrix.rustcomponents.sdk.MediaSource
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps the SDK [MediaSource] for each media event, keyed by event id, so the UI
 * can request a download by id without an SDK type (or encryption keys) crossing
 * the :core:matrix boundary (AGENTS.md §2). Populated by [Mappers] as timeline
 * items are mapped; read by the session's loadMedia.
 */
internal object MediaRegistry {
    private val sources = ConcurrentHashMap<String, MediaSource>()

    fun put(eventId: String, source: MediaSource) {
        if (eventId.isNotEmpty()) sources[eventId] = source
    }

    fun get(eventId: String): MediaSource? = sources[eventId]
}

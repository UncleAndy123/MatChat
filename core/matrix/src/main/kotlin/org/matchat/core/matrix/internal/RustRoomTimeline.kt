package org.matchat.core.matrix.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matchat.core.matrix.RoomTimeline
import org.matchat.core.model.EventId
import org.matchat.core.model.MediaKind
import org.matchat.core.model.TimelineItem
import org.matchat.core.model.UserId
import org.matrix.rustcomponents.sdk.AudioInfo
import org.matrix.rustcomponents.sdk.EventOrTransactionId
import org.matrix.rustcomponents.sdk.FileInfo
import org.matrix.rustcomponents.sdk.ImageInfo
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineListener
import org.matrix.rustcomponents.sdk.TypingNotificationsListener
import org.matrix.rustcomponents.sdk.UploadParameters
import org.matrix.rustcomponents.sdk.UploadSource
import org.matrix.rustcomponents.sdk.VideoInfo
import org.matrix.rustcomponents.sdk.messageEventContentFromMarkdown
import org.matrix.rustcomponents.sdk.TimelineItem as RustTimelineItem

/**
 * A live room timeline backed by the SDK (S9). Maintains an ordered buffer of SDK
 * items from the diff stream and maps it to [TimelineItem]s.
 *
 * FFI: the TimelineDiff variant set and item accessors are version-sensitive;
 * confirm on the first AAR compile. The buffer/diff approach mirrors the room
 * list so both use the same shape.
 */
internal class RustRoomTimeline(
    private val room: Room?,
    private val scope: CoroutineScope,
    private val ownUserId: String?,
) : RoomTimeline {

    private val buffer = mutableListOf<RustTimelineItem>()

    // Sender display names + avatars (Phase 7, extended in the Avatars round):
    // fetched once below, not per event/recompute — @Volatile so the SDK's
    // own listener thread (recompute) sees a fetch that completed on the init
    // coroutine without needing a lock for a plain reference read/swap.
    @Volatile private var members: Map<String, MemberInfo> = emptyMap()

    // Pinned messages round: seeded from the process-lifetime [PinnedEventsCache]
    // when another RustRoomTimeline instance for this room has already primed
    // it, falling back to a cold room.roomInfo() read otherwise (see init{}).
    // Live-updated by the cache subscription below — a pin made through a
    // *sibling* instance for this room (Timeline vs. Pinned Messages vs.
    // Message info all hold their own instance, per RustMatrixSession.timeline)
    // now reaches this one immediately, which is the actual fix (see
    // PinnedEventsCache's doc comment for the root cause). A pin/unpin made
    // from another *client* still isn't picked up live (no state-event
    // listener exists yet); reopening the room re-fetches.
    @Volatile private var pinnedIds: Set<String> = emptySet()
    private val itemsFlow = MutableStateFlow<List<TimelineItem>>(emptyList())
    private val typingFlow = MutableStateFlow<List<UserId>>(emptyList())
    private var timeline: Timeline? = null
    private var handle: TaskHandle? = null // held so the diff stream is not dropped
    private var typingHandle: TaskHandle? = null // held so the typing stream is not dropped

    override val items: Flow<List<TimelineItem>> = itemsFlow
    override val typing: Flow<List<UserId>> = typingFlow

    init {
        scope.launch {
            val r = room ?: return@launch
            val tl = r.timeline()
            timeline = tl
            handle = tl.addListener(object : TimelineListener {
                override fun onUpdate(diff: List<TimelineDiff>) {
                    apply(diff)
                    recompute()
                }
            })
            // Prime the live timeline so history appears on open. Without this the
            // timeline stays empty until some event (e.g. the user's own send)
            // triggers a diff.
            runCatching { tl.paginateBackwards(INITIAL_PAGE_COUNT.toUShort()) }

            // Sender display names + avatars (Phase 7, extended for avatars): a
            // one-time fetch of the already-synced member list — no network round
            // trip (member sync already happened for the app to be in this room),
            // no per-event cost. Off Dispatchers.IO like every other blocking SDK
            // call in this class (fetchMembers' own doc comment). Recompute once
            // more so any items mapped (raw MXID senderName, no avatar) before
            // this finished pick up the real name/avatar; a sender who joins
            // after this point keeps showing their MXID/no avatar until the room
            // is reopened — a deliberate scope cut, not a bug.
            runCatching { members = withContext(Dispatchers.IO) { fetchMembers(r) } }
            // Bug fix: this used to short-circuit to PinnedEventsCache.get()
            // whenever any RustRoomTimeline had ever populated it for this
            // room, in this process's lifetime — meaning a pin made from
            // another client (e.g. Element) never showed up here, even on
            // reopening the room, until the whole app process restarted
            // ("still not pulling pinned messages in that were pinned on
            // Element", on-device report). Every new instance now always
            // does a fresh cold read, and still seeds the cache from it —
            // both setPinned()'s own cache-preferring write baseline and the
            // updatesFor(roomId) subscription just below (for a sibling
            // screen already open) depend on the cache staying populated.
            val roomId = r.id()
            pinnedIds = runCatching { withContext(Dispatchers.IO) { fetchPinnedIds(r) } }
                .getOrDefault(emptySet())
                .also { PinnedEventsCache.put(roomId, it) }
            recompute()
            // The live fix: a pin/unpin made through a sibling RustRoomTimeline
            // for this same room shows here immediately, not just after this
            // instance is torn down and rebuilt. `scope` is the shared,
            // never-cancelled-per-instance scope RustMatrixSession already gives
            // every RustRoomTimeline (see the diff-listener/typing subscriptions
            // above), so this is the same lifecycle, not a new leak.
            scope.launch {
                PinnedEventsCache.updatesFor(roomId).collect { ids ->
                    pinnedIds = ids
                    recompute()
                }
            }

            typingHandle = runCatching {
                r.subscribeToTypingNotifications(object : TypingNotificationsListener {
                    override fun call(typingUserIds: List<String>) {
                        // The server usually omits us already; filter to be safe.
                        typingFlow.value = typingUserIds
                            .filter { it != ownUserId }
                            .map { UserId(it) }
                    }
                })
            }.getOrNull()
        }
    }

    // Every SDK call below is blocking FFI and must run off the main thread — the
    // callers launch on viewModelScope (Dispatchers.Main), so without withContext
    // these would block the UI thread and ANR (e.g. markRead firing on each new
    // message while the room is open).
    override suspend fun paginateBack(count: Int): Boolean = withContext(Dispatchers.IO) {
        runCatching { timeline?.paginateBackwards(count.toUShort()) }.getOrNull() ?: false
    }

    override suspend fun send(body: String) = withContext(Dispatchers.IO) {
        val tl = timeline ?: return@withContext
        runCatching { tl.send(messageEventContentFromMarkdown(body)) }
        Unit
    }

    override suspend fun editMessage(eventId: EventId, newBody: String) = withContext(Dispatchers.IO) {
        val tl = timeline ?: return@withContext
        runCatching {
            val content = org.matrix.rustcomponents.sdk.EditedContent.RoomMessage(
                messageEventContentFromMarkdown(newBody),
            )
            tl.edit(EventOrTransactionId.EventId(eventId.value), content)
        }
        Unit
    }

    override suspend fun sendTyping(isTyping: Boolean) = withContext(Dispatchers.IO) {
        val r = room ?: return@withContext
        runCatching { r.typingNotice(isTyping) }
        Unit
    }

    override suspend fun sendMedia(path: String, mimeType: String, kind: MediaKind, caption: String?) =
        withContext(Dispatchers.IO) {
            val tl = timeline ?: return@withContext
            val size = runCatching { java.io.File(path).length().toULong() }.getOrNull()
            // The SDK uploads from an UploadSource.File(path); each type takes its own
            // *Info record (all fields nullable) and returns a join handle to await.
            // sendImage/sendVideo take an optional thumbnail UploadSource (null here);
            // sendAudio/sendFile take none. Voice is stage 3 (sendVoiceMessage).
            val params = UploadParameters(
                source = UploadSource.File(path),
                caption = caption,
                formattedCaption = null,
                mentions = null,
                inReplyTo = null,
                extraContentJson = null,
            )
            runCatching {
                val handle = when (kind) {
                    MediaKind.IMAGE -> tl.sendImage(
                        params,
                        null,
                        ImageInfo(
                            height = null,
                            width = null,
                            mimetype = mimeType,
                            size = size,
                            thumbnailInfo = null,
                            thumbnailSource = null,
                            blurhash = null,
                            isAnimated = null,
                        ),
                    )
                    MediaKind.VIDEO -> tl.sendVideo(
                        params,
                        null,
                        VideoInfo(
                            duration = null,
                            height = null,
                            width = null,
                            mimetype = mimeType,
                            size = size,
                            thumbnailInfo = null,
                            thumbnailSource = null,
                            blurhash = null,
                        ),
                    )
                    MediaKind.AUDIO, MediaKind.VOICE -> tl.sendAudio(
                        params,
                        AudioInfo(duration = null, size = size, mimetype = mimeType),
                    )
                    MediaKind.FILE -> tl.sendFile(
                        params,
                        FileInfo(mimetype = mimeType, size = size, thumbnailInfo = null, thumbnailSource = null),
                    )
                }
                handle.join()
            }
            Unit
        }

    override suspend fun sendVoice(path: String, mimeType: String, durationMs: Long, waveform: List<Float>) =
        withContext(Dispatchers.IO) {
            val tl = timeline ?: return@withContext
            val size = runCatching { java.io.File(path).length().toULong() }.getOrNull()
            val params = UploadParameters(
                source = UploadSource.File(path),
                caption = null,
                formattedCaption = null,
                mentions = null,
                inReplyTo = null,
                extraContentJson = null,
            )
            val info = AudioInfo(
                duration = java.time.Duration.ofMillis(durationMs),
                size = size,
                mimetype = mimeType,
            )
            runCatching { tl.sendVoiceMessage(params, info, waveform).join() }
            Unit
        }

    override suspend fun markRead(eventId: EventId) = withContext(Dispatchers.IO) {
        // Read receipts are sent on the latest visible event by the SDK. This runs
        // on every new message while the room is open, so it MUST stay off main.
        runCatching { timeline?.markAsRead(org.matrix.rustcomponents.sdk.ReceiptType.READ) }
        Unit
    }

    override suspend fun toggleReaction(eventId: EventId, key: String) = withContext(Dispatchers.IO) {
        val tl = timeline ?: return@withContext
        runCatching { tl.toggleReaction(EventOrTransactionId.EventId(eventId.value), key) }
        Unit
    }

    override suspend fun setPinned(eventId: EventId, pinned: Boolean): Boolean = withContext(Dispatchers.IO) {
        val r = room ?: return@withContext false
        val result = runCatching {
            // Bug fix: this used to always re-read the baseline from a cold
            // room.roomInfo() SDK read, never the cache. That's stale by
            // construction for two pins issued close together (the SDK's
            // local room state only catches up once the server echoes the
            // *previous* write back down the sync loop) — pin A, then pin B
            // before that echo lands, and this cold read comes back missing
            // A, so the write below replaces the pinned list with [B] alone
            // ("2nd pin replaces the first", on-device report). Prefer the
            // cache — PinnedEventsCache.put() below runs synchronously right
            // after every successful local write, so it already reflects our
            // own just-completed pin with no round trip needed — falling
            // back to the cold read only when the cache has nothing for this
            // room yet. This doesn't fully close the window for a genuinely
            // concurrent pin/unpin from a *different* client (the SDK still
            // exposes no compare-and-swap for a state event), only for this
            // (same-client, sequential) case.
            val current = (PinnedEventsCache.get(r.id()) ?: fetchPinnedIds(r)).toList()
            val next = PinnedEventsContent.withEvent(current, eventId.value, pinned)
            r.sendStateEventRaw("m.room.pinned_events", "", PinnedEventsContent.toJson(next))
            pinnedIds = next.toSet()
            // Publish to the shared cache so every other RustRoomTimeline for
            // this room (a sibling screen already open, or one opened after
            // this write) sees the same truth — this is the actual
            // persistence fix; see PinnedEventsCache's doc comment.
            PinnedEventsCache.put(r.id(), pinnedIds)
        }
        // Bug fix: this used to be a bare runCatching with no signal back to
        // the caller — a rejected write (most likely: the sender lacks the
        // state_default power level m.room.pinned_events needs) silently
        // looked identical to success ("doesn't stick", on-device report).
        // recompute() still runs either way: on success it picks up the new
        // pinnedIds; on failure it just re-renders the unchanged state.
        recompute()
        result.isSuccess
    }

    private fun apply(diffs: List<TimelineDiff>) = synchronized(buffer) {
        diffs.forEach { diff ->
            when (diff) {
                is TimelineDiff.Append -> buffer.addAll(diff.values)
                is TimelineDiff.PushBack -> buffer.add(diff.value)
                is TimelineDiff.PushFront -> buffer.add(0, diff.value)
                is TimelineDiff.Insert -> buffer.add(diff.index.toInt(), diff.value)
                is TimelineDiff.Set -> buffer[diff.index.toInt()] = diff.value
                is TimelineDiff.Remove -> buffer.removeAt(diff.index.toInt())
                is TimelineDiff.PopBack -> if (buffer.isNotEmpty()) buffer.removeAt(buffer.lastIndex)
                is TimelineDiff.PopFront -> if (buffer.isNotEmpty()) buffer.removeAt(0)
                is TimelineDiff.Truncate -> buffer.subList(diff.length.toInt(), buffer.size).clear()
                is TimelineDiff.Reset -> {
                    buffer.clear()
                    buffer.addAll(diff.values)
                }
                is TimelineDiff.Clear -> buffer.clear()
            }
        }
    }

    private fun recompute() {
        val snapshot = synchronized(buffer) { buffer.toList() }
        itemsFlow.value = snapshot.mapNotNull { Mappers.toTimelineItem(it, members, ownUserId, pinnedIds) }
    }

    /** userId -> (displayName, avatarUrl) for every member with either set,
     *  paginated the same way as RustMatrixSession.roomMembers (a member with
     *  neither is simply omitted — Mappers.resolveSenderName's raw-ID fallback,
     *  and a null senderAvatarUrl, both cover them). room.members()/nextChunk()
     *  are themselves suspend functions (the actual compile error a non-suspend
     *  version of this hit — "should be called only from a coroutine" — not
     *  just "blocking FFI" as I'd assumed); must still be called off the main
     *  thread regardless — see the one call site. */
    private suspend fun fetchMembers(room: Room): Map<String, MemberInfo> {
        val out = mutableMapOf<String, MemberInfo>()
        runCatching {
            val iterator = room.members()
            while (true) {
                val chunk = iterator.nextChunk(MEMBER_PAGE_SIZE) ?: break
                if (chunk.isEmpty()) break
                chunk.forEach { m ->
                    if (m.displayName != null || m.avatarUrl != null) {
                        out[m.userId] = MemberInfo(m.displayName, m.avatarUrl)
                    }
                }
            }
            iterator.close()
        }
        return out
    }

    /** RoomInfo.pinnedEventIds is a plain field, already the full list — a
     *  cheap suspend (blocking FFI) call, no pagination like members(). */
    private suspend fun fetchPinnedIds(room: Room): Set<String> =
        runCatching { room.roomInfo().pinnedEventIds.toSet() }.getOrDefault(emptySet())

    private companion object {
        const val INITIAL_PAGE_COUNT = 20
        const val MEMBER_PAGE_SIZE: UInt = 50u
    }
}

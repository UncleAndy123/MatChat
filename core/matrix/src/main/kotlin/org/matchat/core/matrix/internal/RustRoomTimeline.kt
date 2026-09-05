package org.matchat.core.matrix.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.matchat.core.matrix.RoomTimeline
import org.matchat.core.model.EventId
import org.matchat.core.model.TimelineItem
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineItem as RustTimelineItem
import org.matrix.rustcomponents.sdk.TimelineListener
import org.matrix.rustcomponents.sdk.messageEventContentFromMarkdown

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
) : RoomTimeline {

    private val buffer = mutableListOf<RustTimelineItem>()
    private val itemsFlow = MutableStateFlow<List<TimelineItem>>(emptyList())
    private var timeline: Timeline? = null
    private var handle: TaskHandle? = null // held so the diff stream is not dropped

    override val items: Flow<List<TimelineItem>> = itemsFlow

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
        }
    }

    override suspend fun paginateBack(count: Int): Boolean =
        runCatching { timeline?.paginateBackwards(count.toUShort()) }.getOrNull() ?: false

    override suspend fun send(body: String) {
        val tl = timeline ?: return
        runCatching { tl.send(messageEventContentFromMarkdown(body)) }
    }

    override suspend fun markRead(eventId: EventId) {
        // Read receipts are sent on the latest visible event by the SDK.
        runCatching { timeline?.markAsRead(org.matrix.rustcomponents.sdk.ReceiptType.READ) }
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
        itemsFlow.value = snapshot.mapNotNull { Mappers.toTimelineItem(it) }
    }
}

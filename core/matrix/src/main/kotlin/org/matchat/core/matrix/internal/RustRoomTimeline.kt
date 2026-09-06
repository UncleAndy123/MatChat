package org.matchat.core.matrix.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.matchat.core.matrix.RoomTimeline
import org.matchat.core.model.EventId
import org.matchat.core.model.MediaKind
import org.matchat.core.model.TimelineItem
import org.matchat.core.model.UserId
import org.matrix.rustcomponents.sdk.AudioInfo
import org.matrix.rustcomponents.sdk.FileInfo
import org.matrix.rustcomponents.sdk.ImageInfo
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.UploadParameters
import org.matrix.rustcomponents.sdk.UploadSource
import org.matrix.rustcomponents.sdk.VideoInfo
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineItem as RustTimelineItem
import org.matrix.rustcomponents.sdk.TimelineListener
import org.matrix.rustcomponents.sdk.TypingNotificationsListener
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
    private val ownUserId: String?,
) : RoomTimeline {

    private val buffer = mutableListOf<RustTimelineItem>()
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

    override suspend fun paginateBack(count: Int): Boolean =
        runCatching { timeline?.paginateBackwards(count.toUShort()) }.getOrNull() ?: false

    override suspend fun send(body: String) {
        val tl = timeline ?: return
        runCatching { tl.send(messageEventContentFromMarkdown(body)) }
    }

    override suspend fun sendTyping(isTyping: Boolean) = withContext(Dispatchers.IO) {
        val r = room ?: return@withContext
        runCatching { r.typingNotice(isTyping) }
        Unit
    }

    override suspend fun sendMedia(
        path: String,
        mimeType: String,
        kind: MediaKind,
        caption: String?,
    ) = withContext(Dispatchers.IO) {
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
                        height = null, width = null, mimetype = mimeType, size = size,
                        thumbnailInfo = null, thumbnailSource = null, blurhash = null, isAnimated = null,
                    ),
                )
                MediaKind.VIDEO -> tl.sendVideo(
                    params,
                    null,
                    VideoInfo(
                        duration = null, height = null, width = null, mimetype = mimeType, size = size,
                        thumbnailInfo = null, thumbnailSource = null, blurhash = null,
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

    override suspend fun sendVoice(
        path: String,
        mimeType: String,
        durationMs: Long,
        waveform: List<Float>,
    ) = withContext(Dispatchers.IO) {
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

    private companion object {
        const val INITIAL_PAGE_COUNT = 20
    }
}

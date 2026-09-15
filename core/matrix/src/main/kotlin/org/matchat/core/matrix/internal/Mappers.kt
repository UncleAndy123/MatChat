package org.matchat.core.matrix.internal

import org.matchat.core.model.EventId
import org.matchat.core.model.MediaKind
import org.matchat.core.model.ReactionSummary
import org.matchat.core.model.RoomId
import org.matchat.core.model.RoomSummary
import org.matchat.core.model.SeenBy
import org.matchat.core.model.SendState
import org.matchat.core.model.TimelineItem
import org.matchat.core.model.UserId
import org.matrix.rustcomponents.sdk.EventOrTransactionId
import org.matrix.rustcomponents.sdk.MediaSource
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.TimelineItemContent
import org.matrix.rustcomponents.sdk.TimelineItem as RustTimelineItem

/** A room member's already-synced name/avatar (RustRoomTimeline.fetchMembers,
 *  RustMatrixSession.roomMembers) — either field may be null when the server
 *  hasn't published one. */
internal data class MemberInfo(val displayName: String?, val avatarUrl: String?)

/**
 * SDK types -> :core:model types. The only mapping layer; if the SDK changes
 * shape on upgrade, this file is what breaks, by design (ARCHITECTURE.md).
 */
internal object Mappers {

    suspend fun toRoomSummary(room: Room): RoomSummary {
        val info = runCatching { room.roomInfo() }.getOrNull()
        val encrypted = runCatching { room.isEncrypted() }.getOrDefault(true)
        return RoomSummary(
            id = RoomId(room.id()),
            name = info?.displayName ?: room.displayName() ?: room.id(),
            // Last-message preview needs the latest-event API — a follow-up.
            lastMessage = null,
            lastActivityEpochMs = null,
            unreadCount = (info?.numUnreadMessages ?: 0uL).toInt(),
            isEncrypted = encrypted,
            avatarUrl = info?.avatarUrl ?: runCatching { room.avatarUrl() }.getOrNull(),
            hasActiveCall = runCatching { info?.hasRoomCall }.getOrNull() ?: false,
        )
    }

    /**
     * Maps one SDK timeline item to a domain [TimelineItem], or null for items we
     * do not render (state changes, virtual dividers). Only text messages are
     * mapped in this step.
     *
     * EventTimelineItem is a uniffi Record, so its fields are properties. The body
     * path is content -> MsgLike.content.kind -> Message.content.body.
     *
     * [members] is the room's already-synced member list (userId -> name/avatar;
     * RustRoomTimeline fetches it once via fetchMembers(), no per-event network
     * round trip) — resolveSenderName falls back to the raw Matrix ID for a
     * sender not yet in it (e.g. joined mid-conversation before the next
     * member-list refresh), never a blank name; a missing avatar is simply null.
     * [ownUserId] resolves each reaction's reactedByMe (Reactions round).
     * [pinnedIds] is the room's current m.room.pinned_events list (Pinned
     * messages round), fetched the same one-shot way as [members].
     */
    fun toTimelineItem(
        item: RustTimelineItem,
        members: Map<String, MemberInfo>,
        ownUserId: String?,
        pinnedIds: Set<String> = emptySet(),
    ): TimelineItem? {
        val event = item.asEvent() ?: return null
        val msgLike = event.content as? TimelineItemContent.MsgLike ?: return null
        val messageKind = msgLike.content.kind as? MsgLikeKind.Message ?: return null
        val eventId = eventIdOf(event.eventOrTransactionId)
        val isPinned = eventId in pinnedIds
        // "Seen by" (Avatars round): every read-receipt holder other than the
        // sender and the current user — already a full user-id list on the
        // SDK side (Map<String, Receipt>), not just a count; readByOther is
        // kept as its own bool for isRead's cheap single-glyph check rather
        // than reading seenBy.isEmpty() in the hot render path. Shown on
        // both own and received messages (seen-by-on-received round) — "who
        // else has read this," never including the sender or me.
        val seenBy = runCatching {
            event.readReceipts.keys
                .filter { it != event.sender && it != ownUserId }
                .map { SeenBy(UserId(it), members[it]?.avatarUrl, members[it]?.displayName) }
        }.getOrDefault(emptyList())
        val readByOther = seenBy.isNotEmpty()
        val senderAvatarUrl = members[event.sender]?.avatarUrl
        // Reactions (Reactions round): already sitting on the same MsgLikeContent
        // this function already destructures for .kind — Reaction(key, senders),
        // so a count + "did I react" is a direct map, no extra SDK call.
        val reactions = runCatching {
            msgLike.content.reactions.map { r ->
                ReactionSummary(
                    key = r.key,
                    count = r.senders.size,
                    reactedByMe = ownUserId != null && r.senders.any { it.senderId == ownUserId },
                    senderNames = r.senders.map { resolveSenderName(it.senderId, members) },
                )
            }
        }.getOrDefault(emptyList())

        val media = mediaOf(
            messageKind.content.msgType, eventId, event, readByOther, members,
            senderAvatarUrl, seenBy, reactions, isPinned,
        )
        if (media != null) return media

        return TimelineItem.Message(
            eventId = EventId(eventId),
            sender = UserId(event.sender),
            senderName = resolveSenderName(event.sender, members),
            body = messageKind.content.body,
            timestampEpochMs = event.timestamp.toLong(),
            isOwn = event.isOwn,
            sendState = SendState.SENT,
            isRead = readByOther,
            senderAvatarUrl = senderAvatarUrl,
            seenBy = seenBy,
            reactions = reactions,
            isPinned = isPinned,
        )
    }

    /** The raw Matrix ID (`@user:server`) is always a safe fallback — never blank,
     *  never a network call — for a sender [members] doesn't (yet) know. */
    internal fun resolveSenderName(rawSenderId: String, members: Map<String, MemberInfo>): String =
        members[rawSenderId]?.displayName ?: rawSenderId

    /** Media messages (image/video/audio/voice/file). Registers the MediaSource
     *  so the download-by-id path can reach it. Returns null for text-like types. */
    @Suppress("CyclomaticComplexMethod", "LongParameterList")
    private fun mediaOf(
        type: MessageType,
        eventId: String,
        event: org.matrix.rustcomponents.sdk.EventTimelineItem,
        isRead: Boolean,
        members: Map<String, MemberInfo>,
        senderAvatarUrl: String?,
        seenBy: List<SeenBy>,
        reactions: List<ReactionSummary>,
        isPinned: Boolean,
    ): TimelineItem.Media? {
        val (kind, source, filename, caption, mime, size, durationMs, waveform) = when (type) {
            is MessageType.Image -> Media6(
                MediaKind.IMAGE,
                type.content.source,
                type.content.filename,
                type.content.caption,
                type.content.info?.mimetype,
                type.content.info?.size?.toLong(),
                null,
                null,
            )
            is MessageType.Video -> Media6(
                MediaKind.VIDEO,
                type.content.source,
                type.content.filename,
                type.content.caption,
                type.content.info?.mimetype,
                type.content.info?.size?.toLong(),
                null,
                null,
            )
            is MessageType.Audio -> Media6(
                if (type.content.voice != null) MediaKind.VOICE else MediaKind.AUDIO,
                type.content.source,
                type.content.filename,
                type.content.caption,
                type.content.info?.mimetype,
                type.content.info?.size?.toLong(),
                runCatching {
                    type.content.info?.duration?.toMillis() ?: type.content.audio?.duration?.toMillis()
                }.getOrNull(),
                waveformOf(type),
            )
            is MessageType.File -> Media6(
                MediaKind.FILE,
                type.content.source,
                type.content.filename,
                type.content.caption,
                type.content.info?.mimetype,
                type.content.info?.size?.toLong(),
                null,
                null,
            )
            else -> return null
        }
        MediaRegistry.put(eventId, source)
        return TimelineItem.Media(
            eventId = EventId(eventId),
            sender = UserId(event.sender),
            senderName = resolveSenderName(event.sender, members),
            body = caption ?: filename,
            timestampEpochMs = event.timestamp.toLong(),
            isOwn = event.isOwn,
            sendState = SendState.SENT,
            kind = kind,
            filename = filename,
            caption = caption,
            mimeType = mime,
            sizeBytes = size,
            durationMs = durationMs,
            isRead = isRead,
            senderAvatarUrl = senderAvatarUrl,
            seenBy = seenBy,
            reactions = reactions,
            isPinned = isPinned,
            waveform = waveform,
        )
    }

    /** NOTE: `.waveform`'s exact element type (UShort/UInt/Int, per the
     *  UniFFI-generated binding for `org.matrix.rustcomponents.sdk`) is
     *  unverified against a real build in this environment — `.toInt()` is
     *  used because it's a valid extension on every built-in Kotlin integer
     *  type, so this compiles regardless of which one it actually is; only
     *  the property name `content.audio?.waveform` itself needs confirming
     *  (its sibling `content.audio?.duration` immediately above already
     *  compiles today, which is strong evidence this same object also
     *  exposes the spec's other field). The actual normalization math lives
     *  in [normalizeWaveform], a plain-Int function so it's testable without
     *  needing an SDK type at all. */
    private fun waveformOf(type: MessageType.Audio): List<Float>? =
        runCatching { type.content.audio?.waveform?.map { it.toInt() } }.getOrNull()?.let(::normalizeWaveform)

    /** MSC3245's waveform is a list of integers 0..1000; normalize to the
     *  same 0f..1f range [org.matchat.feature.timeline.VoiceRecorder] already
     *  produces on the sending side, so incoming and locally-recorded
     *  waveforms render through one shared scale. Clamped defensively —
     *  the spec requires 0..1000, but never trust a value from the wire
     *  (could be another client's bug, or a hostile homeserver). */
    internal fun normalizeWaveform(raw: List<Int>): List<Float> = raw.map { it.coerceIn(0, 1000) / 1000f }

    /** Small carrier so the media `when` can destructure its columns (a data
     *  class provides component1..8 automatically). */
    private data class Media6(
        val kind: MediaKind,
        val source: MediaSource,
        val filename: String,
        val caption: String?,
        val mime: String?,
        val size: Long?,
        val durationMs: Long?,
        val waveform: List<Float>?,
    )

    private fun eventIdOf(id: EventOrTransactionId): String = (id as? EventOrTransactionId.EventId)?.eventId.orEmpty()
}

package org.matchat.core.model

/**
 * A joined room, as shown on the room list (S8). All values are already
 * resolved by [org.matchat.core.matrix]; the UI never derives them.
 *
 * Note the timestamp is an epoch millis Long, not a `java.time` type:
 * :core:model depends on nothing, so it cannot use desugared time. Formatting
 * to a display string ("3:42 PM") happens in a ViewModel.
 */
data class RoomSummary(
    val id: RoomId,
    val name: String,
    val lastMessage: String?,
    val lastActivityEpochMs: Long?,
    val unreadCount: Int,
    val isEncrypted: Boolean,
    /** An `mxc://` URI, or null for no room avatar set. The UI downloads and
     *  decodes it by id (MatrixSession.loadAvatar) — no SDK type crosses this
     *  line, same rule as everything else in :core:model. */
    val avatarUrl: String? = null,
    /** True when a MatrixRTC call is active in this room — the ring pipeline's
     *  signal (docs/VOICE.md §5), read from RoomInfo.hasRoomCall. */
    val hasActiveCall: Boolean = false,
    /** Display name of whoever sent [lastMessage] (raw Matrix ID when no name
     *  is known), or null when there is no latest message. */
    val lastMessageSender: String? = null,
    /** True when the latest message is our own (never notified). */
    val lastMessageIsOwn: Boolean = false,
    /** Set when the latest message is media; [lastMessage] is then null and the
     *  UI shows a label for the kind ("Photo", "Voice message"…). */
    val lastMessageMedia: MediaKind? = null,
)

/** An invitation (a room whose membership state is Invited). See S18/S19. */
data class InviteSummary(
    val roomId: RoomId,
    val roomName: String,
    val inviter: UserId,
    // null until the profile lookup resolves
    val inviterName: String?,
    val isDirect: Boolean,
    val isEncrypted: Boolean,
    // shown to the user, and checked against policy
    val senderDomain: String,
    // false => the screen offers Decline only
    val allowedByPolicy: Boolean,
)

/** A profile fetched by a lookup of a known address — never a search (AGENTS.md §0). */
data class Profile(
    val userId: UserId,
    val displayName: String?,
)

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
)

/** An invitation (a room whose membership state is Invited). See S18/S19. */
data class InviteSummary(
    val roomId: RoomId,
    val roomName: String,
    val inviter: UserId,
    val inviterName: String?, // null until the profile lookup resolves
    val isDirect: Boolean,
    val isEncrypted: Boolean,
    val senderDomain: String, // shown to the user, and checked against policy
    val allowedByPolicy: Boolean, // false => the screen offers Decline only
)

/** A profile fetched by a lookup of a known address — never a search (AGENTS.md §0). */
data class Profile(
    val userId: UserId,
    val displayName: String?,
)

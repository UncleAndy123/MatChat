package org.matchat.core.ui.nav

import org.matchat.core.model.EventId
import org.matchat.core.model.RoomId
import org.matchat.core.model.UserId

/**
 * Cross-screen navigation. Features depend on this interface, never on each
 * other (AGENTS.md §2); :app implements it once over Jetpack Navigation. A
 * Fragment collects its ViewModel's one-shot nav events and calls these.
 */
interface Navigator {
    fun toSignIn()

    /** After a successful sign-in: room list becomes the root, onboarding is popped. */
    fun toRoomListRoot()

    /** After sign-out: welcome becomes the root, everything else is cleared. */
    fun toWelcomeRoot()
    fun toRoom(roomId: RoomId)

    /** Full-screen image viewer for a timeline image (D-pad pan, * / # zoom). */
    fun toImageViewer(eventId: EventId)

    /** Room info + basic room edits (S12). */
    fun toRoomInfo(roomId: RoomId)
    /** Room info > Pinned messages (Pinned messages round). */
    fun toPinnedMessages(roomId: RoomId)
    /** Message info (S11): metadata for one event, with a link to the sender.
     *  [roomId] lets the screen look up this event's live reactions (who
     *  reacted, by name — a follow-up to the Reactions round). */
    fun toMessageInfo(roomId: RoomId, eventId: EventId, senderId: UserId, timestampEpochMs: Long)
    /** A sender's profile, reached from message info. */
    fun toProfile(userId: UserId)

    /** Voice call screen (docs/VOICE.md). [incoming] true rings; false dials out. */
    fun toCall(roomId: RoomId, peerName: String?, incoming: Boolean)
    fun toInvites()
    fun toInvite(roomId: RoomId)
    fun toNewChat()
    fun toTypeAddress()
    fun toVerification()
    fun toSettings()
    fun toTheme()
    /** Settings > Text size: Normal/Large (UX-SPEC §S16). */
    fun toTextSize()

    /** Settings > Advanced (Phase 6, UI improvement plan): softkey swap. */
    fun toAdvanced()

    /** Settings > Notifications: on/off + sound. */
    fun toNotifications()
    fun toPolicy()

    /** Settings > Software update: in-app updater over GitHub Releases. */
    fun toUpdate()
    fun toHelp()
    fun back()
}

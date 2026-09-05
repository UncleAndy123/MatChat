package org.matchat.core.ui.nav

import org.matchat.core.model.RoomId

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
    fun toInvites()
    fun toInvite(roomId: RoomId)
    fun toNewChat()
    fun toTypeAddress()
    fun toVerification()
    fun toSettings()
    fun toPolicy()
    fun toHelp()
    fun back()
}

package org.matchat.client.contacts

import org.matchat.core.contacts.ContactsRepository
import org.matchat.core.model.RoomSummary
import org.matchat.core.model.UserId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Saves everyone the user has a direct (1-to-1) chat with as a contact
 * (PLAN.md §6.10), whoever started the chat. Fed every room-list update by
 * [org.matchat.client.sync.SyncOwner]. A contact the user removed is not
 * re-added, and a name the user already has is never overwritten
 * ([ContactsRepository.autoSave]). Only people already in a room with the user
 * — never a lookup (AGENTS.md §0).
 */
@Singleton
class DirectChatContactSaver @Inject constructor(
    private val contacts: ContactsRepository,
) {
    // Partners already handed to the repository this process, so a busy room
    // list doesn't re-check every DM on every update.
    private val handled = HashSet<UserId>()

    suspend fun onRooms(rooms: List<RoomSummary>) {
        rooms.forEach { room ->
            val partner = room.directUserId ?: return@forEach
            if (handled.add(partner)) contacts.autoSave(partner, room.name.takeIf { it.isNotBlank() })
        }
    }
}

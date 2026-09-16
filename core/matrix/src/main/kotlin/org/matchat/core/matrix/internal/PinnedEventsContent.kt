package org.matchat.core.matrix.internal

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the `m.room.pinned_events` state-event content (`{"pinned": [...]}`)
 * — the SDK has no dedicated pin/unpin method (confirmed: none of Room's
 * 104 public methods is pin-named), so this app owns the state event
 * directly via the already-used Room.sendStateEventRaw, the same way
 * core/rtc's CallMembership builds `m.call.member` content. Pure/testable
 * (AGENTS.md §6) — no SDK call in here.
 */
internal object PinnedEventsContent {
    private const val KEY = "pinned"

    /** [current] with [eventId] added (if [pinned]) or removed, de-duplicated
     *  and order-preserving. */
    fun withEvent(current: List<String>, eventId: String, pinned: Boolean): List<String> = if (pinned) {
        if (eventId in current) current else current + eventId
    } else {
        current - eventId
    }

    fun toJson(eventIds: List<String>): String = JSONObject().put(KEY, JSONArray(eventIds)).toString()
}

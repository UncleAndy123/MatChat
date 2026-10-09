package org.matchat.core.testing

import kotlinx.coroutines.flow.MutableStateFlow
import org.matchat.core.model.RoomId
import org.matchat.core.model.notify.RoomNotificationSounds
import org.matchat.core.model.notify.RoomSound

/** In-memory [RoomNotificationSounds] with the same version rule as the real one. */
class FakeRoomNotificationSounds(initial: Map<RoomId, RoomSound> = emptyMap()) : RoomNotificationSounds {
    override val overrides = MutableStateFlow(initial)
    private val versions = initial.mapValues { it.value.version }.toMutableMap()

    override suspend fun set(roomId: RoomId, uri: String?) {
        val next = (versions[roomId] ?: 0) + 1
        versions[roomId] = next
        overrides.value =
            if (uri == null) overrides.value - roomId else overrides.value + (roomId to RoomSound(uri, next))
    }
}

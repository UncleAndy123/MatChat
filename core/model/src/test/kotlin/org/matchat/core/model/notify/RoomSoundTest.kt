package org.matchat.core.model.notify

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RoomSoundTest {

    private val systemDefault = "content://settings/system/notification_sound"

    @Test
    fun `cancelling keeps the current choice`() {
        assertEquals("content://a", RoomSoundPick.resolve(null, cancelled = true, "content://a", systemDefault))
        assertNull(RoomSoundPick.resolve(null, cancelled = true, current = null, systemDefault))
    }

    @Test
    fun `picking silent stores the silent marker`() {
        assertEquals(SILENT_SOUND, RoomSoundPick.resolve(null, cancelled = false, current = null, systemDefault))
    }

    @Test
    fun `picking Default means the app sound`() {
        assertNull(RoomSoundPick.resolve(systemDefault, cancelled = false, "content://a", systemDefault))
    }

    @Test
    fun `picking a sound stores it`() {
        assertEquals(
            "content://media/1",
            RoomSoundPick.resolve("content://media/1", cancelled = false, current = null, systemDefault),
        )
    }

    @Test
    fun `choice follows the override`() {
        assertEquals(RoomSoundChoice.AppDefault, RoomSoundChoice.of(null))
        assertEquals(RoomSoundChoice.Silent, RoomSoundChoice.of(RoomSound(SILENT_SOUND, 1)))
        assertEquals(RoomSoundChoice.Custom("content://a"), RoomSoundChoice.of(RoomSound("content://a", 2)))
    }
}

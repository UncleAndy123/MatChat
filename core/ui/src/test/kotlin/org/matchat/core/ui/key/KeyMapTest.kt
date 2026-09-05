package org.matchat.core.ui.key

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit coverage for the pure parts of the key map (keycode int constants are
 * inlined at compile time, so this needs no Android runtime). Full dispatch of
 * live KeyEvents is exercised by the instrumented traversal suite (PLAN.md §8.1).
 */
class KeyMapTest {
    @Test
    fun `hash and star hold map to their actions`() {
        assertEquals(LogicalKey.HASH_HOLD, KeyMap.holdKey(KeyEvent.KEYCODE_POUND))
        assertEquals(LogicalKey.STAR_HOLD, KeyMap.holdKey(KeyEvent.KEYCODE_STAR))
    }

    @Test
    fun `other codes are not hold actions`() {
        assertNull(KeyMap.holdKey(KeyEvent.KEYCODE_DPAD_CENTER))
    }
}

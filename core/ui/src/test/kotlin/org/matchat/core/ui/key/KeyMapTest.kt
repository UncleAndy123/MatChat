package org.matchat.core.ui.key

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit coverage for the key map. holdKey takes a raw keycode Int (inlined at
 * compile time, no Android runtime needed); map(event, swapped) takes a real
 * KeyEvent, so this class runs under Robolectric for that — same convention
 * as ThemeColorTest/SharedPreferencesUserPreferencesTest. Full dispatch of
 * live KeyEvents is exercised by the instrumented traversal suite
 * (PLAN.md §8.1).
 */
@RunWith(RobolectricTestRunner::class)
class KeyMapTest {
    @Test
    fun `hash and star hold map to their actions`() {
        assertEquals(LogicalKey.HASH_HOLD, KeyMap.holdKey(KeyEvent.KEYCODE_POUND))
        assertEquals(LogicalKey.STAR_HOLD, KeyMap.holdKey(KeyEvent.KEYCODE_STAR))
    }

    @Test
    fun `held CENTER or ENTER maps to CENTER_HOLD`() {
        assertEquals(LogicalKey.CENTER_HOLD, KeyMap.holdKey(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(LogicalKey.CENTER_HOLD, KeyMap.holdKey(KeyEvent.KEYCODE_ENTER))
    }

    @Test
    fun `other codes are not hold actions`() {
        assertNull(KeyMap.holdKey(KeyEvent.KEYCODE_UNKNOWN))
    }

    @Test
    fun `a plain (non-long) press of CENTER still maps instantly, unaffected by the hold addition`() {
        assertEquals(LogicalKey.CENTER, KeyMap.map(down(KeyEvent.KEYCODE_DPAD_CENTER), swapped = false))
    }

    // --- map(event, swapped) — Phase 6, UI improvement plan (docs/adr/0007) ---

    private fun down(keyCode: Int) = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)

    @Test
    fun `unswapped, LEFT-position codes map to SOFT_LEFT and RIGHT-position codes to SOFT_RIGHT`() {
        assertEquals(LogicalKey.SOFT_LEFT, KeyMap.map(down(KeyEvent.KEYCODE_SOFT_LEFT), swapped = false))
        assertEquals(LogicalKey.SOFT_LEFT, KeyMap.map(down(KeyEvent.KEYCODE_MENU), swapped = false))
        assertEquals(LogicalKey.SOFT_RIGHT, KeyMap.map(down(KeyEvent.KEYCODE_SOFT_RIGHT), swapped = false))
        assertEquals(LogicalKey.SOFT_RIGHT, KeyMap.map(down(KeyEvent.KEYCODE_BACK), swapped = false))
    }

    @Test
    fun `swapped, the two positional codes flip`() {
        assertEquals(LogicalKey.SOFT_RIGHT, KeyMap.map(down(KeyEvent.KEYCODE_SOFT_LEFT), swapped = true))
        assertEquals(LogicalKey.SOFT_LEFT, KeyMap.map(down(KeyEvent.KEYCODE_SOFT_RIGHT), swapped = true))
    }

    @Test
    fun `MENU always means Options, never swapped`() {
        // A dedicated Options key, like BACK is a dedicated Back key.
        assertEquals(LogicalKey.SOFT_LEFT, KeyMap.map(down(KeyEvent.KEYCODE_MENU), swapped = false))
        assertEquals(LogicalKey.SOFT_LEFT, KeyMap.map(down(KeyEvent.KEYCODE_MENU), swapped = true))
    }

    @Test
    fun `only MENU is kept away from the platform for its whole press`() {
        assertEquals(true, KeyMap.isMenuKey(KeyEvent.KEYCODE_MENU))
        assertEquals(false, KeyMap.isMenuKey(KeyEvent.KEYCODE_SOFT_LEFT))
        assertEquals(false, KeyMap.isMenuKey(KeyEvent.KEYCODE_SOFT_RIGHT))
        assertEquals(false, KeyMap.isMenuKey(KeyEvent.KEYCODE_BACK))
    }

    // --- detectSoftkeys — automatic layout (docs/adr/0007, "Auto-detection") ---

    @Test
    fun `a MENU key puts Options on the left`() {
        val d = KeyMap.detectSoftkeys(KeyEvent.KEYCODE_MENU, SoftkeyKind.NONE)
        assertEquals(SoftkeyDetection(SoftkeyKind.MENU, swapped = false), d)
    }

    @Test
    fun `a right softkey puts Options on the right`() {
        val d = KeyMap.detectSoftkeys(KeyEvent.KEYCODE_SOFT_RIGHT, SoftkeyKind.NONE)
        assertEquals(SoftkeyDetection(SoftkeyKind.SOFT_RIGHT, swapped = true), d)
    }

    @Test
    fun `a MENU key on a phone that showed a right softkey moves Options left`() {
        val d = KeyMap.detectSoftkeys(KeyEvent.KEYCODE_MENU, SoftkeyKind.SOFT_RIGHT)
        assertEquals(SoftkeyDetection(SoftkeyKind.MENU, swapped = false), d)
    }

    @Test
    fun `the same kind again changes nothing, so a manual choice stands`() {
        assertNull(KeyMap.detectSoftkeys(KeyEvent.KEYCODE_MENU, SoftkeyKind.MENU))
        assertNull(KeyMap.detectSoftkeys(KeyEvent.KEYCODE_SOFT_RIGHT, SoftkeyKind.SOFT_RIGHT))
    }

    @Test
    fun `once MENU is seen a right softkey stays Back, so the layout never flip-flops`() {
        assertNull(KeyMap.detectSoftkeys(KeyEvent.KEYCODE_SOFT_RIGHT, SoftkeyKind.MENU))
    }

    @Test
    fun `other keys never change the layout`() {
        listOf(
            KeyEvent.KEYCODE_SOFT_LEFT,
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_5,
        ).forEach { code ->
            SoftkeyKind.entries.forEach { seen -> assertNull(KeyMap.detectSoftkeys(code, seen)) }
        }
    }

    @Test
    fun `swapped, KEYCODE_BACK still means Back, never Options`() {
        // The confirmed bug this guards: a dedicated hardware Back key (distinct
        // from the two labeled positional softkeys) got swapped to Options along
        // with them. It must always resolve to SOFT_RIGHT (Back's LogicalKey).
        assertEquals(LogicalKey.SOFT_RIGHT, KeyMap.map(down(KeyEvent.KEYCODE_BACK), swapped = true))
        assertEquals(LogicalKey.SOFT_RIGHT, KeyMap.map(down(KeyEvent.KEYCODE_BACK), swapped = false))
    }

    @Test
    fun `swap never touches a non-softkey code`() {
        // Table test across every other mapped key: swapped must equal unswapped.
        // KEYCODE_BACK is deliberately not in this list — it's covered by its own
        // test above, and it's the one code that's exempt for a different reason
        // (always Back) than these (not a softkey at all).
        val codes = listOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_CALL, KeyEvent.KEYCODE_ENDCALL,
            KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_3,
            KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_7,
            KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_9,
        )
        codes.forEach { code ->
            assertEquals(
                "code $code should map the same regardless of swap",
                KeyMap.map(down(code), swapped = false),
                KeyMap.map(down(code), swapped = true),
            )
        }
    }

    @Test
    fun `an unmapped code stays null regardless of swap`() {
        assertNull(KeyMap.map(down(KeyEvent.KEYCODE_UNKNOWN), swapped = false))
        assertNull(KeyMap.map(down(KeyEvent.KEYCODE_UNKNOWN), swapped = true))
    }
}

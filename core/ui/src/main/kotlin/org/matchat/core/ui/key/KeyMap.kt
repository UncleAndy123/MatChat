package org.matchat.core.ui.key

import android.view.KeyEvent

/**
 * The single translation from raw [KeyEvent] codes to [LogicalKey]. Softkey
 * codes vary by device — KEYCODE_SOFT_LEFT/SOFT_RIGHT are frequently never
 * dispatched, and the keys arrive as KEYCODE_MENU / KEYCODE_BACK or an OEM
 * private code (PLAN.md risk table, AGENTS.md §4). This table is per-device data,
 * established by the M0 key-logger spike on real hardware. Nothing outside this
 * file reads a keycode.
 *
 * Long-press (`#`, `*`) is resolved by the caller tracking down/up time; [map]
 * handles the single-press codes and the D-pad.
 */
object KeyMap {

    /** Returns the logical key for a key-DOWN event, or null to fall through. */
    fun map(event: KeyEvent): LogicalKey? = when (event.keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> LogicalKey.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> LogicalKey.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> LogicalKey.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> LogicalKey.RIGHT
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> LogicalKey.CENTER

        // LEFT softkey = Options. Most AOSP flips deliver it as MENU; some as the
        // legacy SOFT_LEFT. Add a SKU's OEM code here, never in a feature module.
        KeyEvent.KEYCODE_SOFT_LEFT, KeyEvent.KEYCODE_MENU -> LogicalKey.SOFT_LEFT

        // RIGHT softkey = Back. Delivered as BACK on most SKUs; SOFT_RIGHT on some.
        KeyEvent.KEYCODE_SOFT_RIGHT, KeyEvent.KEYCODE_BACK -> LogicalKey.SOFT_RIGHT

        KeyEvent.KEYCODE_0 -> LogicalKey.DIGIT_0
        KeyEvent.KEYCODE_1 -> LogicalKey.DIGIT_1
        KeyEvent.KEYCODE_2 -> LogicalKey.DIGIT_2
        KeyEvent.KEYCODE_3 -> LogicalKey.DIGIT_3
        KeyEvent.KEYCODE_4 -> LogicalKey.DIGIT_4
        KeyEvent.KEYCODE_5 -> LogicalKey.DIGIT_5
        KeyEvent.KEYCODE_6 -> LogicalKey.DIGIT_6
        KeyEvent.KEYCODE_7 -> LogicalKey.DIGIT_7
        KeyEvent.KEYCODE_8 -> LogicalKey.DIGIT_8
        KeyEvent.KEYCODE_9 -> LogicalKey.DIGIT_9

        else -> null
    }

    /** Codes that a long-press turns into a hold action. */
    fun holdKey(keyCode: Int): LogicalKey? = when (keyCode) {
        KeyEvent.KEYCODE_POUND -> LogicalKey.HASH_HOLD
        KeyEvent.KEYCODE_STAR -> LogicalKey.STAR_HOLD
        else -> null
    }
}

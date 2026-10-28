package org.matchat.core.ui.key

import android.view.KeyEvent

/**
 * The single translation from raw [KeyEvent] codes to [LogicalKey]. Softkey
 * codes vary by device — KEYCODE_SOFT_LEFT/SOFT_RIGHT are frequently never
 * dispatched, and the keys arrive as KEYCODE_MENU / KEYCODE_BACK or an OEM
 * private code (PLAN.md risk table, AGENTS.md §4). This table is per-device data,
 * established by the M0 key-logger spike on real hardware. Nothing outside this
 * file reads a keycode *to decide what it logically means* — the one narrow
 * exception is `MatChatKeyAccessibilityService` (app/accessibility, docs/adr/0007
 * addendum), which reads a raw keycode only to decide whether to intercept it
 * at all (a plain equality check against KEYCODE_SOFT_RIGHT), before handing
 * any actually-claimed event straight back into [map] for the real
 * translation — it never derives a [LogicalKey] itself.
 *
 * Long-press (`#`, `*`) is resolved by the caller tracking down/up time; [map]
 * handles the single-press codes and the D-pad.
 */
object KeyMap {

    /** Returns the logical key for a key-DOWN event, or null to fall through.
     *  [swapped] (Settings > Advanced > "Swap Left/Right keys", Phase 6 of the
     *  UI improvement plan; docs/adr/0007) flips SOFT_LEFT/SOFT_RIGHT at the
     *  end, after the normal per-device keycode table below — a device whose
     *  hardware softkeys are physically reversed still maps LEFT-position to
     *  Options semantically everywhere past this function, just via the
     *  opposite raw keycode.
     *
     *  KEYCODE_BACK is handled separately, first, and NEVER swapped: on
     *  hardware with a dedicated Back key (distinct from the two labeled
     *  positional softkeys), that key must always mean Back — it isn't one
     *  of the two things the swap preference is about. A confirmed bug had
     *  it swapping to Options along with the positional keys, since the
     *  per-device table below originally folded BACK into the same
     *  LogicalKey as the right softkey position (true before the swap
     *  preference existed, since they meant the same thing; no longer true
     *  once "the right position" and "always Back" can disagree). */
    fun map(event: KeyEvent, swapped: Boolean = false): LogicalKey? {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return LogicalKey.SOFT_RIGHT
        // MENU is a dedicated Options key wherever it exists (Sonim's Options
        // key; the left softkey on AOSP flips that send it), so — like BACK —
        // it always means Options and is never swapped. Seeing it moves the
        // "Options" label to the left ([detectSoftkeys]).
        if (event.keyCode == KeyEvent.KEYCODE_MENU) return LogicalKey.SOFT_LEFT
        val logical = mapRaw(event) ?: return null
        return if (swapped) swapSoftkeys(logical) else logical
    }

    private fun swapSoftkeys(key: LogicalKey): LogicalKey = when (key) {
        LogicalKey.SOFT_LEFT -> LogicalKey.SOFT_RIGHT
        LogicalKey.SOFT_RIGHT -> LogicalKey.SOFT_LEFT
        else -> key
    }

    private fun mapRaw(event: KeyEvent): LogicalKey? = when (event.keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> LogicalKey.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> LogicalKey.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> LogicalKey.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> LogicalKey.RIGHT
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> LogicalKey.CENTER

        // LEFT softkey position (KEYCODE_MENU is handled in map(): always
        // Options). Add a SKU's OEM code here, never in a feature module.
        KeyEvent.KEYCODE_SOFT_LEFT -> LogicalKey.SOFT_LEFT

        // RIGHT softkey position. KEYCODE_BACK is NOT handled here — see map()'s
        // doc comment: it's a separate, never-swapped, always-Back case.
        KeyEvent.KEYCODE_SOFT_RIGHT -> LogicalKey.SOFT_RIGHT

        // Hardware call keys (docs/VOICE.md §6). Present on these feature phones;
        // only the call screens act on them, elsewhere they fall through.
        KeyEvent.KEYCODE_CALL -> LogicalKey.CALL
        KeyEvent.KEYCODE_ENDCALL -> LogicalKey.END

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

    /**
     * Automatic softkey layout, like FlipWeather (docs/adr/0007, "Auto-detection"):
     * a phone with a MENU key gets Options on the LEFT; one with a right softkey
     * (`KEYCODE_SOFT_RIGHT`) gets Options on the RIGHT, the default. A Back key
     * pressed before any softkey also moves Options LEFT: on a phone whose right
     * softkey sends `KEYCODE_BACK` (the ZTE Cymbal 2, issue #44) Options on the
     * right is unreachable. On a phone that has a right softkey as well, the
     * first right-softkey press moves it back. Given a key-DOWN's [keyCode] and
     * the [seen] kind stored so far, returns the new kind and layout to apply,
     * or null when nothing changes.
     *
     * It only acts when the phone shows something different, so a manual choice
     * in Settings > Advanced stands until then. MENU is final: once seen, a
     * right softkey on the same phone stays Back (the label it shows there), so
     * a phone with both keys can't flip the layout back and forth.
     */
    fun detectSoftkeys(keyCode: Int, seen: SoftkeyKind): SoftkeyDetection? = when {
        keyCode == KeyEvent.KEYCODE_MENU && seen != SoftkeyKind.MENU ->
            SoftkeyDetection(SoftkeyKind.MENU, swapped = false)
        keyCode == KeyEvent.KEYCODE_SOFT_RIGHT && (seen == SoftkeyKind.NONE || seen == SoftkeyKind.BACK) ->
            SoftkeyDetection(SoftkeyKind.SOFT_RIGHT, swapped = true)
        keyCode == KeyEvent.KEYCODE_BACK && seen == SoftkeyKind.NONE ->
            SoftkeyDetection(SoftkeyKind.BACK, swapped = false)
        else -> null
    }

    /** True for the MENU key. The caller keeps the *whole* press — its UP and
     *  any repeats, not just the DOWN that [map] turns into Options — away
     *  from the platform: AppCompat otherwise opens its own (empty) options
     *  panel on MENU UP over the menu the DOWN just opened, which looked like
     *  "MENU moves the label but doesn't open Options" on a Sonim XP3800.
     *  FlipWeather intercepts MENU the same way. */
    fun isMenuKey(keyCode: Int): Boolean = keyCode == KeyEvent.KEYCODE_MENU

    /** Codes that a long-press turns into a hold action. Unlike `#`/`*`
     *  (absent from [mapRaw], so a short press of those does nothing
     *  anywhere), CENTER/ENTER are also mapped in [mapRaw] to plain CENTER —
     *  a long press therefore dispatches both, in order: the initial
     *  (non-long) DOWN fires plain CENTER first, then this fires CENTER_HOLD
     *  once the OS's long-press threshold passes. CENTER stays instant
     *  everywhere by default; only a screen that specifically opts into
     *  treating CENTER_HOLD as its real "confirm" (and CENTER itself as a
     *  no-op in that context) sees hold-to-confirm behavior. */
    fun holdKey(keyCode: Int): LogicalKey? = when (keyCode) {
        KeyEvent.KEYCODE_POUND -> LogicalKey.HASH_HOLD
        KeyEvent.KEYCODE_STAR -> LogicalKey.STAR_HOLD
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> LogicalKey.CENTER_HOLD
        else -> null
    }
}

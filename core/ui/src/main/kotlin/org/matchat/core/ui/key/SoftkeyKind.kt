package org.matchat.core.ui.key

/**
 * Which kind of Options key this phone has shown so far, for the automatic
 * softkey layout (docs/adr/0007, "Auto-detection" addendum; FlipWeather's
 * approach). Persisted, so the layout is set once and stays until the phone
 * shows something different.
 */
enum class SoftkeyKind {
    /** No softkey pressed yet: the default layout (Options on the right). */
    NONE,

    /** A right softkey (`KEYCODE_SOFT_RIGHT`) was seen: Options on the right. */
    SOFT_RIGHT,

    /** A MENU key (`KEYCODE_MENU`, a dedicated Options key) was seen: Options
     *  on the left. Final — see [KeyMap.detectSoftkeys]. */
    MENU,

    /** A Back key (`KEYCODE_BACK`) was pressed before any softkey: Options on
     *  the left, because a phone whose right softkey sends Back (the ZTE
     *  Cymbal 2, issue #44) can never reach Options on the right. Provisional —
     *  a right softkey or a MENU key overrides it. */
    BACK,
}

/** What [KeyMap.detectSoftkeys] learned from one key: the kind to store, and
 *  the softkey layout to switch to (`swapped` true = Options on the right). */
data class SoftkeyDetection(val kind: SoftkeyKind, val swapped: Boolean)

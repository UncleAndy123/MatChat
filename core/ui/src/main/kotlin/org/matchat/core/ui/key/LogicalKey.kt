package org.matchat.core.ui.key

/**
 * The only keys the rest of the app ever sees. Raw KeyEvent codes are translated
 * to these in exactly one place ([KeyMap]); no feature module handles a keycode
 * (AGENTS.md §4).
 */
enum class LogicalKey {
    UP,
    DOWN,

    /** Declared only where a screen opts into horizontal focus. No v1 screen does. */
    LEFT,
    RIGHT,
    CENTER, // activate focused item — identical to the centre softkey label
    SOFT_LEFT, // Options
    SOFT_RIGHT, // Back (Exit at top level)
    DIGIT_0,
    DIGIT_1,
    DIGIT_2,
    DIGIT_3,
    DIGIT_4,
    DIGIT_5,
    DIGIT_6,
    DIGIT_7,
    DIGIT_8,
    DIGIT_9,
    HASH_HOLD, // next unread room
    STAR_HOLD, // toggle large-text mode

    /** A held CENTER/ENTER (~500ms). Unlike HASH_HOLD/STAR_HOLD, plain CENTER
     *  still fires immediately too — this is opt-in, used only where a screen
     *  wants CENTER itself to require a hold (Timeline: sending a compose
     *  message, so a quick accidental CENTER press doesn't send). Elsewhere
     *  it's simply unused; a screen that doesn't check for it sees no change
     *  in behavior. */
    CENTER_HOLD,

    // Hardware call keys (docs/VOICE.md §6, ADR 0006). The only keys that gain a
    // screen-specific meaning: on an incoming call CALL = Answer, END = Decline;
    // in a call END = Hang up. Only the call screens act on these.
    CALL,
    END,
}

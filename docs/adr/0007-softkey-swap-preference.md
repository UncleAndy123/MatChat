# ADR 0007 — A user preference to swap the Left/Right softkeys

**Status:** Accepted · **Date:** 2026-09

## Context

`AGENTS.md` §4 and `UX-SPEC.md` §2 both state a global, unconditional rule:
LEFT softkey is always Options, RIGHT is always Back — "no screen reassigns a
key to a different meaning." That rule exists to keep the app predictable
across every screen.

Some feature-phone hardware exists (or is configured) with its physical
Left/Right softkeys mounted or wired in reverse of the AOSP default, so the
key that is physically on the left dispatches what the platform reports as
`KEYCODE_SOFT_RIGHT` (or vice versa). On that hardware, the app's own
consistent LEFT=Options mapping becomes *inconsistent with the phone itself*
— Options is reachable on the physical right, Back on the physical left,
with no visual cue why.

## Decision

Add one user preference, `Settings > Advanced > "Swap Left/Right keys"`
(`UserPreferences.softkeysSwapped`, `core/ui`). When on:

- `KeyMap.map(event, swapped)` flips `LogicalKey.SOFT_LEFT`/`SOFT_RIGHT` after
  its normal per-device keycode table, so a physical-left press yields
  `SOFT_RIGHT` and vice versa.
- Every screen's `SoftkeyFragment` still receives `LogicalKey.SOFT_LEFT` as
  "Options" and `SOFT_RIGHT` as "Back" — the swap happens once, centrally, in
  `KeyMap`; no feature code changes meaning.
- The on-screen labels mirror position (`mirroredLabels`,
  `SoftkeyFragment.kt`) so the visible text still matches which physical
  button does what; `SoftkeyBarView`'s tap fallback dispatches to match.
- **`KEYCODE_BACK` is exempt, always.** Some hardware has a dedicated Back
  key distinct from the two labeled positional softkeys — a universal "go
  back" affordance, not one of the two things this preference is about.
  `KeyMap.map` special-cases it before the swap step, unconditionally
  returning `SOFT_RIGHT` regardless of `swapped`. (First shipped without
  this exemption — `KEYCODE_BACK` and `KEYCODE_SOFT_RIGHT` originally shared
  one `LogicalKey` in the per-device table, a fine simplification before
  this preference existed, since they meant the same thing; swapping then
  incorrectly carried the dedicated Back key along with the positional
  right softkey. Fixed same day, on user report.)

This is a **narrow, explicit exception** to the "no screen reassigns a key"
rule, not a repeal of it: the rule is about screens giving LEFT/RIGHT
*different meanings from each other* (e.g. a screen making LEFT mean Back).
This preference doesn't do that — Options is still always reachable via one
softkey and Back via the other; only which *physical* key produces which
*logical* key changes, uniformly, for every screen, at once.

## Rationale

- Centralizing the flip in `KeyMap` (the one file that reads a raw keycode,
  per `AGENTS.md` §4) keeps every downstream consumer — 20+ `SoftkeyFragment`
  subclasses — unaware the preference exists. No screen re-implements it.
- A `StateFlow<Boolean>` read directly (`.value`) at dispatch time, not
  collected/cached: the swap takes effect on the very next key press, no
  Activity recreate needed (unlike the theme preferences, which do recreate
  — this one has no visual chrome to rebuild, just future key events).

## Update (UI improvement plan, Phase 4) — the swapped layout is now the default

Shipped default flipped from off to **on**: `softkeysSwapped` now defaults to
`true`, so a fresh install has Options on the physical right and Back on the
left. This is a distinct decision from the one above — the original
rationale (unconfigured/unreversed hardware) doesn't apply to most devices,
this is simply the preferred default layout going forward, independent of
any particular hardware's wiring. The toggle itself, `KeyMap`'s swap
mechanism, the `KEYCODE_BACK` exemption, and the mirrored on-screen labels
are all unchanged — anyone who prefers the original LEFT=Options/RIGHT=Back
layout flips the same "Swap Left/Right keys" row back off in Settings >
Advanced.

## Update — the swapped default is reverted; Options is on the left again

On-device testing after the Addendum's `AccessibilityService` workaround
shipped showed the physical right softkey still not reliably opening
Options, even with that service enabled — and the workaround itself
requires the user to grant it in system Accessibility settings, which per
user report doesn't reliably take effect on every phone (e.g. Android 13+'s
"restricted settings" protection can silently block a sideloaded app's
toggle). Reachable Options mattered more than the cosmetic preference for a
default layout, so `softkeysSwapped` is reverted to defaulting **off**:
Options is back on the left, Back on the right, out of the box. This
sidesteps the underlying conflict entirely for anyone on the default — the
contested key (`KEYCODE_SOFT_RIGHT`) no longer needs to mean Options at
all, so whatever is or isn't intercepting it stops mattering for a fresh
install.

The toggle, `KeyMap`'s swap mechanism, the `KEYCODE_BACK` exemption, the
mirrored on-screen labels, and the `AccessibilityService` workaround
(Addendum, below) are all unchanged and still available — this only flips
which layout ships unconfigured. Anyone who explicitly turned the swap on
already has that choice persisted and is unaffected either way.

## Addendum — an AccessibilityService workaround for one class of hardware

Diagnosed together with the user via adb logcat on real hardware: on some
devices, the system's own predictive-text ("T9word") keyboard consumes
`KEYCODE_SOFT_RIGHT` before `MainActivity.dispatchKeyEvent` ever sees it, but
ONLY while an EditText/IME is active (composing) — outside of composing,
normal dispatch already works fine. This is a real conflict between two
apps' hardware-key handling, not something fixable by changing what
`KeyMap`/`dispatchKeyEvent` do with an event they never receive in the first
place.

Fix: `MatChatKeyAccessibilityService` (`app/accessibility`), an
`AccessibilityService` requesting `FLAG_REQUEST_FILTER_KEY_EVENTS` — the
documented purpose of that flag is letting a service see hardware keys
earlier in the platform's dispatch pipeline than IME processing does, giving
it a chance to claim the key before the IME can swallow it.

Scope is deliberately as narrow as possible, per explicit user direction:
the service claims **only** the physical right softkey
(`KEYCODE_SOFT_RIGHT`) — not the left softkey, not `MENU`, not the dedicated
`BACK` key. Every other key, including all of a T9 IME's own digit/D-pad/
CENTER input, returns `false` immediately and is completely untouched,
whether or not this service is enabled. A claimed key is handed to
`MainActivity.handleExternalSoftkey` → `handleAccessibilityKeyEvent`, which
runs it through the exact same `KeyMap` + `LogicalKeyReceiver` path
`dispatchKeyEvent` already uses — no parallel/divergent key-handling logic.

Entirely inert unless the user explicitly enables it in system Accessibility
settings (Settings > Advanced > "Softkey helper", a plain link row to the
system screen — the app can't read or set this itself). Landed once before,
reverted for having shipped with no unit-testable seam at all; this version
extracts the one genuinely pure piece (`isInterceptedSoftkey`, the keycode
filter) into a tested top-level function, while the rest — a real
Accessibility-permission grant and a real (or simulated) T9 IME actually
swallowing the key — can only be verified with manual on-device QA, which is
called out explicitly rather than claimed as covered by the test suite.

### Update — TurboText ruled out; a real DOWN/UP consumption bug found and fixed

A sibling app on the same hardware family, TurboText (com.turbotext.app),
was suspected as a competing cause after its own accessibility-based key
service showed up reacting to `KEYCODE_SOFT_RIGHT` in an on-device logcat
capture. Reading its own source (`KeyButtonAccessibilityService`, in its
repo) settled it: that service only ever consumes the key while *Kyocera's
own home screen* is in the foreground, to fix a broken OEM shortcut —
every other foreground app, MatChat included, falls through untouched.
It still logs on every SOFT_RIGHT press system-wide for its own
diagnostics, which is why its lines appeared in the capture; it never
actually intercepts here. Ruled out by design, not just by the earlier,
inconclusive on/off retest.

Comparing the two implementations did turn up a real, previously-unnoticed
bug in `MatChatKeyAccessibilityService`: it consumed a claimed key's DOWN
but unconditionally returned `false` for its matching UP, leaving that UP
orphaned (no DOWN was ever delivered anywhere for it) — TurboText's own
doc comment describes the platform mishandling exactly this on this
hardware family ("Cancelling event due to no window focus"). Fixed the
same way TurboText does: track whether the DOWN was consumed and consume
the matching UP too.

A follow-up on-device capture confirmed the service does connect and is
granted `FLAG_REQUEST_FILTER_KEY_EVENTS` (`onServiceConnected: flags=32`)
— ruling out the Android 13+ "restricted settings" theory too — but also
showed the right softkey already working correctly via *normal* dispatch
on the Settings and room-list screens (`SOFT_RIGHT`/Back, `handled=true`),
which was never in question; the actual T9-IME-while-composing conflict
this ADR is about hasn't been captured yet. Per the user's direction, two
more manifest/config differences from TurboText's confirmed-working
service were matched for full mechanism parity: `android:exported="true"`
on the `<service>` entry, and `android:accessibilityFlags=
"flagRequestFilterKeyEvents"` declared statically in the XML config
(`key_accessibility_service_config.xml`'s own comment has the detail on
what was deliberately not copied and why). Still unconfirmed whether any
of this is the actual cause of Options not responding while composing —
needs an on-device capture taken specifically on the thread screen with
the IME up, using the existing `MatChatSoftkey` logging, to say for
certain.

## Consequences

- `AGENTS.md` §4 carries a named-exception note pointing here.
- Any *future* per-screen key reassignment proposal is still a "stop and
  ask" per `AGENTS.md` §1 — this ADR authorizes exactly this one global,
  uniform swap, not a precedent for screen-specific remapping.
- If a specific device SKU is later found to need this permanently (not as a
  user toggle), that's a `KeyMap` per-device table entry instead, per its own
  existing convention — not a reason to remove this preference, which serves
  a different case (unconfigured/unknown hardware, or a device that's
  reversed but not per-device-detectable).
- The AccessibilityService above is a separate, narrower mitigation for a
  different problem (an IME swallowing the key before dispatch, not a
  reversed physical layout) — the two are independent and can be enabled in
  any combination.

## Addendum: Auto-detection (Options on the right by default)

**Status:** Accepted · **Date:** 2026-10

By explicit user direction, the layout is now set **automatically**, the way
FlipWeather does it, and **Options is on the physical right by default** (as
in FlipWeather and the Flip-DumbPhoneGuide's house rule):

- `UserPreferences.softkeysSwapped` defaults to `true` (Options right, Back
  left) for a fresh install.
- `KeyMap.detectSoftkeys(keyCode, seen)` runs on every key-DOWN in
  `MainActivity` (normal dispatch and the accessibility path), before
  `KeyMap.map`:
  - a **MENU** key → Options on the **left** (`swapped = false`),
    `SoftkeyKind.MENU`;
  - a **right softkey** (`KEYCODE_SOFT_RIGHT`) on a phone that hasn't shown
    one yet → Options on the **right**, `SoftkeyKind.SOFT_RIGHT`;
  - the same kind again → nothing, so a manual choice in Settings > Advanced
    stands until the phone shows a different kind of key;
  - once MENU has been seen it is final: a right softkey on the same phone
    stays Back (the label it shows there), so a phone with both keys can't
    flip the layout back and forth. Unlike FlipWeather, there is no prompt;
    the user asked for the move to be automatic.
- `KEYCODE_MENU` now always maps to Options (`SOFT_LEFT`) and is never
  swapped, like `KEYCODE_BACK` is always Back: it's a dedicated Options key
  wherever it appears (Sonim's Options key; the left softkey on AOSP flips
  that send it).
- The key that triggers a change is mapped with the layout it was pressed
  under, so it does what its label said. MENU and BACK are never swapped, so
  the first MENU press still both moves the label and opens Options.
- **Back key before any softkey (issue #44).** On the ZTE Cymbal 2 the right
  softkey reaches the app as `KEYCODE_BACK`, so with Options on the right the
  "Options" key exited the app and Options was unreachable. A `KEYCODE_BACK`
  press while nothing has been seen yet (`SoftkeyKind.NONE`) now moves
  Options to the **left** (`SoftkeyKind.BACK`); the press itself is still
  Back. This is provisional: a later `KEYCODE_SOFT_RIGHT` (a phone with a
  real right softkey *and* a dedicated Back key, e.g. Kyocera) moves Options
  back to the right, and that press acts as the Back its label showed; a MENU
  key moves it left for good. The cost on such a phone is one label move;
  every press still does what its label said when it was pressed.

The known risk recorded above still applies to phones that land on Options
right: the T9 keyboard can swallow `KEYCODE_SOFT_RIGHT` while composing.
The accessibility-service mitigation (Addendum above) remains the answer for
that, and Settings > Advanced can still move Options to the left by hand.


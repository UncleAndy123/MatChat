# ADR 0009 — Compile-only stub for Kyocera's cover-screen callback

**Status:** Accepted · **Date:** 2026-10

## Context

MatChat posts message cards to the Kyocera cover (outer) screen through the
OEM class `jp.kyocera.sublcd.SubLcdManager`, reached by reflection
(docs/COVER-DISPLAY.md). The user wants a card that is still unread to come
back whenever the cover screen wakes, until the message is read.

Android only tells an app when the *main* screen turns on or off
(`SCREEN_ON`/`SCREEN_OFF`), which covers opening and closing the flip. Waking
the cover with an outside button while the flip is shut produces no public
signal, and on-device testing found no key reaching `MatChatKeyAccessibilityService`
in that state.

A read-only probe of the OEM API found `SubLcdManager.registerCallback(ISubLcdCallback)`,
where `ISubLcdCallback` is an AIDL interface with `onScreenStateChanged(int)`
(constants `STATE_SCREEN_ON = 1`, `STATE_SCREEN_OFF = 0`), `onKeyDown/Up/
LongPress/Multiple(...)`, and `onNotificationCancel(String, int)` — Kyocera's
own cover on/off and cover-key events.

Listening means extending `ISubLcdCallback.Stub`. Reflection can call the
manager, but it cannot create a subclass of a class that only exists on the
phone, so the compiler needs a declaration of the interface.

## Decision

Add a module, `:stubs:kyocera-sublcd`: one Java file declaring
`jp.kyocera.sublcd.ISubLcdCallback` and its nested `Stub` with placeholder
bodies, matching the six methods the probe listed. It is a plain
`java-library` compiled against the SDK's `android.jar` (no Android plugin, no
AAR), and `:app` depends on it with **`compileOnly`**, so it is never
packaged. On a Kyocera phone the framework's own class is loaded (the system
class loader is asked first); on any other phone MatChat never reaches code
that uses it, because `SubLcdManager` is absent and every use is guarded.

R8: `-dontwarn jp.kyocera.sublcd.**`, and keep the overrides of any subclass
of `ISubLcdCallback$Stub` so the framework's `onTransact` can reach them.

Rollout is in two steps, because the only other private cover call we tried
(`notifyIconToAnnunciatorTray`) crashed SystemUI:

1. **Debug probe** (`CoverProbeReceiver --ez callback true`): registers a
   callback for two minutes that only logs what it receives, answers every
   key callback `false` (not handled), then unregisters.
2. **Production**, only if step 1 shows sensible events and nothing breaks:
   `CoverScreenNotifier` registers while an unread card is pending, re-shows
   it on `onScreenStateChanged(STATE_SCREEN_ON)`, still never handles keys,
   and unregisters when nothing is pending. The accessibility-service "cover
   re-show" job (AGENTS.md §4) is then removed.

**Result:** step 1 on the DuraXV registered cleanly and reported
`onScreenStateChanged` 1/0 as the cover turned on/off, with nothing breaking;
no cover keys were forwarded. Step 2 is implemented (`CoverScreenCallback`),
and the accessibility job is removed.

## Consequences

- A private OEM interface is now part of the build. If Kyocera changes it,
  the stub no longer matches; registration then fails inside a guard and the
  feature degrades to flip open/close only. The probe is how to re-check.
- The stub module needs the Android SDK path (local.properties `sdk.dir` or
  `ANDROID_HOME`), like every other module in practice.
- No new third-party dependency and nothing added to the APK.

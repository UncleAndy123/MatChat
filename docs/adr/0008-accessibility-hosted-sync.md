# ADR 0008 — Sync hosted by the accessibility service (no running notification)

**Status:** Accepted · **Date:** 2026-09

## Context

ADR 0004 put the sync loop in a foreground service because there is no FCM on
these phones. Android only lets a process keep a network connection alive in
the background through a foreground service if it shows an ongoing
notification, so every user sees "MatChat is running", all the time.

TurboText, a sibling app on the same Kyocera hardware, shows no such
notification. It doesn't need one: as the default SMS app, the OS wakes it for
every incoming text (`SMS_DELIVER`). Matrix has no OS-level wake-up without a
push service. TurboText also runs an optional accessibility service, and the
system keeps an *enabled* accessibility service bound: its process stays alive
and is restarted after a kill, with no foreground service and no notification.
MatChat already ships an optional accessibility service
(`MatChatKeyAccessibilityService`, the softkey workaround).

## Decision

When the user has **both** turned that service on (now called the "Background
helper") **and** allowed "Run in background" (the battery-optimization
exemption), the service hosts sync instead of the foreground service, and the
"MatChat is running" notification goes away. Otherwise the foreground service
stays the host, unchanged.

- `SyncOwner` (app, `@Singleton`) owns the sync logic: session restore, message
  notifications, call ringing. A host (`SyncHost.FOREGROUND_SERVICE` or
  `SyncHost.ACCESSIBILITY`) only lends it a scope; exactly one host owns sync
  at a time, and baselines survive a hand-off so nothing re-alerts.
- `SyncHosts.ensureRunning()` picks the host with the pure
  `SyncHostPolicy.choose(signedIn, helperConnected, batteryExempt)`. It runs on
  every app resume, after sign-in, on boot, from the watchdog, and when the
  helper connects or disconnects.
- The exemption is required because without it Doze and app standby cut
  network access to a process that isn't a foreground service.
- The sync loop itself is the same in both modes: one continuous connection,
  messages within about a second. The Android 15 `dataSync` cap does not apply
  to the accessibility host.
- After the first sign-in the app offers the exemption dialog and then S27,
  once. Settings > Advanced (S25) shows both states and reopens both screens.

## Consequences

- **Not off by default.** The app cannot turn on an accessibility service for
  itself; the user does, in system settings. (A phone provisioned over ADB could
  have it enabled by the setup script; not done here.)
- The service still reads only the right softkey and never the screen
  (`canRetrieveWindowContent="false"`); its system description now names both
  jobs so enabling it is informed consent.
- Accessibility APIs used for something other than accessibility would be a
  Play Store policy problem. MatChat is sideloaded (PLAN.md §3), so this does
  not apply today; revisit if it is ever distributed through Play.
- Turning the helper off, or revoking the exemption, hands sync straight back to
  the foreground service (with its notification). A failed hand-off is covered
  by the `SyncWorker` watchdog (ADR 0004).
- OEM behaviour varies. **Measure on the reference DuraXV**: messages arrive
  with the phone idle and closed, and idle drain stays under 2 %/hour.
- Do not use the accessibility service for anything else without the same kind
  of explicit direction (AGENTS.md §4).

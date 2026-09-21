# ADR 0004 — Sync in a foreground service; no FCM

**Status:** Accepted · **Date:** 2026-09

## Context

Kyocera DuraXV-class AOSP flip phones generally ship without Google Play
Services. There is no FCM, no Play Store, and no Play Integrity. Push
notification — the mechanism every modern Android messenger depends on — is
simply unavailable.

## Decision

A foreground service owns the SDK's sync loop for the life of the session, with
a persistent low-priority notification. Message notifications are generated
locally from the sync stream.

## Consequences

- The user always sees a "MatChat is running" notification. Acceptable on this
  device class; document it in Help so it does not read as a bug.
- At `targetSdk 35` the service must declare
  `android:foregroundServiceType="dataSync"` and hold
  `FOREGROUND_SERVICE_DATA_SYNC` (API 34+), and the app must request
  `POST_NOTIFICATIONS` (API 33+). **Android 15 caps `dataSync` at ~6 h per
  24 h**; on any device running API 35 the service hits that ceiling and must
  hand off to a periodic `WorkManager` sync, with the user-visible cost of
  delayed messages. Most target flips run older AOSP builds where the cap does
  not apply — verify per SKU rather than assuming.
  **Built (M1):** `SyncForegroundService.onTimeout()` pauses the loop (keeping
  the client alive) and enqueues `SyncWorker`, a unique periodic (15 min,
  network-constrained) job exempt from the FGS cap. Each run restores the session
  if the process was reclaimed, runs a bounded catch-up sync, and posts
  notifications via the shared `MessageNotifications`. Foregrounding the app
  resets the budget and reclaims the service, which cancels the worker so the two
  never sync in parallel.
- Battery is the primary risk of the whole project. We request a battery
  optimization exemption during onboarding and **measure idle drain on hardware
  at M1**, not at M5. Budget: < 2 %/hour idle-connected.
- Some carrier builds may kill or refuse to exempt the service. Fallback,
  if measurement demands it: sync on unlock plus a periodic alarm, with the
  user-visible cost of delayed messages.
- If a target device *does* have Play Services, FCM may be added later as an
  optional flavour — but the foreground service stays the default path.

# ADR 0005 — Domain allowlist from the MDM restrictions bundle, fail-open

**Status:** Accepted · **Date:** 2026-09

## Context

Users need to be invited to rooms and to start direct chats with people on other
federated homeservers by address. That reopens a door the product had deliberately
nailed shut — but only partly: *knowing* an address stays allowed, *finding* one
never becomes allowed (ADR 0003 still stands in full).

The question is who decides which servers a given phone may reach, and where that
decision is enforced. Options:

1. **Server-side only** — `federation_domain_whitelist` in Synapse. Real
   enforcement, but one list for the whole homeserver: every phone gets the same
   answer, and changing it is an ops task.
2. **Baked into the APK** — a build-time list. Per-build, not per-device, and
   changing it means re-signing and re-sideloading to every phone.
3. **Managed configuration (MDM application restrictions)** — per-device, changed
   from the EMM console, no rebuild.

## Decision

Option 3. The allowlist lives in the Android application restrictions bundle
(`RestrictionsManager` + `app_restrictions.xml`), read by `:core:policy` and
re-read on `ACTION_APPLICATION_RESTRICTIONS_CHANGED`.

**When no managed configuration is present — no device owner, no profile owner,
an empty bundle — all domains are allowed.** Fail-open, deliberately.

## Rationale for failing open

The alternative (fail-closed) means every unmanaged phone — a developer's test
device, a phone whose enrollment lapsed, a user who has not been enrolled yet —
silently cannot message anyone, with no way for the user to tell why. On a device
with no second screen and no support channel, a silent, unexplainable block is
worse than an open phone that still has no discovery surface. The floor is
already high: no directory, no search, no browsing. Fail-open lands on that
floor, not on nothing.

## Consequences

- **This is a guardrail, not a security boundary.** It stops an ordinary user
  from messaging an outside domain. It does not stop a modified APK, a different
  client pointed at the same account, or an unenrolled device. Anyone reading
  this ADR should assume it will be bypassed if someone wants to bypass it.
- Where the boundary must actually hold, the same list goes into Synapse's
  `federation_domain_whitelist` as well. `docs/MDM.md` documents both halves
  together so the choice is explicit at deployment time, not discovered later.
- These devices have no Play Services, so the usual Android Enterprise
  enrollment path is unavailable. Restrictions require a device-owner or
  profile-owner DPC — an EMM agent that supports non-GMS AOSP, or
  `adb shell dpm set-device-owner` during provisioning. **If no DPC is ever
  installed, this ADR's mechanism does nothing at all**, quietly, by design.
  Settings therefore always shows "Managed by your organization" or "Not
  managed".
- Policy is live, so `Policy` is a `Flow`. Any code that reads it once at startup
  is wrong.
- `contacts` is pushed as a JSON *string*, not `bundle_array`: `bundle_array`
  requires API 26 and minSdk is 24.

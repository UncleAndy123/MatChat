# ADR 0001 — Use the Matrix Rust SDK Android bindings for all protocol work

**Status:** Accepted · **Date:** 2026-09

## Context

We need Matrix client-server protocol, sliding sync, timeline management and
E2EE (Olm/Megolm, cross-signing, key backup, verification) on a low-power AOSP
feature phone. Writing any of that ourselves would be the whole project, and
getting encryption subtly wrong is worse than not shipping.

Options considered:

1. `org.matrix.rustcomponents:sdk-android` — the FFI bindings over
   `matrix-rust-sdk`, the same layer Element X Android ships on.
2. `matrix-android-sdk2` (the legacy Kotlin SDK behind Element Android) —
   in maintenance, heavier, and the ecosystem has moved.
3. Hand-rolled HTTP + a crypto library — no.

## Decision

Option 1. `:core:matrix` wraps it and is the only module permitted to import it.

## Consequences

- We get sync, pagination, dedup, read receipts, decryption retries, key backup
  and verification without writing them.
- The Rust `.so` dominates APK size → we ship per-ABI splits (`armeabi-v7a`,
  `arm64-v8a`) and hold a < 25 MB per-split budget.
- The binding is calendar-versioned and moves fast; upgrades are a scheduled
  task, and all breakage is confined to `internal/Mappers.kt` by design.
- The homeserver must support sliding sync (native in current Synapse). Verify
  the deployed server version before M2.

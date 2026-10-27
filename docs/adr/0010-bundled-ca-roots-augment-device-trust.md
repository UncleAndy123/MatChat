# ADR 0010 — Bundle current CA roots to augment the device trust store

**Status:** Accepted · **Date:** 2026-10

## Context

The Matrix Rust SDK verifies homeserver TLS against the **operating system's
trust store** via `rustls-platform-verifier` (the OS store, plus any CA the
user installs on the device). That is the behaviour we want — it honours the
device, including a private CA a user adds for a self-hosted homeserver.

But MatChat's target hardware is old: Kyocera and other AOSP feature phones on
Android 7–11. Those system trust stores predate today's public roots. A device
whose store lacks, say, **ISRG Root X1** (Let's Encrypt) or the **Google Trust
Services** roots cannot anchor the chains that `matrix.org` and most
Let's-Encrypt homeservers now serve, even though the certificates are
perfectly valid. The verifier correctly returns "trust anchor for certification
path not found", and release sign-in fails. Chrome works on the same phone
only because it ships its own roots.

This is issue #41, reproduced on real devices (Android 10 ZTE Cymbal 2, a real
Android 7 handset) and an Android 9 emulator. Debug builds hid it because they
disable TLS verification entirely (`MatrixDevConfig.allowInsecureTls`,
debuggable only), so the problem is release-only and had gone unnoticed.

The verifier is **not** wrong, and we must not weaken it — disabling
verification, or replacing the OS store with a fixed bundle, would both break
the "a CA the user trusts on the device is trusted by the app" property (and
the former is a security hole besides).

reqwest/rustls supports exactly the right primitive: **augmenting** the
platform verifier with extra roots (`Verifier::new_with_extra_roots`), surfaced
by the SDK's `ClientBuilder.addRootCertificates(List<ByteArray>)` **when the
built-in roots are not disabled** (this maps to reqwest's `tls_certs_merge`,
which merges rather than replaces).

## Decision

Ship a current public CA set with the app and merge it into the verifier:

- `core/matrix/src/main/res/raw/ca_bundle.pem` — the Mozilla CA bundle
  (sourced from the `certifi` project's `cacert.pem`). It is parsed once into
  one blob per certificate and passed to
  `ClientBuilder.addRootCertificates(...)` in `RustMatrixClientHolder.buildClient`.
- We **do not** call `disableBuiltInRootCertificates()`. The OS trust store —
  and any CA the user installs on the device — stays authoritative; the bundle
  only **adds** the modern roots an aging store lacks. A homeserver behind a
  private/corporate CA that the user has installed still works; a managed-CA
  story would layer on top via policy, never by replacing this.
- No-op fallback: if the resource can't be read, the build proceeds on the OS
  store alone (the prior behaviour).

## Consequences

- Release sign-in works on old Android against homeservers whose roots the
  device predates.
- The bundle goes stale over time (roots rotate). It is a plain data file:
  refresh it on SDK bumps or at least yearly from the same upstream. A stale
  bundle fails safe — it can only fail to add a brand-new root (sign-in falls
  back to the OS store for that one), never trust something it shouldn't.
- ~230 KB added to the APK. The per-split size gate was already removed, and
  this is well within budget.
- Verification is never weakened: this only ever *adds* trust anchors, and only
  on top of the device's own store.

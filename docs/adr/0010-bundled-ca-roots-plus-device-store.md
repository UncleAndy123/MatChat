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

The clean primitive would be to **augment** the platform verifier with extra
roots (reqwest's `tls_certs_merge` / rustls `Verifier::new_with_extra_roots`,
i.e. `addRootCertificates(...)` without disabling the built-in roots). But this
SDK build does **not** support it: that call fails `ClientBuilder.build()` with
a reqwest "builder error" (the platform verifier can't take a merged root
store). So the only options the SDK actually offers are the platform verifier
alone, or a root set of our own via `disableBuiltInRootCertificates()`.

## Decision

Rebuild the trust set ourselves and hand it to webpki — the bundled modern
public roots **plus** the device's own store, so nothing the device already
trusts is lost:

- `core/matrix/src/main/res/raw/ca_bundle.pem` — the Mozilla CA bundle
  (sourced from the `certifi` project's `cacert.pem`), parsed once into one
  blob per certificate.
- At client build (`RustMatrixClientHolder.buildClient`) we also enumerate the
  device's **`AndroidCAStore`** (system roots **and** any CA the user installed
  by hand), as DER.
- We call `disableBuiltInRootCertificates()` and
  `addRootCertificates(bundle + deviceStore)`. webpki then trusts the union:
  the modern public roots fix the old-device gap, and the enumerated device
  store means a self-hosted homeserver's private CA — or any CA the user trusts
  on the phone — keeps working. This reproduces the "augment" outcome that the
  SDK won't do natively.
- No-op fallback: if neither source yields any certificate we leave the
  built-in roots enabled, so the build proceeds on the platform verifier alone
  (the prior behaviour) rather than with an empty trust set.

The one thing lost versus the live platform verifier is dynamism: the device
store is snapshotted at client-build time, so a CA the user adds mid-session is
picked up on the next sign-in / client rebuild, not instantly. For this app
that is immaterial.

## Consequences

- Release sign-in works on old Android against homeservers whose roots the
  device predates.
- The bundle goes stale over time (roots rotate). It is a plain data file:
  refresh it on SDK bumps or at least yearly from the same upstream. A stale
  bundle fails safe — it can only fail to anchor a chain under a brand-new root
  (that homeserver fails closed), never trust something it shouldn't.
- ~230 KB added to the APK. The per-split size gate was already removed, and
  this is well within budget.
- Verification is never weakened. The trust set is (current public roots ∪ the
  device's own store), so it is a superset of what the device trusted before —
  no public CA is dropped, and the user's own installed CAs are kept. Because we
  disable the SDK's built-in roots, the floor is this trust set, never an empty
  one, which the no-op fallback guarantees.
- A clear `Sdk: builder error` at client build means the root list failed to
  parse (every entry must be a valid DER/PEM certificate); the merge path was
  already ruled out this way.

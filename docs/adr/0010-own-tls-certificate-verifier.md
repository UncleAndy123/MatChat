# ADR 0010 — Ship our own TLS certificate verifier (platform-default trust)

**Status:** Accepted · **Date:** 2026-10 · **Issue:** #41

## Context

Release builds could not sign in to matrix.org on Android 7–11 (a real Android 7.1
handset, the issue reporter's Android 10 ZTE Cymbal 2, an Android 9 emulator):
`ServerUnreachable: error sending request for url (https://matrix.org/.well-known/matrix/client)`.
Debug builds worked only because they disable TLS verification
(`MatrixDevConfig.allowInsecureTls`). Upstream reports the same symptom against
sdk-android 26.07–26.09 (matrix-rust-sdk #7013).

How the SDK verifies TLS on Android: reqwest 0.13 + rustls use
`rustls-platform-verifier` 0.6.2, whose native code calls one Java method,
`org.rustls.platformverifier.CertificateVerifier.verifyCertificateChain(...)`, and reads
`VerificationResult.code` / `.message`. sdk-android ships **no** such class; the app must.

What we measured and verified (no assumptions):

- **The device stores can anchor matrix.org.** Read straight from each release's system CA
  store (LineageOS mirror of AOSP `system/ca-certificates`): GTS Root R4 is absent on
  Android 7.1/8.1/9 and present from 10; GlobalSign Root CA — which cross-signs the GTS
  Root R4 that matrix.org sends (`matrix.org ← WE1 ← GTS Root R4 ← GlobalSign Root CA`) —
  is present on every version. ISRG Root X1 is present from 7.1.
- **Android's own default trust manager accepts the chain** on the failing device
  (`TrustManagerFactory.init(null)` → "TRUSTS").
- **The upstream Kotlin verifier rejects it** (`code=3 UnknownCert`, "Trust anchor for
  certification path not found") on the same device, Android 7.1 and 9. Upstream builds its
  trust manager from `TrustManagerFactory.init(KeyStore.getInstance("AndroidCAStore"))`
  instead of the platform default, then runs an OCSP-only revocation check.
- **Adding roots to the SDK is impossible on Android.** reqwest 0.13.1 compiles
  `Verifier::new_with_extra_roots` only for desktop targets; on Android
  `addRootCertificates()` fails the build ("rustls-platform-verifier could not load extra
  certs"). `disableBuiltInRootCertificates()` + `addRootCertificates()` also fails: the
  FFI tries `Certificate::from_der` first, which never errors under rustls, so PEM input is
  stored as DER and rejected later ("builder error").
- **Element X**, on this SDK line, ships its own copy of `CertificateVerifier` with the
  revocation logic removed (#7013), plus a network security config trusting system + user
  CAs. Upstream's later fix (Android component 0.2.0, for rustls-platform-verifier 0.7.x,
  which this SDK cannot use — its FFI pins 0.6.2) only adds CRL hosts and an `isKnownRoot`
  loop fix; it does not change the trust-manager step.

## Decision

- `app/src/main/kotlin/org/rustls/platformverifier/CertificateVerifier.kt` is our own source,
  derived from upstream 0.1.1 (MIT), with the identical JNI contract (verified: the compiled
  descriptor equals the string in `libmatrix_sdk_ffi.so`). It replaces the vendored jar.
  - Trust is decided by Android's **platform-default** trust manager
    (`TrustManagerFactory.init(null)`), the one HttpsURLConnection/OkHttp use.
  - **No revocation check** — Android does none for apps, and upstream's OCSP-only check
    fails certificates without an OCSP URL.
  - Encoding, validity-period and EKU checks are kept; hostname matching stays in Rust.
- `app/src/main/res/xml/network_security_config.xml` trusts **system + user** CAs (same as
  Element X), so a CA the user installs on the device — a self-hosted homeserver's private CA
  or a managed network's CA — is honoured by the SDK and by every other HTTPS client in the
  app. Cleartext stays off.
- `RustMatrixClientHolder` passes no root certificates to the SDK.

## Consequences

- TLS trust now matches the rest of Android exactly: what the device (and the user) trusts,
  MatChat trusts. Verification is never disabled outside debug builds.
- No revocation checking, as on every other Android app's default path.
- On an SDK bump, re-check the JNI signature in the `.so` (command in
  `gradle/libs.versions.toml`). When the SDK moves to rustls-platform-verifier 0.7.x, either
  keep this class (same contract) or switch to the upstream AAR plus its CRL network config.

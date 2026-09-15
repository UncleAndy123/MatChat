# matrix-shim — patched `sdk-android` build pipeline

Spike harness for the E2EE-call path chosen in `docs/VOICE.md §4.1`: produce our
own build of `org.matrix.rustcomponents:sdk-android` with a few extra FFI methods
the upstream binding does not expose (to-device send/receive, needed for Element
Call's `io.element.call.encryption_keys`), **without** turning MatChat into a
Rust project.

The app never compiles Rust. This harness produces a prebuilt `.aar` (native
`.so` per ABI + generated Kotlin), exactly like the upstream artifact; the app
just links a different coordinate. Android Studio builds MatChat unchanged.

## What this spike proves (and what it does not)

**Goal of the spike:** de-risk the *pipeline*, not the crypto. Prove that we can:

1. check out `matrix-rust-components-kotlin` + `matrix-rust-sdk` at the versions
   MatChat pins,
2. overlay an extra FFI method onto the `matrix-sdk-ffi` crate,
3. cross-compile with `cargo-ndk` + run uniffi codegen and assemble the `.aar`,
4. publish it and have **Android Studio build MatChat against it** and call the
   new method from Kotlin.

The overlay therefore adds one trivial probe method — `matchatShimVersion()` —
whose only job is to survive cross-compile + uniffi codegen and be callable. If
that round-trips, the pipeline is proven and the real to-device methods (below)
are ordinary follow-on work in the same overlay file.

**Not in scope for the spike:** the actual key exchange, Olm to-device
encryption, or LiveKit E2EE wiring. Those are the *next* increment, tracked in
`overlay/matchat_shim.rs` as commented reference signatures.

## Prerequisites (build host / CI runner — never a dev laptop)

- Linux or macOS with: Rust stable + the Android targets
  (`aarch64-linux-android`, `armv7-linux-androideabi`, `i686-linux-android`,
  `x86_64-linux-android`), `cargo-ndk`, Android NDK (r26+), JDK 17, and the
  Android SDK. `git`, `bash`, `curl`.
- Set `ANDROID_NDK_HOME` (or `ANDROID_NDK_ROOT`).

None of this is needed to build the MatChat app — only to (re)build the AAR,
which happens in CI (`.github/workflows/matrix-shim.yml`) or on one throwaway
box.

## Run

```bash
# from repo root
ANDROID_NDK_HOME=/path/to/ndk \
  tools/matrix-shim/build-aar.sh
```

Key variables (all overridable as env vars; defaults track `libs.versions.toml`):

| var | default | meaning |
|---|---|---|
| `SDK_VERSION` | `26.09.3` | must equal `matrix-rustsdk` in `gradle/libs.versions.toml` |
| `COMPONENTS_TAG` | `sdk-v${SDK_VERSION}` | tag in `matrix-org/matrix-rust-components-kotlin` |
| `SDK_REF` | `f4b9512…` | `matrix-rust-sdk` commit that `COMPONENTS_TAG` was built from (that release's notes) |
| `SHIM_VERSION` | `${SDK_VERSION}-matchat-shim1` | version stamped on our AAR |
| `ONLY_TARGET` | `aarch64-linux-android` | one ABI for a fast spike build; empty = **all** ABIs; e.g. `armv7-linux-androideabi` for the flip phones |
| `WORK_DIR` | `tools/matrix-shim/work` | scratch checkouts (git-ignored) |
| `PUBLISH` | `mavenlocal` | `mavenlocal` \| `file` (drops the aar in `WORK_DIR/out`) |

Note `ONLY_TARGET`: the default builds a single arm64 ABI so the first green run
is fast. To sideload on the actual device build its ABI (msm8909-class phones are
32-bit → `armv7-linux-androideabi`) or set `ONLY_TARGET=` empty for all four.

On success it publishes `org.matrix.rustcomponents:sdk-android:${SHIM_VERSION}`
to your local Maven (`~/.m2`), or writes the `.aar` under `WORK_DIR/out`.

## Point the MatChat build at the shim (opt-in, default off)

The app build is untouched unless you opt in. In `gradle.properties` (or `-P`):

```properties
matchat.useShimSdk=true
matchat.shimSdkVersion=26.09.3-matchat-shim1
```

That flag (wired in `settings.gradle.kts` + root `build.gradle.kts`) adds
`mavenLocal()` and substitutes the `sdk-android` version. With it off — the
committed default — resolution is byte-identical to today.

### Verify the round-trip (the spike's actual pass/fail)

With the shim built and the flag on, add this temporary probe to
`RustMatrixClientHolder` (behind the same flag / a debug log) and confirm it
compiles and logs at runtime:

```kotlin
Log.i("matrix-shim", "shim = " + requireClient().matchatShimVersion())
```

`matchatShimVersion` exists **only** in the shim build — that it resolves,
compiles, and runs is the proof the whole pipeline works. Remove the probe once
green; it is not committed to normal source (it would not compile against the
upstream SDK).

## How the overlay is applied

`build-aar.sh` clones `matrix-rust-components-kotlin` (@ `COMPONENTS_TAG`) and,
separately, `matrix-rust-sdk` (@ `SDK_REF` — components-kotlin does not vendor
it; its `scripts/build.sh -p <sdk>` takes the path). It copies
`overlay/matchat_shim.rs` into the SDK's `bindings/matrix-sdk-ffi/src/` and
ensures `mod matchat_shim;` is declared in that crate's `lib.rs`, then runs
components-kotlin's `scripts/build.sh` (cargo xtask cross-compile + uniffi codegen
→ `gradlew :sdk:sdk-android:assembleRelease`). This avoids a fragile line-numbered
patch. If uniffi rejects a second `#[matrix_sdk_ffi_macros::export] impl Client`
block in a separate module (a thing this spike is meant to discover — client.rs
already uses that macro on multiple `impl Client` blocks, so it is expected to
work), the fallback is to inline the method into the existing exported
`impl Client` in `client.rs` — see the header of `overlay/matchat_shim.rs`.

## Next increment (post-spike)

Once the probe round-trips, replace it with the real methods (signatures already
sketched in `overlay/matchat_shim.rs`):

- `send_encrypted_to_device(event_type, targets, content_json)` — Olm-encrypt to
  the given user/device pairs via the client's own crypto machine.
- a to-device receive stream delivering decrypted
  `io.element.call.encryption_keys` to Kotlin.

Then the Kotlin/LiveKit key-schedule work (transcribed from matrix-js-sdk's
`matrixrtc` module) lands in `:core:matrix` + `:core:rtc`. None of that changes
this pipeline.

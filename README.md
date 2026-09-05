# MatChat

A Matrix client for D-pad feature phones — Kyocera flip phones and similar AOSP
dumbphones used as filtered / "kosher" phones.

- Fully operable with the D-pad and two softkeys. No touchscreen assumptions.
- Built for 240 × 320, 2 GB RAM, no Google Play Services.
- End-to-end encrypted, via the Matrix Rust SDK.
- **No room discovery, no search, no directory** — by design and by build rule.

## Documents

| File | What it is |
|---|---|
| [`PLAN.md`](PLAN.md) | The development plan: goals, stack, milestones, risks |
| [`AGENTS.md`](AGENTS.md) | Rules for AI agents (and new humans) contributing code |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Module contracts and data flow |
| [`docs/UX-SPEC.md`](docs/UX-SPEC.md) | Every screen, key map, focus order |
| [`docs/adr/`](docs/adr) | One file per irreversible decision |

## Status

**M0 — skeleton.** The module graph, build, and CI are in place; every screen in
the UX spec is stubbed and reachable with the D-pad + softkeys only. Matrix is not
wired yet: `:core:matrix` ships the full contract plus an in-memory stub that
returns empty flows, so the app runs and renders its empty states. M1 swaps the
stub for the SDK-backed session without touching a single caller.

What's real in M0:

- 14 modules with the `PLAN.md §5` dependency graph, enforced by Konsist tests
  (`:app` `org.matchat.client.arch.*`): SDK import confined to `:core:matrix`, no
  feature→feature deps, no discovery APIs, ViewModels free of Android/`:core:ui`.
- `:core:ui` device layer: the single `KeyMap`, `SoftkeyFragment`, the focus
  engine, the one `MenuSheet`, the theme, type scale and `focus_selector`.
- `:core:policy` reads the managed-configuration bundle live and fail-open
  (unit-tested); `:core:contacts` merges admin + local contacts.
- Every screen (S1–S23) as `State`/`Action`/`ViewModel`/`Fragment`/layout, with
  reducer unit tests for the ones that carry logic.
- Sync foreground service, manifest, `app_restrictions.xml`, ABI splits, R8.

The Matrix Rust SDK (`libs.matrix.rustsdk`) is pinned in the version catalog but
not yet a module dependency — it lands in M1 (`PLAN.md §7`).

## Build

```bash
./gradlew spotlessApply detektAll test          # format, static analysis, unit tests
./gradlew :app:testDebugUnitTest --tests "org.matchat.client.arch.*"   # architecture rules
./gradlew verifyPaparazziDebug                  # screenshot diffs (240×320)
./gradlew :app:assembleDebug
./gradlew :app:installDebug                     # reference device: Kyocera DuraXV Extreme+
```

Requires the Android SDK (`compileSdk 35`, `minSdk 24`) and JDK 17. Emulator
profile for UI work: 240×320 mdpi, API 24, touch disabled. The nightly key-only
traversal suite runs on that emulator (`.github/workflows/traversal.yml`).

On an SSL-inspecting corporate proxy, Gradle downloads fail with `PKIX path
building failed` until the proxy root CA is imported — see `PLAN.md §11`.

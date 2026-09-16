<div align="center">

# 📟 MatChat

**Matrix group messaging for D-pad feature phones.**

Real end-to-end-encrypted chat on Kyocera flips and other locked-down AOSP
"dumbphones" — driven entirely by the directional pad and two softkeys, with
**no way to browse or search for people or rooms**, by design.

[![CI](https://github.com/UncleAndy123/MatChat/actions/workflows/ci.yml/badge.svg)](https://github.com/UncleAndy123/MatChat/actions/workflows/ci.yml)
[![License: GPL v2](https://img.shields.io/badge/License-GPLv2-blue.svg)](LICENSE)
![Platform](https://img.shields.io/badge/platform-Android%20%7C%20minSdk%2024-3DDC84?logo=android&logoColor=white)
![Screen](https://img.shields.io/badge/screen-240%C3%97320%20QVGA-lightgrey)
![Status](https://img.shields.io/badge/status-pre--release-orange)
![Version](https://img.shields.io/badge/version-0.1.0--M0-informational)

</div>

---

## Why it exists

People who carry a filtered feature phone — no touchscreen, no Google Play, a
2.6″ 240×320 screen — have no usable group-messaging option. Every mainstream
messenger ships a directory, a people search, or a media feed that makes it
unacceptable on such a device.

MatChat deliberately has **none of those**. It joins a room only when you
*accept an invitation* or *start a chat with an address you already know*
(`@wayne:example.org`). The line that matters: **knowing an address is allowed;
finding one is not.**

Built on the same [Matrix](https://matrix.org) Rust SDK that Element X ships, so
we write **UI only** — zero protocol code of our own.

## What it does

| | |
|---|---|
| 🎮 **D-pad native** | Fully operable with ↑↓←→ + CENTER and two softkeys. LEFT is always *Options*, RIGHT is always *Back*. No touch handling anywhere. |
| 🔒 **Encrypted by default** | E2EE via the Matrix Rust SDK. An unencrypted room shows a visible warning. |
| 🚫 **No discovery, ever** | No room directory, no user search, no filter boxes — enforced structurally by a build-time lint rule, not a setting. |
| 🏢 **Admin-manageable** | An allow-list of homeserver domains pushes over standard Android managed configuration (MDM). Unmanaged phones stay open — never silently unable to message. |
| 👓 **Legible at 2.6″** | Strict type floor (body 16 sp, labels 14 sp, metadata 11 sp), high-contrast focus highlighting. |
| 📞 **Voice calls** *(in progress)* | Native MatrixRTC + LiveKit audio that interoperates with Element — no WebView. |

**Reference device:** Kyocera DuraXV Extreme+ · also DuraXV Extreme, DuraXE
Epic, and similar Sonim / TCL AOSP flips (2 GB RAM, `minSdk 24`).

These phones have no app store: MatChat is **sideloaded** (ADB / WebADB) and
kept in sync by a foreground service, since there is no Google push. An in-app
updater pulls signed releases over GitHub Releases.

## Where we are

Actively developed, pre-release (`0.1.0-M0`). The module graph, build, and CI
are in place, and every screen is reachable with the D-pad and softkeys only.

| Milestone | Scope | Status |
|---|---|:--:|
| **M0** — Skeleton | Modules, CI, focus engine, softkey bar, key-traversal harness | ✅ Done |
| **M1** — Session | Password sign-in, Keystore session persist/restore, sync service, sign out | ✅ Done |
| **M2** — Read | Room list + timelines, pagination, read receipts (sliding sync) | ✅ Done |
| **M3** — Write | Send, send-state, retry, notifications, deep link | 🟡 In progress |
| **M4** — Crypto | Emoji SAS verification, recovery key, unable-to-decrypt recovery | 🟡 In progress |
| **M5** — Reach | Invitations, new-message → contacts / recents / typed address, MDM policy | 🟡 In progress |
| **M6** — Polish | Empty/error/offline states, battery tuning, Help screen, a11y pass | ⬜ Planned |
| **M7** — Pilot | Signed APK, enrollment docs, 5-device field pilot | ⬜ Planned |

> Invitations, device verification, and direct-chat-by-address are partly wired
> and being completed — search for `FFI follow-up` in `:core:matrix` for the
> remaining SDK bring-up points.

## What's next

- 🎯 **Finish M3–M5** — complete verification, invitations, and direct chat; land
  the managed-configuration policy read with live reload.
- 📞 **Voice calls** — mature MatrixRTC/LiveKit interop with Element into a
  shippable audio call on the reference hardware ([`docs/VOICE.md`](docs/VOICE.md)).
- 🔋 **Battery tuning on hardware** — the single biggest risk; measured every
  milestone, not deferred.
- 📱 **Field pilot** — 5 devices, one week, crash-free sessions > 99 %.
- 🔮 **v1.1 candidates** — QR sign-in, image send/receive, per-room mute, a KaiOS
  shell reusing `:core:model` + the UX spec.
- 🔬 **Research only** — [Tzibbur](docs/TZIBBUR.md) interop feasibility (no code yet;
  E2EE and licensing constraints to resolve first).

**Permanent non-goals:** spaces, threads, and any room/user discovery.
See [`PLAN.md`](PLAN.md) for the full plan, milestones, and rationale.

## Documentation

| File | What it is |
|---|---|
| [`PLAN.md`](PLAN.md) | Development plan: goals, stack, architecture, milestones, risks |
| [`AGENTS.md`](AGENTS.md) | Rules for AI agents (and new humans) contributing code |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Module contracts and data flow |
| [`docs/UX-SPEC.md`](docs/UX-SPEC.md) | Every screen, key map, and focus order |
| [`docs/VOICE.md`](docs/VOICE.md) | MatrixRTC + LiveKit voice-call interop with Element |
| [`docs/MDM.md`](docs/MDM.md) | Managed-configuration keys and the domain allow-list |
| [`docs/TZIBBUR.md`](docs/TZIBBUR.md) | Tzibbur integration feasibility (research note) |
| [`docs/adr/`](docs/adr) | One file per irreversible decision |

## Building

Requires the Android SDK (`compileSdk 35`, `minSdk 24`) and **JDK 17**.

```bash
./gradlew spotlessApply detektAll test   # format, static analysis, unit + architecture tests
./gradlew verifyPaparazziDebug           # screenshot diffs (240×320)
./gradlew :app:assembleDebug             # build the debug APK
./gradlew :app:installDebug              # install to the reference device
```

Architecture rules (SDK confined to `:core:matrix`, no feature→feature deps, no
discovery APIs) run as JVM tests under `org.matchat.client.arch.*`. For UI work,
use an emulator profile of **240×320 mdpi, API 24, touch disabled** — the nightly
key-only traversal suite runs there (`.github/workflows/traversal.yml`).

## Contributing

Read [`AGENTS.md`](AGENTS.md) first — it applies to humans too. The three rules
that matter most:

1. **Don't write Matrix protocol code** — the SDK does it.
2. **Don't write touch code** — D-pad + softkeys only.
3. **Don't add discovery** — no directory, no search, ever.

## License

Free software under the **GNU General Public License, version 2** — see
[`LICENSE`](LICENSE). Copyright © 2026 MatChat contributors.

GPLv2 keeps the source open and prevents proprietary forks, without GPLv3's
anti-tivoization terms — appropriate for an app that ships on locked-down
devices. The Matrix Rust SDK is Apache-2.0; if strict FSF compatibility matters
for your distribution, a narrow linking exception for the SDK can be added.

# MatChat — Software Development Plan

**A Matrix client for D-pad feature phones (dumbphones / kosher phones).**

> Application id `org.matchat.client`; display name **MatChat**. Both live in
> `gradle.properties` (`app.name`, `app.id`) so a later rename stays a one-line
> change. Check the name against Play/trademark use before any public release —
> it is a common word pairing.

---

## 1. Problem statement

Filtered-phone users (Kyocera flip phones, Sonim, TCL, other AOSP feature phones)
have no usable group-messaging option. SMS group chat is dying, and every
mainstream messenger ships a discovery surface — a directory, a people search,
a media feed — that makes the app unacceptable on a locked-down device.

Matrix is the right substrate: open protocol, self-hostable, the server can be
administered to hold the policy, and there are production-grade SDKs that mean
we write **UI only**.

## 2. Product goals

| # | Goal | Test |
|---|------|------|
| G1 | Fully operable with D-pad + 2 softkeys + Back/End. No touch, no gestures. | Automated key-only traversal test passes on every screen |
| G2 | Runs acceptably on a 2 GHz Helio A22 / Snapdragon 215, 2 GB RAM, 240×320 QVGA | Cold start < 2.5 s, steady RSS < 120 MB, no ANR under 500-message room |
| G3 | No *discovery*, ever — but membership can arrive by invitation or by a known address | No directory and no user-search API is reachable from any code path (lint rule). Joining happens only by accepting an invitation, or by starting a chat with an address the user already knows |
| G4 | End-to-end encrypted by default | Rust SDK crypto on; unencrypted room shows a warning banner |
| G5 | Legible without reading glasses at 2.6″ | Type floor: body 16 sp, interactive labels 14 sp, secondary metadata 11 sp — nothing below 11 sp. Focus highlight ≥ 2 dp. Contrast ≥ 7:1 for body and controls, ≥ 4.5:1 for metadata |
| G6 | Almost no protocol code of our own | `:core:matrix` is the only module that imports the SDK; it is < 1500 LOC |

### Explicit non-goals (v1)

Voice/video calls · spaces · threads · widgets · stickers · location sharing ·
public room directory · user directory search · browsing or searching for people
or rooms in any form · in-app browser or link previews · custom keyboard/T9 (the
system IME already does this) · tablet or smartphone layouts.

**The line that matters**: *knowing* an address is allowed; *finding* one is not.
A user may accept an invitation, or type `@wayne:example.org` and start a chat.
A user may never browse, search, or be shown a list of people or rooms they are
not already connected to.

## 3. Target hardware

| Device | SoC | RAM | Screen | OS |
|---|---|---|---|---|
| Kyocera DuraXV Extreme+ (E4811) — **reference device** | MediaTek Helio A22 | 2 GB | 2.6″ 240×320 | proprietary, AOSP-based |
| Kyocera DuraXV Extreme (E4810) | Snapdragon 215 | 2 GB | 2.6″ 240×320 | proprietary, AOSP-based |
| Kyocera DuraXE Epic | SD 632 | 2 GB | 2.4″ 320×240 | AOSP-based |
| Sonim XP3plus / TCL Flip Pro | — | 1–2 GB | 240×320 | AOSP-based |

Consequences:

- **`minSdk = 24`**, `targetSdk = 35`, with **core library desugaring on** (so
  `java.time` is usable below API 26). 24 covers the AOSP flips in the field;
  confirm the OS build of each pilot SKU before promising it.
- `targetSdk = 35` brings obligations that the sync design must satisfy — see
  §6.6: `foregroundServiceType="dataSync"` + `FOREGROUND_SERVICE_DATA_SYNC`
  (API 34+), `POST_NOTIFICATIONS` (API 33+), and Android 15's ~6 h/24 h cap on
  `dataSync` services.
- **No Google Play Services** on most of these devices. No FCM push, no Play
  Store, no Play Integrity. Delivery is sideload (ADB / WebADB), and sync is a
  foreground service — see §6.6.
- **Landscape variants exist** (DuraXE Epic is 320×240). Layouts must be
  written for a 240 dp-ish shortest width and must not assume portrait.
- **Both ABIs**: ship `armeabi-v7a` + `arm64-v8a` splits; the Rust `.so` is the
  bulk of the APK.

## 4. Stack decisions

| Layer | Choice | Why |
|---|---|---|
| Language | Kotlin, JVM 17 toolchain | — |
| Matrix protocol + crypto | **`org.matrix.rustcomponents:sdk-android`** (matrix-rust-sdk FFI, calendar-versioned, e.g. `25.9.10`) | Same binding Element X ships. Sync, timeline, E2EE, key backup, verification all come for free. We write zero protocol code. |
| UI toolkit | **Android Views + ViewBinding**, not Compose | Hardware D-pad focus is a first-class, decade-tested citizen of the View system (`nextFocusDown`, `descendantFocusability`, focus highlight drawables). Compose focus on non-TV key hardware is extra risk, extra APK, extra CPU on a Helio A22. See `docs/adr/0002`. |
| Lists | RecyclerView + ListAdapter/DiffUtil | Focusable item views, view recycling matters at 2 GB |
| Async | Coroutines + Flow | The FFI exposes callbacks; wrap once in `:core:matrix` |
| DI | Hilt | Conventional, agent-legible, no bespoke wiring |
| Persistence | SDK's own store (SQLite, managed by the SDK) | We persist **only** the session token, in a file encrypted with an Android Keystore AES key (`androidx.security:security-crypto` is deprecated — do not use it) |
| Nav | Single `Activity`, Fragments + Jetpack Navigation | Cheapest thing that gives back-stack + softkey ownership |
| Images | Coil, capped to 240 px decode | Only for avatars/inline images if enabled |
| Tests | JUnit5 + Turbine (unit), Paparazzi (240×320 screenshots), UiAutomator (key-only traversal) | §8 |

**Rule of the project:** if you find yourself writing a data class that mirrors a
Matrix event, a `/sync` parser, an Olm/Megolm call, or an HTTP request to a
homeserver — stop. The SDK already does it.

## 5. Architecture

Unidirectional data flow, strict module boundaries, one place for anything
device-specific.

```
                    ┌──────────────────────────────────────────┐
                    │  :app  (Application, DI graph, nav host, │
                    │         manifest, sync foreground svc)   │
                    └───────────────┬──────────────────────────┘
                                    │ depends on
   ┌────────────┬────────────┬───────┼────────┬────────────┬────────────┐
   │            │            │       │        │            │            │
:feature:    :feature:    :feature:  │   :feature:      :feature:   :feature:
 onboarding   roomlist     timeline  │    invites        newchat     settings
                                     │                                  +
                                     │                            verification
   └────────────┴────────────┴───────┴────────┴────────────┴────────────┘
                                    │ depends on
       ┌──────────────┬─────────────┼─────────────┬──────────────┐
       │              │             │             │              │
  :core:matrix   :core:ui     :core:policy   :core:contacts
 (ONLY module   (focus       (managed        (admin-pushed +
  allowed to     engine,      configuration   local contacts,
  import the     softkey      from the MDM    recent addresses)
  SDK; exposes   bar, theme,  restrictions
  domain models  widgets)     bundle; pinned
  + Flows)                    homeserver;
                              domain allowlist)
       │
  :core:model  (pure Kotlin data classes, no Android, no SDK)

        :core:testing (fakes: FakeMatrixClient, FakeTimeline, focus test rules)
```

### Dependency rules (mechanically enforced — see §8.4)

1. `:core:matrix` is the **only** module that may `import org.matrix.rustcomponents.*`.
2. `:feature:*` modules may not depend on each other. Cross-feature navigation
   goes through a `Navigator` interface implemented in `:app`.
3. `:core:model` has zero dependencies (no Android SDK, no coroutines-android).
4. Nothing depends on `:app`.
5. Any network/discovery/search API is reachable only via `:core:policy`, which
   has no method that returns a public-room list. G3 is structural, not a habit.

### Per-screen pattern (identical in every feature module)

```
feature/timeline/
├── TimelineFragment.kt        # renders State, emits Action. No logic.
├── TimelineViewModel.kt       # Action -> reduce -> StateFlow<TimelineState>
├── TimelineState.kt           # data class, everything the screen shows
├── TimelineAction.kt          # sealed interface of user intents
├── TimelineAdapter.kt         # RecyclerView, focusable rows
└── res/layout/fragment_timeline.xml
```

`State` is a single immutable data class. `Fragment.render(state)` must be a pure
function of it — no `if (view.isVisible)` reads, no ad-hoc mutation. This is what
makes screenshot tests and key-traversal tests possible, and it is what makes an
AI agent's diff reviewable.

### `:core:matrix` surface (the whole contract)

```kotlin
interface MatrixSession {
    val rooms: Flow<List<RoomSummary>>          // joined rooms only
    val invites: Flow<List<InviteSummary>>      // membership == Invited
    val syncState: Flow<SyncState>
    val ownDevice: Flow<DeviceTrust>
    fun timeline(roomId: RoomId): RoomTimeline
    suspend fun acceptInvite(roomId: RoomId): Result<Unit>
    suspend fun declineInvite(roomId: RoomId, ignoreSender: Boolean): Result<Unit>
    suspend fun lookupProfile(address: UserId): Result<Profile>   // known address, not a search
    suspend fun startDirectChat(address: UserId): Result<RoomId>  // is_direct + encrypted
    suspend fun logout()
}

interface RoomTimeline {
    val items: Flow<List<TimelineItem>>          // already paginated + deduped
    suspend fun paginateBack(count: Int = 20): Boolean
    suspend fun send(body: String)
    suspend fun markRead(eventId: EventId)
}

interface MatrixAuth {
    suspend fun signIn(user: String, password: String): Result<Unit>
    suspend fun signInWithQr(): Flow<QrLoginStep>   // MSC4108, if the server supports it
    suspend fun restoreSession(): Result<Unit>
}
```

Everything above returns `:core:model` types. No SDK type ever crosses this line.

## 6. Feature design

### 6.1 Navigation & key model (the heart of the product)

Feature phones have: D-pad (↑↓←→ + CENTER), **LEFT softkey**, **RIGHT softkey**,
CALL, END/BACK, 0-9, `*`, `#`.

Global contract, enforced by `:core:ui`:

| Key | Meaning — always, everywhere |
|---|---|
| ↑ / ↓ | Move focus within the current list/form |
| ← / → | Only where a screen declares horizontal focus; otherwise ignored (never "go back") |
| CENTER | Activate the focused item — identical to the **centre** softkey label |
| LEFT softkey | **Options** — opens the context menu for the current screen/item |
| RIGHT softkey | **Back** (top level: **Exit**) |
| END/BACK | Same as RIGHT softkey |
| `#` long-press | Jump to next unread room (power-user shortcut, documented in Help) |
| 0-9 | Type, when an input is focused; otherwise quick-select list item N |

Every screen extends `SoftkeyFragment`, which declares `leftLabel`, `rightLabel`,
`centerLabel` and the softkey bar renders them. A screen that does not declare
them fails a unit test. **There is no touch handling in this app at all** — no
`OnClickListener` on a non-focusable view, no swipe, no long-press-to-drag.

Focus rules:

- Every interactive view is `focusable=true` + `focusableInTouchMode=false`.
- Focus highlight is a 2 dp inverted border + background swap, defined once in
  `:core:ui/res/drawable/focus_selector.xml`. Never per-screen.
- Focus order follows XML order; explicit `nextFocusDown` only where the visual
  order differs, and then with a comment saying why.
- Lists keep focus on the item, not the container (`descendantFocusability=afterDescendants`).
- Opening a screen sets initial focus deterministically (first actionable item),
  restored on rotation/back.

### 6.2 Onboarding

1. **Welcome** → 2. **Sign in**. Two paths:
   - *QR sign in* (preferred): SDK's QR login (MSC4108) with the phone camera —
     no password typed on a multi-tap keypad. Requires an OIDC-native homeserver
     (MAS). Ship it behind `policy.qrLoginEnabled`.
   - *Username + password*, homeserver pre-filled and **not editable** when
     `policy.pinnedHomeserver` is set.
3. **Encryption setup**: verify against an existing device (emoji SAS) or enter
   an admin-issued recovery key. Recovery-key entry on a keypad is brutal — the
   field accepts the key in 4-char groups with auto-advance, and Options →
   "Verify with another device instead" is always offered.
4. **Done** → room list.

### 6.3 Room list

Rows: room name, last message preview (1 line, ellipsized), relative time, unread
count badge. Sorted by activity. Focus = full-row inverse.
Pending invitations sit in a bordered band directly under the title bar —
"1 invitation" — which is the first focus stop and opens S18.
Options menu: New message · Mark all read · Settings · Sign out. **No "join
room", no search, no directory.** If the user is in zero rooms the empty state
says *"Your groups will appear here. Ask your administrator to add you, or start
a message from Options."*

### 6.4 Timeline

- Compact list, sender name only when it changes (12 sp), 16 sp body, timestamp
  right-aligned at 11 sp, day separators at 11 sp.
- An **unencrypted** room shows a persistent one-line warning band above the
  timeline: "This group is not encrypted." (G4).
- Focus lands on a *message* — CENTER opens the message menu (Reply · Copy text ·
  Info · Delete if permitted).
- Paginate back 20 at a time when focus reaches the top item.
- Send state per message: sending (○), sent (✓), failed (!) with Options → Retry.
- Unable-to-decrypt renders as a distinct row with a "Fix encryption" action that
  routes to verification — not a scary grey blob.

### 6.5 Composing

Focus at the bottom input; CENTER sends — the centre softkey reads **Send**
while the input is focused. LEFT stays Options, RIGHT stays Back. The system
IME provides T9/multi-tap — **we do not write a keyboard**. `inputType` is
`textShortMessage|textCapSentences|textAutoCorrect`; IME action = send. While
the IME is up it may draw its own key labels over ours; our bar keeps its
meanings and is re-shown as soon as the IME closes.

### 6.6 Sync, notifications, battery

No Play Services ⇒ no FCM. Therefore:

- A **foreground service** owns the SDK sync loop, with a persistent low-priority
  notification ("MatChat is running"). At `targetSdk 35` this requires
  `android:foregroundServiceType="dataSync"` plus the
  `FOREGROUND_SERVICE_DATA_SYNC` permission, and `POST_NOTIFICATIONS` at
  runtime. **Android 15 caps `dataSync` at roughly 6 h per 24 h** — if any pilot
  device runs API 35, the service must fall back to a periodic
  `WorkManager` sync when the cap is hit, and that fallback is part of M1, not
  a later fix. Most flips in scope run older AOSP builds where the cap does not
  apply; verify per SKU rather than assuming.
- On app background, sync continues; on device idle, we request battery
  optimization exemption once during onboarding (documented; some carrier builds
  refuse it — measure).
- Notification per room, collapsed, with the room name and a count. Selecting it
  deep-links into that room's timeline.
- Target: < 2 % battery/hour idle-connected on the reference device. **Measure on
  hardware every milestone** — this is the single most likely thing to sink the
  project.

### 6.7 Lockdown policy (`:core:policy`)

Three layers, and it matters which does what.

**Layer 1 — structural, always on.** `:core:policy` exposes no API that can
return a public-room list or a user-search result, and a Detekt rule fails the
build on any reference to a directory/search call. This is the part no
configuration can turn on. Discovery is not a setting.

**Layer 2 — managed configuration (MDM), the domain allowlist.** The set of
homeserver domains a user may chat with is read at runtime from the Android
**application restrictions bundle** — the standard managed-configuration
mechanism (`RestrictionsManager`, `app_restrictions.xml`, pushed by whichever
EMM owns the device). Full key schema and worked examples in `docs/MDM.md`; the
shape is:

```xml
<restrictions xmlns:android="http://schemas.android.com/apk/res/android">
  <restriction android:key="pinnedHomeserver" android:restrictionType="string"/>
  <restriction android:key="allowedDomains"   android:restrictionType="string"/>
  <restriction android:key="allowDirectChat"  android:restrictionType="bool"/>
  <restriction android:key="invitePolicy"     android:restrictionType="choice"/>
  <restriction android:key="contacts"         android:restrictionType="string"/>
  <restriction android:key="mediaSend"        android:restrictionType="bool"/>
</restrictions>
```

**`allowedDomains` is fail-open, by your decision**: when the app is not managed
— no device owner, no profile owner, an empty bundle — every domain is allowed.
A managed device gets the list; an unmanaged one gets an open phone with no
discovery. The app re-reads the bundle on
`ACTION_APPLICATION_RESTRICTIONS_CHANGED` and applies changes without a restart,
and Settings shows which policy is in force ("Managed by your organization" vs
"Not managed") so the state is never invisible.

Be clear-eyed about what this is: **client-side enforcement is a guardrail, not
a security boundary.** It stops an ordinary user from messaging an outside
domain; it does not stop a modified APK, and an unmanaged device is open by
construction. If the allowlist ever has to actually hold, the same list also
goes into Synapse's `federation_domain_whitelist`, where the phone cannot argue
with it. `docs/MDM.md` carries both halves side by side so this stays a
deliberate choice rather than an assumption.

**Layer 3 — server.** Closed registration, deny-all
`room_list_publication_rules`, invite-only rooms, admin-provisioned accounts
(`docs/SERVER.md`). This layer holds regardless of what runs on the phone.

### 6.8 Invitations

Invites never auto-join. A pending invitation appears as a band on the room list
and as a row on **S18 Invitations**; opening it gives **S19**: who invited you,
their address, their server, the room name, whether it is encrypted, and two
actions — **Accept** and **Decline**. Decline offers "Decline and ignore this
person" as a second step, which is a server-side ignore, not a local hide.

An invitation from a domain outside `allowedDomains` is shown but not
acceptable: the detail screen says "Your organization does not allow messages
from example.org" and offers only Decline. It is never silently hidden — a user
who cannot see an invitation cannot ask anyone about it.

Invited rooms are ordinary rooms in the SDK (`RoomState::Invited`, with
`join()` / `leave()`), so there is no parallel invite store: we filter the room
list by membership state and render the two groups differently.

### 6.9 Direct chat by address

Options → **New message** (S20) offers, in order: **Contacts** (admin-pushed +
people already chatted with), **Recent** (last 8 addresses used), **Type an
address** (S21).

The address field pre-inserts the leading `@` and the `:`, so the user fills two
segments rather than punctuating on a keypad, and it defaults the right-hand
side to the last server used — which is most of the typing saved.

On submit:

1. Validate the shape locally. Malformed addresses fail here, cheaply.
2. Check the domain against `allowedDomains`. Blocked → **S22**, which names the
   domain and does nothing else.
3. Look up the profile over federation (`/profile/{userId}` — a *lookup of an
   address the user already has*, not a search; see the table in `AGENTS.md §0`).
   Confirm: "Send to Wayne Zimmerman (@wayne:example.org)?"
4. On confirmation, create an encrypted DM (create-room with `is_direct`, the
   trusted-private-chat preset, the address in `invite`) and open its timeline.

Step 3 earns its place: creating a room that invites a non-existent Matrix ID
does not reliably fail, so one keypad typo can leave an orphan room on the phone
forever. Confirm before creating, and if a DM's invitee never resolves, offer
"Leave and delete" in that timeline's Options.

### 6.10 Contacts and recents (`:core:contacts`)

Two sources, one list:

- **Admin-pushed** — the `contacts` restriction key, a JSON array of
  `{name, address}`. A *string* key rather than `bundle_array`, because
  `bundle_array` needs API 26 and we support 24.
- **Local** — everyone the user has exchanged a DM with, plus the last 8 typed
  addresses. Stored on the phone, never uploaded.

Merged, de-duplicated by address, admin entries first. This list is **not a
directory**: it contains only people the user already knows or the admin has
explicitly provided, it has no search box, and nothing but the managed
configuration can extend it.

## 7. Milestones

| M | Outcome | Done when |
|---|---|---|
| **M0** — Skeleton (1 wk) | Repo, modules, CI, Hilt graph, empty screens, softkey bar, focus engine, key-traversal test harness | An empty app installs on the reference device and every stub screen is reachable with the D-pad only |
| **M1** — Session (1 wk) | Sign in (password), session persist/restore, sync foreground service, sign out | Can log into the homeserver on-device and stay logged in across reboot |
| **M2** — Read (2 wk) | Room list + timeline, pagination, read receipts, day separators | Can read a 2 000-message room on-device without jank; RSS < 120 MB |
| **M3** — Write (1 wk) | Send, send-state, retry, notifications, deep link | Two flip phones hold a conversation |
| **M4** — Crypto (2 wk) | E2EE verified end-to-end, emoji SAS verification, recovery key, UTD recovery flow | New device verifies against an existing one and decrypts history |
| **M5** — Reach (2 wk) | Invitations (S18/S19), new message → contacts / recents / typed address (S20–S22), DM creation, `:core:contacts`, managed-configuration policy read + live reload, Settings "managed by" line | An invite from another homeserver can be accepted on the phone; a DM to a typed federated address works; pushing a new `allowedDomains` from the EMM blocks it without a restart |
| **M6** — Polish (2 wk) | Empty/error/offline states, battery tuning, Help screen, accessibility pass, string review | Battery target met on hardware; screenshot suite green |
| **M7** — Pilot (1 wk) | Signed APK, sideload + enrollment doc, 5-device field pilot, feedback loop | 5 users run it for a week; crash-free sessions > 99 % |
| **v1.1 candidates** | QR sign-in, image send/receive, per-room mute, contact QR/short codes, KaiOS shell (reuse `:core:model` + UX spec only) | — |

~12 weeks of focused work. Every milestone ends with an install on real hardware;
emulator-only milestones are not milestones.

## 8. Quality strategy

### 8.1 The key-only traversal test (the signature test of this project)

A UiAutomator test that, for every screen, starts at initial focus and walks the
D-pad in all four directions up to N steps, asserting:

- every declared interactive element is reachable,
- focus never escapes to an invisible or 0-size view,
- focus never enters a trap (a cycle that cannot return to the softkey bar),
- CENTER on every reachable element does not crash.

Run it in CI on an emulator configured 240×320 mdpi with touch disabled.

### 8.2 Screenshot tests

Paparazzi renders every screen's `State` at 240×320 (and 320×240) in normal +
largest font scale. A diff is a review conversation, not a merge blocker on
purpose — but an *unreviewed* diff blocks.

### 8.3 Unit tests

Reducers only, no Android. `TimelineViewModel` given a `FakeTimeline` from
`:core:testing`. Target: 100 % of reducer branches; no coverage target elsewhere
(coverage targets on UI code produce garbage tests).

### 8.4 Static enforcement

- **Spotless + ktlint** — formatting is never a review comment.
- **Detekt** with custom rules: `NoSdkImportOutsideCoreMatrix`,
  `NoDiscoveryApis`, `NoTouchListeners`, `NoHardcodedStrings`.
- **Konsist** architecture tests for the §5 dependency rules.
- **APK size check** in CI: fail if release APK > 25 MB per ABI split.

### 8.5 CI (GitHub Actions)

`spotless → detekt → konsist → unit → paparazzi → assembleRelease → size check`.
Instrumented traversal tests nightly (they are slow). PRs require green.

## 9. Repo layout

```
matchat/
├── AGENTS.md                  # read this first if you are an AI agent
├── PLAN.md                    # this file
├── README.md
├── docs/
│   ├── ARCHITECTURE.md        # module contracts, data flow
│   ├── UX-SPEC.md             # every screen, key map, focus order
│   ├── MDM.md                 # managed-configuration keys, EMM setup, fail-open
│   ├── SERVER.md              # Synapse hardening for the lockdown half
│   ├── DEVICE-SETUP.md        # sideloading to Kyocera flips, ADB over WebADB
│   └── adr/                   # one short file per irreversible decision
├── app/
├── core/{matrix,model,ui,policy,contacts,testing}/
├── feature/{onboarding,roomlist,timeline,invites,newchat,settings,verification}/
├── config/{detekt.yml,ktlint.editorconfig}
└── gradle/libs.versions.toml  # single source of dependency versions
```

## 10. Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Background sync killed by carrier/OEM battery policy | High | Fatal to usability | Foreground service + exemption request; measure at M1, not M5; fallback = poll on unlock |
| Rust SDK `.so` size / startup cost on 2 GB device | Medium | Medium | ABI splits, R8 full mode, measure cold start every milestone |
| E2EE key management UX on a keypad | High | High | QR/other-device verification as the primary path; recovery key entry is the fallback, not the default |
| Sideloading blocked on some carrier builds (locked bootloader, no dev options) | Medium | Blocks a device family | Validate on the exact SKU before promising it; document per-device in `DEVICE-SETUP.md` |
| Device has no camera / poor camera → QR login unusable | Medium | Low | Password path always available |
| **Softkey keycodes differ per SKU** — `KEYCODE_SOFT_LEFT`/`SOFT_RIGHT` are legacy constants many AOSP builds never dispatch; the left key often arrives as `KEYCODE_MENU`, the right as `KEYCODE_BACK`, or as an OEM-private code | High | Fatal to the core contract | Spike this on real hardware in **M0**, before anything else: a throwaway key-logger APK that prints every keycode. `:core:ui` maps OEM codes to the three logical keys in one table; the rest of the app never sees a raw keycode. Not discoverable on an emulator. |
| System IME on a given SKU behaves badly with our softkey bar | Medium | Medium | Test IME interaction per device at M3 |
| **Managed configuration never arrives** — these devices have no Play Services, so Android Enterprise's usual enrollment path is unavailable; restrictions require a device-owner or profile-owner DPC, which means an EMM agent that supports non-GMS AOSP, or `adb shell dpm set-device-owner` at provisioning | High | The allowlist silently does nothing (fail-open) | Decide the EMM before M5. Settings always states "Not managed" when the bundle is absent, so the state is visible. Where the boundary must hold, mirror the list into `federation_domain_whitelist` |
| Client-side allowlist bypassed by a modified APK, or a user moving the SIM to an unmanaged phone | Medium | The boundary was never a boundary | Accepted by design; documented in `docs/MDM.md`. Server-side list is the answer if it ever matters |
| Federated DM to a mistyped address creates an orphan room that never resolves | Medium | Low, but confusing on a phone with no other UI | Profile lookup + confirmation before create (§6.9); "Leave and delete" in Options |
| Remote homeserver refuses federation, or is slow — DM creation hangs | Medium | Medium | Timeout with a plain-language failure and a retry; never a spinner with no exit |
| Homeserver ops burden (accounts, rooms, invites by hand) | High | Medium | `docs/SERVER.md` + a small admin script; consider a provisioning web page in v1.1 |
| Corporate SSL inspection breaks Gradle/Rust downloads on the dev box | High (your network) | Low | See §11 |

## 11. Dev environment notes (DrawBridge / SSL-inspected network)

Builds pull from Maven Central and Google's Maven; on an SSL-inspecting proxy
they fail with `PKIX path building failed`. Fix once, per workstation:

1. Export the proxy root CA, then `keytool -importcert -cacerts -alias drawbridge
   -file drawbridge.crt`.
2. In `~/.gradle/gradle.properties`:
   `systemProp.https.proxyHost=… / .proxyPort=… / .nonProxyHosts=…`
   and `systemProp.javax.net.ssl.trustStore=…` if you use a private truststore.
3. Android Studio: *Appearance & Behavior → System Settings → HTTP Proxy*, and
   tick "Accept non-trusted certificates automatically" only if you must.
4. On-device testing goes through the proxy too — if the homeserver cert is
   re-signed, the SDK will reject it. Either exempt the homeserver domain from
   inspection, or install the proxy CA on the test phone (feature phones make
   this painful — exemption is the sane path).

## 12. Open questions

1. Homeserver: `chats.carpathianserver.org` is assumed (taken from the sign-in
   mockup). Confirm the Synapse version supports native sliding sync before M2,
   and confirm who administers accounts and room membership.
2. Are accounts admin-provisioned, or is there self-signup with an invite code?
   (Affects onboarding screens 2–3.)
3. Media: allow sending/receiving images at all in v1? Currently `mediaSend: true`,
   `linkPreviews: false` — confirm.
4. **Which EMM?** The domain allowlist rides on managed configuration, so the
   choice of EMM (one that supports non-GMS AOSP devices — SOTI, Ivanti, 42Gears
   and similar are the usual candidates) decides whether the allowlist ever
   arrives. Needed before M5. Same decision covers APK distribution.
5. Should `allowedDomains` also be mirrored into Synapse's
   `federation_domain_whitelist`? Client-side alone is a guardrail; server-side
   is the boundary. Recommendation: do both, and let the client copy exist for
   the message it shows the user rather than for enforcement.
6. `invitePolicy`: is "ask the user" right for everyone, or do some fleets want
   invites from allowlisted domains to auto-join? The key exists; the default is
   ask.
7. Exact device SKUs in the pilot — needed to validate sideload and enrollment
   before M7.

---

*Sources for the technical claims in §3–§4 are listed at the end of the chat
message that delivered this plan.*

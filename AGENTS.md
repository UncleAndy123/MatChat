# AGENTS.md — instructions for AI agents working on MatChat

You are contributing to a **Matrix client for D-pad feature phones** (Kyocera
flip phones and similar AOSP dumbphones). Read this file completely before your
first edit. `PLAN.md` is the why; `docs/ARCHITECTURE.md` and `docs/UX-SPEC.md`
are the contracts; this file is the how.

If a rule here conflicts with your general habits, this file wins. If a rule here
conflicts with something a human told you in the task, the human wins — and then
you update this file in the same PR.

---

## 0. The three rules that matter most

1. **Do not write Matrix protocol code.** Sync, events, rooms, timelines,
   encryption, key backup, verification — all of it comes from
   `org.matrix.rustcomponents:sdk-android`. If you are writing a `/sync` parser,
   an event data class, an Olm/Megolm call, or an HTTP request to a homeserver,
   you have taken a wrong turn. Only `:core:matrix` may touch the SDK.
2. **Do not write touch code.** No `OnClickListener` on a non-focusable view, no
   gestures, no swipes, no `ScrollView` that only scrolls by drag. Every
   interaction is D-pad + softkeys. See §4.
3. **Do not add discovery.** The rule is not "no federation" — it is
   ***knowing* an address is allowed; *finding* one is not.**

   | Allowed | Forbidden |
   |---|---|
   | Accepting or declining an invitation | Public room directory, in any form |
   | Starting a DM with an address the user typed or picked from Contacts | User-directory search (`/user_directory/search`) |
   | `/profile/{userId}` lookup of a **specific known** address, to show a name before sending | Any call that takes a *query* and returns people or rooms |
   | Contacts and recents (people already known, or admin-pushed) | A search or filter box over anything, including the user's own rooms |
   | Federated addresses on other homeservers, subject to `allowedDomains` | Browsing members of a room the user is not in |

   A Detekt rule (`NoDiscoveryApis`) fails the build on the right-hand column.
   Do not suppress it. If you think you need something in that column, stop and
   ask — the answer has been no every time so far.

---

## 1. Before you start a task

- [ ] Read the issue/task and restate the acceptance criteria in your PR
      description.
- [ ] Find the screen in `docs/UX-SPEC.md`. If the screen is not specified there,
      **stop and ask** — do not invent UX. Adding a screen means editing the spec
      first, in the same PR.
- [ ] Check `gradle/libs.versions.toml` before adding any dependency. Adding a
      dependency requires a one-paragraph justification in the PR and an ADR if
      it is architectural.
- [ ] Grep for prior art: `rg "class .*ViewModel" feature/` — copy the shape of
      the nearest existing feature rather than inventing a new one.

## 2. Repository map

```
app/                     Application, Hilt graph, nav host, sync foreground service
core/model/              Pure Kotlin data classes. No Android. No SDK. No coroutines-android.
core/matrix/             The ONLY module that imports the Rust SDK. Exposes core/model types + Flows.
core/ui/                 Focus engine, softkey bar, theme, shared widgets, focus_selector drawable.
core/policy/             Managed configuration (the MDM restrictions bundle): pinned homeserver,
                         allowedDomains, invitePolicy, admin contacts. The ONLY module that touches
                         RestrictionsManager. No method here returns a room list or a search result.
core/contacts/           Admin-pushed contacts (via policy) + local contacts and recent addresses.
core/testing/            Fakes: FakeMatrixSession, FakeTimeline, FakePolicy, focus test rules. Test-only.
feature/onboarding/      Welcome, sign in, encryption setup
feature/roomlist/        Room list + pending-invitation band
feature/timeline/        Timeline + compose + message menu
feature/invites/         Invitation list, invitation detail, accept / decline
feature/newchat/         New message: contacts, recents, type an address, blocked address
feature/settings/        Settings, help, sign out
feature/verification/    Emoji SAS, recovery key
docs/                    ARCHITECTURE.md, UX-SPEC.md, MDM.md, SERVER.md, DEVICE-SETUP.md, adr/
config/                  detekt.yml, ktlint config
```

### Dependency rules (Konsist tests enforce these — they will fail your PR)

- `:core:matrix` is the only module allowed `import org.matrix.rustcomponents.*`.
- `:feature:*` may **not** depend on another `:feature:*`. Cross-feature
  navigation goes through the `Navigator` interface, implemented in `:app`.
- `:core:model` depends on nothing.
- Nothing depends on `:app`.
- No SDK type may appear in any public signature outside `:core:matrix`.
- `:core:policy` is the only module that may touch `RestrictionsManager`,
  `app_restrictions.xml`, or the `ACTION_APPLICATION_RESTRICTIONS_CHANGED`
  broadcast. Everything else reads an immutable `Policy` object from Hilt.

### Policy rules

- **`allowedDomains` is fail-open**: an absent or empty bundle means *allow every
  domain*, because most devices are not managed. Never invert this "for safety" —
  it would leave an unmanaged phone unable to message anyone, with no way for the
  user to tell why. `docs/MDM.md` explains the reasoning.
- Policy is **live**: the app re-reads the bundle on
  `ACTION_APPLICATION_RESTRICTIONS_CHANGED`, so `Policy` is a `Flow`, not a value
  read once at startup. A screen that caches an allow/deny decision across a
  policy change is a bug.
- A blocked address is **explained, never hidden**. Name the domain, say the
  organization does not allow it, and stop. Do not degrade silently, do not offer
  a workaround, and do not show a blocked invitation as if it did not exist.

## 3. The per-screen pattern — copy it exactly

Every screen is five files. Do not deviate; consistency here is what lets the
next agent work without re-reading the codebase.

```
feature/<name>/
├── <Name>Fragment.kt      renders State, emits Action. Contains no logic and no branching beyond render().
├── <Name>ViewModel.kt     Action → reduce → StateFlow<State>. All logic lives here.
├── <Name>State.kt         one immutable data class; everything the screen displays
├── <Name>Action.kt        sealed interface of user intents
└── res/layout/fragment_<name>.xml
```

Non-negotiables:

- `render(state: State)` is a **pure function of state**. Never read a view's
  current value to decide what to do. Never mutate a view outside `render`.
- The ViewModel never imports `android.view`, `android.widget`, or anything from
  `:core:ui`.
- The Fragment never imports anything from `:core:matrix`.
- State classes carry display-ready values (`"3:42 PM"`, not `Instant`). Formatting
  happens in the ViewModel so it is unit-testable.
- Loading/empty/error are **states in the data class**, not separate code paths.

```kotlin
// good
data class RoomListState(
    val rooms: List<RoomRow> = emptyList(),
    val isSyncing: Boolean = false,
    val error: ErrorText? = null,
    val focusedIndex: Int = 0,
)
```

## 4. D-pad and softkey rules

The full key map is in `docs/UX-SPEC.md §2`. What you must obey in code:

- Every screen extends `SoftkeyFragment` and declares `leftLabel`, `rightLabel`,
  `centerLabel`. A screen that does not declare them fails a unit test. A label
  may be **empty** (a screen with no options leaves LEFT blank) — but the
  declaration is still required, explicitly.
- **LEFT softkey = Options. RIGHT softkey = Back. CENTER = activate the focused
  item.** Always. No screen reassigns a key to a different *meaning*; a screen
  may change the wording of the centre label (`Open`, `Send`, `Select`), leave
  LEFT blank when it has no options, and choose what Options contains. RIGHT is
  Back on every screen without exception (top level: `Exit`).
- Raw key codes are handled in **one** place: `:core:ui`'s key map. Softkey
  codes vary by device — `KEYCODE_SOFT_LEFT`/`SOFT_RIGHT` are often not
  dispatched at all, and the keys arrive as `KEYCODE_MENU`/`KEYCODE_BACK` or an
  OEM-private code. Never handle a raw keycode in a feature module; add the
  device's code to the map instead.
- Every interactive view: `android:focusable="true"`,
  `android:focusableInTouchMode="false"`, and uses
  `@drawable/focus_selector` from `:core:ui`. Never write a per-screen focus
  highlight.
- Focus order follows XML order. If you need `nextFocusDown`/`nextFocusUp`, add a
  comment on the same line explaining why the visual order differs.
- Every screen sets deterministic initial focus in `onViewCreated` and restores
  focus after configuration change and after returning from a child screen.
- Lists: `android:descendantFocusability="afterDescendants"`, focus lives on the
  row, the row is the click target.
- **Never** add `android:clickable="true"` to something that is not focusable.

## 5. UI constraints (2.6″ QVGA, 240×320)

- Type floor, no exceptions: **body 16 sp · interactive labels 14 sp · secondary
  metadata (timestamps, day separators, sender names, field captions) 11 sp**.
  Nothing a user reads goes below 11 sp.
- Design for 240 dp width **and** 320×240 landscape devices. Rows have a
  `minHeight`, never a fixed `height` — content must be free to grow at the
  largest font scale, which the screenshot suite checks.
- Contrast ≥ 7:1 for body text and anything focusable; ≥ 4.5:1 for metadata.
  The palette is in `:core:ui/res/values/colors.xml` — use the
  named roles (`text_primary`, `surface_focused`, …), never a raw hex in a layout.
- One screen = one job. If a screen needs a scroll of more than ~3 screens' worth
  of content, it is two screens.
- No animation longer than 150 ms; no shared-element transitions; no ripples.
- All strings in `strings.xml`, with a comment for translator context. No string
  concatenation for sentences — use placeholders.

## 6. Testing — what your PR must include

| You changed | You must add |
|---|---|
| A reducer / ViewModel | JUnit test with a fake from `:core:testing`, covering every branch |
| A screen's layout or render | Paparazzi screenshot at 240×320, normal + largest font scale |
| Focus order or a new screen | Entry in the key-only traversal test (`app/src/androidTest/.../TraversalTest.kt`) |
| `:core:matrix` | A test against the fake; do **not** write a test that hits a real homeserver |
| Anything at all | `./gradlew spotlessApply detekt konsistTest test` green locally |

Do not write tests that assert implementation details (that a method was called
N times). Assert the state that resulted.

## 7. Commands

```bash
./gradlew spotlessApply            # format — run before every commit
./gradlew detekt konsistTest       # static + architecture rules
./gradlew test                     # JVM unit tests
./gradlew verifyPaparazziDebug     # screenshot diffs
./gradlew recordPaparazziDebug     # accept intentional screenshot changes
./gradlew :app:assembleDebug
./gradlew :app:installDebug        # reference device: Kyocera DuraXV Extreme+, adb over USB
./gradlew :app:connectedAndroidTest  # includes the D-pad traversal suite (slow)
```

Emulator profile for UI work: `240x320 mdpi, API 24, touch disabled, hardware
keyboard off`. If you tested only on a phone-sized emulator, you did not test.

## 8. Definition of done

A change is done when **all** of these hold:

- [ ] Acceptance criteria from the task are met and restated in the PR body.
- [ ] Reachable and operable with D-pad + softkeys only, verified in the traversal
      test — not by reasoning about it.
- [ ] `spotless`, `detekt`, `konsist`, `test`, `paparazzi` are green.
- [ ] No new dependency without justification; no new module without an ADR.
- [ ] `docs/UX-SPEC.md` updated if any screen, key binding, or label changed.
- [ ] No TODO left behind without a linked issue number.
- [ ] Diff contains no unrelated formatting churn.

## 9. Things agents get wrong on this project (read twice)

- **Reaching for Compose.** This project uses Android Views deliberately
  (`docs/adr/0002`). Do not migrate, do not "just use Compose for this one
  screen."
- **Re-implementing what the SDK does.** Pagination, dedup, read receipts, key
  sharing, decryption retries — the SDK handles all of it. Look for the SDK API
  before writing 200 lines.
- **Adding a search box.** Any search box. Even "just to filter my own rooms",
  even over Contacts — search boxes are how discovery comes back. Contacts and
  recents are short, ordered lists you scroll with the D-pad, and they stay that
  way.
- **Treating "no discovery" as "no federation".** A user may absolutely message
  `@someone:another-server.org` if they know the address and the domain is
  allowed. Read the table in §0 before restricting anything on this basis.
- **Reading policy once at startup.** It changes underneath you when the EMM
  pushes an update; observe the `Flow`.
- **Assuming a touchscreen.** These devices have none. A dialog with a
  touch-only dismiss is a bricked screen.
- **Assuming Google Play Services.** No FCM, no Play Store, no Maps, no
  `com.google.android.gms.*`. Sync is a foreground service.
- **Optimistic string sizing.** "Sign in to your homeserver" wraps to three lines
  at 240 px. Check every label in the screenshot test.
- **Silent failure states.** On a flip phone the user has no other channel to
  diagnose with. Every failure gets a visible, plain-language message and a
  retry action.
- **Chatty logging.** No PII, no message content, no access tokens in logs. Ever.

## 10. When you are unsure

Stop and ask, with a specific question and a proposed default. Do not guess at:
product scope, a new screen, a lockdown-policy change, a new dependency, or
anything touching encryption. Guessing is cheap for you and expensive for the
people carrying these phones.

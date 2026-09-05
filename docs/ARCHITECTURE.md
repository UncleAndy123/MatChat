# Architecture — MatChat

Companion to `PLAN.md §5`. This file is the contract; `PLAN.md` is the reasoning.

## Layers

```
Fragment  ──emits──▶  Action  ──▶  ViewModel (reduce)  ──▶  StateFlow<State>  ──renders──▶  Fragment
                                        │
                                        ▼
                              :core:matrix interfaces
                                        │
                                        ▼
                      org.matrix.rustcomponents:sdk-android (FFI)
                                        │
                                        ▼
                              homeserver (Synapse)
```

One direction. No callbacks upward, no `Fragment → SDK`, no `ViewModel → View`.

## Module contracts

### `:core:model` — the shared vocabulary
Pure Kotlin. Every type is an immutable `data class` or `value class`.

```kotlin
@JvmInline value class RoomId(val value: String)
@JvmInline value class EventId(val value: String)
@JvmInline value class UserId(val value: String)

data class RoomSummary(
    val id: RoomId,
    val name: String,
    val lastMessage: String?,
    val lastActivityEpochMs: Long?,   // no java.time here: :core:model depends on nothing
    val unreadCount: Int,
    val isEncrypted: Boolean,
)

sealed interface TimelineItem {
    data class Message(
        val eventId: EventId, val sender: UserId, val senderName: String,
        val body: String, val timestampEpochMs: Long, val isOwn: Boolean,
        val sendState: SendState,
    ) : TimelineItem
    data class DaySeparator(val label: String) : TimelineItem
    data class UnableToDecrypt(val eventId: EventId, val sender: UserId) : TimelineItem
    data class StateChange(val text: String) : TimelineItem
}

enum class SendState { SENDING, SENT, FAILED }
enum class SyncState { IDLE, SYNCING, OFFLINE, ERROR }

data class InviteSummary(
    val roomId: RoomId,
    val roomName: String,
    val inviter: UserId,
    val inviterName: String?,      // null until the profile lookup resolves
    val isDirect: Boolean,
    val isEncrypted: Boolean,
    val senderDomain: String,      // shown to the user, and checked against policy
    val allowedByPolicy: Boolean,  // false => the screen offers Decline only
)

data class Profile(val userId: UserId, val displayName: String?)

data class Contact(
    val address: UserId,
    val name: String?,
    val source: Source,            // ADMIN (pushed by policy) or LOCAL (already chatted / typed)
) { enum class Source { ADMIN, LOCAL } }
```

Nothing here knows about Android or about Matrix wire formats.

### `:core:matrix` — the only place the SDK exists
Responsibilities: session lifecycle, mapping SDK types → `:core:model`, exposing
Flows, owning the sync service's client instance.
Non-responsibilities: business rules, formatting, policy, threading decisions
beyond `Dispatchers.IO`.

Public surface is exactly the interfaces in `PLAN.md §5` — including invites
(`RoomState::Invited` rooms, `join()` / `leave()`), `lookupProfile` (a lookup of
a known address, never a search) and `startDirectChat` (create-room with
`is_direct`, trusted-private-chat preset, encryption on) — plus
`MatrixSessionStore` (persist/restore the session, encrypted with an Android
Keystore AES-GCM key — **not** `androidx.security:security-crypto`, which is
deprecated).

Mapping lives in `internal/Mappers.kt` and is unit-tested against recorded SDK
fixtures. If the SDK changes shape on upgrade, exactly one file breaks.

### `:core:ui` — the device abstraction
- `KeyMap` — the single translation from raw `KeyEvent` codes to the three
  logical keys (LEFT / CENTRE / RIGHT) plus the D-pad. Device softkeys are not
  uniform: `KEYCODE_SOFT_LEFT`/`SOFT_RIGHT` are frequently never dispatched, and
  the keys show up as `KEYCODE_MENU`, `KEYCODE_BACK`, or an OEM code. The map is
  per-device data, established by an M0 spike on real hardware. Nothing outside
  this file reads a keycode.
- `SoftkeyFragment` (declares `leftLabel`/`centerLabel`/`rightLabel`, renders the
  bar, receives logical keys from `KeyMap`).
- `FocusEngine` — deterministic initial focus, focus restoration across
  configuration change and back-navigation, traversal assertions used by tests.
- `MenuSheet` — the only menu construct in the app (S11-style list).
- Theme, type scale, `focus_selector`, colour roles.

This is the **only** module allowed to contain custom `View` subclasses or key
handling. A feature that needs a new interaction primitive adds it here, with a
screenshot test, not in the feature module.

### `:core:policy` — managed configuration
The only module that touches `RestrictionsManager`. It reads the application
restrictions bundle, maps it to an immutable `Policy`, and re-emits on
`ACTION_APPLICATION_RESTRICTIONS_CHANGED`:

```kotlin
data class Policy(
    val isManaged: Boolean,             // false = no DPC, empty bundle
    val pinnedHomeserver: String?,
    val allowedDomains: List<String>?,  // null = unmanaged = allow everything
    val allowDirectChat: Boolean = true,
    val invitePolicy: InvitePolicy = InvitePolicy.ASK,
    val adminContacts: List<Contact> = emptyList(),
    val mediaSend: Boolean = true,
) {
    fun allows(address: UserId): Boolean =
        allowedDomains?.contains(address.domain) ?: true   // fail-open, deliberately
}

interface PolicyProvider { val policy: StateFlow<Policy> }
```

`allowedDomains == null` means *unmanaged*, and unmanaged means *allow*. That is
the product decision (`docs/adr/0005`), not an oversight — and it is why the
client-side allowlist is a guardrail rather than a boundary.

The module still has no method that can return a public-room list, a user-search
result, or an arbitrary homeserver URL. G3 is enforced by the absence of API,
backed by the `NoDiscoveryApis` Detekt rule.

### `:core:contacts` — people the user already knows
Merges `Policy.adminContacts` with locally stored contacts (anyone the user has
exchanged a DM with) and the last 8 typed addresses. Exposes
`Flow<List<Contact>>`, de-duplicated by address, admin entries first. It has no
query method — the whole list is short by construction, and there is nothing to
search.

### `:feature:*`
Five files per screen (see `AGENTS.md §3`). No feature-to-feature dependency.
Navigation via `Navigator`:

```kotlin
interface Navigator {
    fun toRoom(roomId: RoomId)
    fun toInvites()
    fun toInvite(roomId: RoomId)
    fun toNewChat()
    fun toVerification()
    fun toSettings()
    fun back()
}
```

implemented once in `:app` over Jetpack Navigation.

### `:app`
Application class, Hilt graph, `MainActivity` (single activity, owns the nav
host and the global key dispatcher), `SyncForegroundService`, notification
plumbing, deep links, manifest.

## Threading

- SDK calls: `Dispatchers.IO`, inside `:core:matrix` only.
- Reducers: `Dispatchers.Default` if they do work; otherwise immediate.
- `render()`: main thread, driven by `repeatOnLifecycle(STARTED)`.
- No `GlobalScope`. Every coroutine has a lifecycle-scoped parent.

## Error handling

`:core:matrix` returns `Result<T>` or emits an `error` field into state — it never
throws across the module boundary. Every error surfaced to a user maps to a
`ErrorText` in `:core:model` with a plain-language string resource and an
optional retry `Action`. No stack traces reach a screen. No silent catches:
`catch { }` without a log and a state change fails review.

## Sync lifecycle

```
App start → restore session → start SyncForegroundService
                                  │
                       SDK SyncService (sliding sync)
                                  │
             rooms Flow ──────────┴────────── timeline Flows (per open room)
```

The service is the single owner of the client. Screens observe; they never start
or stop sync. Backgrounding does not stop sync; sign-out does.

## Performance budget (checked at every milestone, on hardware)

| Metric | Budget |
|---|---|
| Cold start to room list (warm session) | < 2.5 s |
| Room list scroll (focus move) frame time | < 32 ms |
| Steady-state RSS with 3 rooms open in history | < 120 MB |
| Release APK per ABI split | < 25 MB |
| Idle-connected battery | < 2 %/hour |

Regressions in these are treated as bugs, not as "polish later".

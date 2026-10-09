# UX Specification — MatChat

Reference viewport: **240 × 320 px, mdpi (1×), 2.6″**. Landscape variant
**320 × 240** must not break. All measurements below are dp = px at mdpi.

## 1. Chrome

Every screen has three fixed bands:

```
┌────────────────────────────┐  ← 240 wide
│ Title bar            24 dp │  title left, sync/battery glyphs right
├────────────────────────────┤
│                            │
│ Content            270 dp  │  scrolls by focus movement only
│                            │
├────────────────────────────┤
│ Options | Select |  Back   │  softkey bar, 26 dp
└────────────────────────────┘
```

- Title bar: scales with Settings > Text size (§S16) like everything else —
  18 sp by default (Normal), 14 sp at Small — single line, ellipsized at the
  end, on the accent surface (`?attr/colorFocusAccent` — the user's chosen
  accent, Settings > Theme, not the inverse/near-black surface used
  elsewhere). Right side shows sync state (`⟳` syncing, `!` offline) —
  nothing else.
- Softkey bar: three cells, left-aligned / centre / right-aligned, 16 sp (its
  own dedicated size, `text_softkey_label` — not the shared 11 sp metadata
  floor). Fixed, unlike the title bar — it does NOT scale with Settings >
  Text size (§S16): a chrome band, not a row, so it stays this one size
  regardless of the Normal/Small setting, to avoid clipping a band that's
  already nearly full. On the same accent surface as the title bar so it is
  never confused with content.
- Content never scrolls by pixel drag. It scrolls because focus moved.
  One documented exception (S9, long-message round): a message bubble
  taller than the visible list area scrolls in small fixed steps (~0.75")
  per DOWN/UP press before focus moves to the next/previous row — still
  driven entirely by key presses, never by touch/drag.

## 2. Key map (global, unchangeable)

| Key | Behaviour |
|---|---|
| ↑ ↓ | Move focus. At list end, stop — do not wrap (wrapping disorients on a small viewport). |
| ← → | Ignored. **No v1 screen declares horizontal focus** — every list, form and grid is traversed with ↑↓ only. Never means "back". |
| CENTER | Activate focused item — identical to the centre softkey label. |
| LEFT softkey | **Options** — context menu for the screen + focused item. Blank on a screen that has no options; never reassigned to anything else. |
| RIGHT softkey | **Back**. On the room list (top level): **Exit**, with confirm. |
| END / BACK | Same as RIGHT softkey. |
| CALL | Ignored (never places a call from inside the app). |
| 0–9 | Text entry when an input is focused; otherwise jump to list item *n*. |
| `#` (hold) | Next unread room. |
| `*` (hold) | Cycle text size (Small → Normal → Large, §S16). |

"Unchangeable" above means no *screen* ever reassigns LEFT/RIGHT to a
different meaning (AGENTS.md §4) — Options and Back are always exactly one
softkey each. Settings > Advanced > "Swap Left/Right keys" (S25, docs/adr/0007)
is a separate, global exception, **on by default** (Options on the physical
right, Back on the left) with the toggle still there for anyone who prefers
the original left-Options layout or has hardware with reversed physical
softkeys: it flips which *physical* key produces which *logical* one, for
every screen at once, not a per-screen reassignment.

**Type floor** (matches `PLAN.md` G5 and `AGENTS.md §5`), at the Small text
size — the default (Normal) is larger, §S16: body 16 sp · interactive labels
14 sp · secondary metadata — timestamps, day separators, sender names, field
captions — 11 sp; softkey labels 16 sp, their own dedicated, fixed size (§1).
Nothing below 11 sp.

Focus highlight: a subtle full-row tint (`?attr/colorSurfaceFocused`) plus a
solid accent bar on the trailing edge (`?attr/colorFocusAccent`,
`@dimen/focus_bar_width`), no rounded corners — replaces the earlier full-width
bordered block. The accent bar is now the primary at-a-glance cue; this
trades some of the old block's raw contrast for a lighter-weight look, so
re-check sunlight legibility (PLAN.md G5) once this ships. `colorSurfaceFocused`
is a translucent wash of the user's chosen accent (`colors.xml`'s
`accent_<name>_tint`, ~20% alpha, assigned per leaf in `themes.xml`) — it
used to be a fixed green in every theme regardless of accent, which was a
bug (found via screenshot: the room-list selection highlight stayed green
under the Blue/Amber/Plum accents). The same tint drives S9's
`colorBubbleOwn` (below) for the same reason.

## 3. Screen inventory

Each screen lists: purpose · content · focus order · softkeys · Options menu ·
empty/error states.

### S1 — Splash / restoring
Purpose: cover session restore.
Content: app name, "Signing you in…", spinner.
Focus: none. Softkeys: blank | blank | Cancel (after 5 s).
Error: "Could not reach the server." + Retry (focused) / Sign out.

### S2 — Welcome
Content: app name, one line of purpose text, two buttons.
Focus order: `Sign in with QR code` → `Sign in with password` → `Help`.
Softkeys: Options | Select | Exit.
Options: Help · About.
(QR button hidden when `policy.qrLoginEnabled = false`.)

### S3 — Sign in (password)
Content: homeserver row (read-only when pinned, shown as grey text with a lock
glyph), `Username` field, `Password` field, `Sign in` button.
Focus order: Username → Password → Sign in.
Softkeys: Options | Select | Back.
Options: Sign in with QR code · Help · About.
Errors: inline under the field, red, plain language — "That username or password
did not work." / "No network. Check signal and try again."

### S4 — Sign in (QR)
Content: camera viewfinder framed to 200 × 200, instruction line beneath:
"On your other device: Settings → Link a device."
Softkeys: blank | blank | Back.
Error: "Camera unavailable — use password sign-in instead." (action focused)

### S5 — Encryption setup
Content: heading "Protect your messages", explanation (2 lines, 8th-grade
reading level), buttons.
Focus order: `Verify with another device` → `Enter recovery key` → `Skip for now`.
Skip shows a confirm: "Messages already sent to you will stay unreadable."
Softkeys: Options | Select | Back.

### S6 — Emoji verification (SAS)
Content: "Do these appear on your other device?", 7 emoji in a 4 + 3 grid, each
with its word label beneath at 11 sp, then `They match` / `They do not match`.
Focus: the grid is display only; only the two buttons are focusable, ↑↓.
Softkeys: Options | Select | Cancel.
Timeout state: "Verification timed out." + Try again.

### S7 — Recovery key entry
Content: label, single field showing the key in 4-character groups with
auto-advance, character counter `12 / 48`, `Continue` button.
Options: Verify with another device instead · Paste from clipboard.
Softkeys: Options | Select | Back.

### S8 — Room list *(home)*
Rows (44 dp each): a 32 dp room avatar (a plain filled circle placeholder
until the real image decodes, or when none is set — Avatars round) at the
start · room name 16 sp bold · last message 13 sp grey, one line
ellipsized · relative time 11 sp top-right · unread badge (inverse pill, count)
right of the name.
Sorted by most recent activity. Focus = the standard focus highlight (§2).
**Pending invitations** appear as an 18 dp band directly under the title bar —
"1 invitation" / "3 invitations", with the count in an inverse pill. It is
the *first* focus stop and opens S18; when focused it uses the
standard focus highlight like any other row (§2, everywhere, and nothing
else). No band when there are none.
Focus order: invitation band (if any) → row 1 → row *n*. Initial focus:
invitation band, else first unread, else row 1.
Softkeys: Options | Open | Exit.
Options: New message · Mark all as read · Settings · Help · Sign out.
Empty: "Your groups will appear here. Ask your administrator to add you, or
start a message from Options."
Offline: title bar `!` plus a 16 dp banner "No connection — showing saved
messages."

### S9 — Timeline
Content: day separator rows (centred, 11 sp, grey rule); message rows shown
as bubbles reaching nearly the full row width (matching the reference
device's own SMS app) — own messages trail (right, a light accent-tinted
fill) and others lead (left, a neutral fill); a 16 dp sender avatar sits
beside the sender name (Avatars round), shown/hidden together. A member
with no avatar set shows a colored circle with their initial instead of a
flat placeholder (AvatarFallback round) — a deterministic color per user
id, from a small fixed palette that stays constant across the app's
Light/Dark/accent theme; the sender name text is colored to match. 12 sp
coloured inside the bubble (shown only when the sender changes), body 16 sp,
time 11 sp shown below the bubble on the same side, with a send-state glyph
on own messages (`○` sending, `✓` sent, `!` failed). Reactions (Reactions
round) show as a row of read-only "emoji count" chips below the time line
— bolder/accent-colored for a reaction we sent — reached via the message
menu's `React` item, never by tapping a chip (a D-pad row can't usefully
offer several separately focusable chips). A message another member (besides the sender and the current user) has
read also shows a short "seen by" row of small avatars (up to 4, then
"+N", each overlapping the previous one) under the time line — shown for
both own and received messages. Every bubble carries a
colored stripe (the user's chosen accent, Settings > Theme) on its leading
edge for received messages, trailing edge for own — the bubble is rounded
only on the side away from its stripe (square where the stripe sits, so it
sits flush, not a full rounded rect). A focused bubble's border recolors to
the accent and thickens, in place of the app's usual flat-fill-plus-bar
focus style (AGENTS.md §4's named exception) — the row itself has no
background.
**Pinned-messages band** (Pinned messages quick-access round): when the room
has ≥1 pinned message, a band reading "📌 N pinned message(s) ›" sits at the
very top of the content, above the unencrypted-warning band — same row
shape as the room list's invitation band. It's the first focus stop, CENTER
opens Pinned messages, but walking UP from the newest message to reach it
is impractical in a long room, so D-pad **RIGHT** also jumps straight there
from anywhere in the message list — except while the compose box is
focused, where RIGHT stays with the text cursor as normal (a narrow,
documented exception to "directional keys are never reassigned per
screen," scoped to this one shortcut — see `DirectionalKeyReceiver`).
Hidden entirely when nothing is pinned.
If the room is **not encrypted**, a persistent 14 dp band sits directly under the
title bar (below the pinned band, if both show): "This group is not encrypted."
(G4). Encrypted rooms show nothing — encryption is the norm, not a decoration.
Bottom: a one-line message input strip (18 dp) that is the **last** focus stop.
Focus order: pinned band (if any) → oldest-loaded message → … → newest →
input strip. Initial focus: input strip (people come here to reply), ↑ walks
back through history.
Reaching the top item triggers `paginateBack(20)`; a 16 dp "Loading earlier
messages…" row appears while it runs.
Softkeys: Options | Select | Back.
Options: Room info · Mark as read · Mute this group · Help.
Special rows:
- **Unable to decrypt** — italic "This message can't be read on this phone yet."
  + inline action `Fix encryption` (focusable) → S6.
- **Unsent** — red `!`, Options on that row offers Retry / Delete.
Empty: "No messages yet. Say hello."

**Full-screen image viewer** (reached from an image message's `Open`): the
image fills the screen; `*` zooms in, `#` zooms out, the D-pad / 2468 pan, `0`
resets, Back returns. Softkeys: Options | | Back — Options offers `Save to
gallery` and `Save to files` (same destinations and permission behaviour as
the S11 items above), acting on the image already on screen.

### S10 — Compose (input focused)
The input strip expands to 5 lines max as text grows; the timeline shrinks.
System IME (T9 / multi-tap) provides text entry — we never draw a keyboard.
Softkeys while the input is focused: Options | **Send** | Back.
Options: Clear · Cancel.
Sending an empty message is a no-op, not an error.

### S11 — Message menu
Opened with CENTER on a message row. A bottom-anchored list, typically ~5
rows, each 26 dp, dismiss with RIGHT softkey.
Items: `Reply` · `Edit` (own messages only) · `React` · `Pin message` /
`Unpin message` · `Copy text` · `Save to gallery` (image messages only) ·
`Save to files` (any image/video/file/voice attachment) · `Message info`.
`Save to gallery` writes the image to the public Pictures folder (Pictures/
MatChat); `Save to files` writes any attachment to Downloads (Downloads/
MatChat). On Android 8.1 and older the first save prompts once for storage
permission; newer releases need none. A one-line result confirms ("Saved to
Pictures." / "Saved to Downloads.") or reports failure. Text messages show
neither item.
`React` (Reactions round) opens a second MenuSheet list of 10 choices
(thumbs up/down + 8 common smileys, each row "<emoji> <label>", a trailing
✓ on one already reacted with) — this list doesn't fit one screen, so
MenuSheet itself grew a height-capped, scrollable body for it (invisible to
every shorter menu, whose natural height stays under the cap). Selecting a
choice already reacted with removes that reaction.
`Pin message` / `Unpin message` (Pinned messages round) toggles the row's
label with the message's current state and shows a 📌 prefix on the pinned
message's time line — the same compact "prefix the time text" idiom the
send-state glyph already uses.
Focus starts on `Reply`.
Softkeys: (blank) | Select | Back — the menu *is* the options list, so LEFT is
blank here.

### S12 — Room info
Content: room name, member count, encryption state line ("Encrypted — only
members can read this"), a **Notification sound** row (primary line: the
sound's name — "Default (app sound)", "Silent", or the picked sound; caption
"Notification sound"), member list (a 16 dp avatar beside each name — same
placeholder-until-decoded treatment as S8/S9 — plus a power label), then two
action rows: `Pinned messages` (Pinned messages round, below) and `Add
member`/`Leave room`.
Focus order: name, topic, notification sound, member rows, then the action
rows. CENTER on Notification sound opens Android's sound picker for this room
only. Its "Default" entry means the app sound from S26 (removes the room's
own sound); "Silent" silences this room. On Android 7–9 the first open may ask
for storage access, to add MatChat's bundled sounds (docs/SOUNDS.md); if
refused, a short message says MatChat's own sounds need storage access and the
picker opens anyway.
Softkeys: Options | Select | Back.
Options: Mute this group · Leave group (confirm) · Help.

**Pinned messages** (reached from Room info > Pinned messages): a read-only
list of this room's pinned messages, reusing S12's own field-row look
(sender + time as the caption, the message text as the primary line).
CENTER opens the room — no screen in this app can jump to a specific
message yet, so this is a deliberate scope cut, not a broken link. Empty:
"No pinned messages in this room."

### S13 — Settings
Rows: `Notifications` (opens S26) · `Text size` · `Theme` (opens S24) ·
`Advanced` (opens S25) · `Encryption` (verification status) ·
`About this phone's session` ·
`Policy` · `Help` · `Sign out`.
The `Policy` row reads "Managed by your organization" or "Not managed" and opens
a read-only screen listing the homeserver, the allowed servers (or "All servers
allowed"), and whether direct chat is on. A user who cannot message someone must
be able to find out why without calling anyone.
Softkeys: Options | Select | Back.
Sign out confirms: "Sign out? Messages on this phone will be removed."

### S14 — Help
A static, scrollable-by-focus list of key hints, one per row, in the same
vocabulary as the softkey labels. This is the manual for a user with no
second screen.
Softkeys: (blank) | Select | Back.

### S15 — Notification
*Not a screen we draw — this is the system notification surface; the entries
below are what we put into it.*
Heads-up notification: title = room name; text = the latest message, with the
sender's name in front in a group ("Ann: see you at six" — no name when it
would repeat the title, as in a direct chat). Media shows "Photo", "Video",
"Voice message", "Audio" or "File". When there's nothing to show (an encrypted
message not yet decrypted), the text is the count ("3 new messages"); with
more than one unread, the count is also the sub-text. Long text expands.
Lock screen / outer display: if the phone is set to hide sensitive content,
only the room name and count. The notification LED blinks for as long as the
notification is up, i.e. while the room has unread messages. A
message-bubble small icon. Selecting deep-links to S9 for that room, with the
back stack rooted at S8. Whether it fires at all, and what sound it plays,
are user-configurable — Settings → Notifications (S26), and per room in Room
info (S12), which overrides the app sound for that room.
Persistent low-priority notification while the sync service runs:
"MatChat is running." — its own circular-arrows icon, not user-configurable
(docs/adr/0004). Shown whenever the foreground service hosts sync; it goes
away only when the Background helper hosts sync instead, which needs both
the helper turned on and "Run in background" allowed (S25, S27,
docs/adr/0008). Off by default is not possible: only the user can turn on an
accessibility service.

### S16 — Text size (Small / Normal / Large)
Normal is the default, the middle of three tiers: room name 21 sp, preview
16 sp, row min-height 64 dp, timeline body 20 sp — about four room-list rows
fit the content band at once. Title bar text scales the same way (18 sp,
§1). Cycled by holding `*`, or from Settings → Text size, down to **Small**:
room name 16 sp, preview 13 sp, row min-height 44 dp, timeline body 16 sp,
title bar 14 sp — about six rows fit instead — or up to **Large**: room name
26 sp, preview 20 sp, row min-height 80 dp, timeline body 25 sp, for anyone
who wants it bigger still. The softkey bar's own label size is fixed
regardless of this setting (§1) — it's chrome, not a row. Nothing is removed
and no layout reflows into a different shape in any of the three states;
only the scale changes, which is why row heights are `minHeight` and never
fixed. The screenshot suite renders every screen at Small and Normal (the
existing font-scale cases) — see the Phase 3 (UI improvement plan) PR notes
on why Large isn't exercised there the same way.

### S17 — Landscape (320 × 240)
On landscape SKUs (DuraXE Epic) the bands are the same height, leaving a
190 dp content band — three room-list rows at the default Normal text size,
or roughly two messages plus the input strip. Same screens, same focus
order; no landscape-only layout exists. Every screen must be checked at this
size in the screenshot suite.

### S18 — Invitations
Reached from the room-list band.
Rows (36 dp): room or person name 16 sp bold · "from @wayne:example.org" 11 sp,
ellipsized from the left so the domain always stays visible.
A row whose domain is blocked by policy carries a 11 sp "Not allowed" tag on the
right and still opens — the reason belongs on S19, not in a silent omission.
Focus order: row 1 → row *n*. Initial focus row 1.
Softkeys: (blank) | Open | Back.
Empty: this screen is never reachable with zero invitations; the band is absent.

### S19 — Invitation detail
Content, in order: room or person name (17 sp bold) · "Invited by
Wayne Zimmerman" · the full address `@wayne:example.org` (13 sp, wraps, never
truncated — this is the thing the user is judging) · server line · encryption
line · then the actions.
Focus order: `Accept` → `Decline`.
Softkeys: Options | Select | Back. Options: Decline and ignore this person.
**Blocked by policy**: no Accept button; in its place a 13 sp line —
"Your organization does not allow messages from example.org." — and focus starts
on `Decline`.
Errors: accept can fail (room gone, server unreachable) → inline message plus
Retry, and the invitation stays in the list.

### S20 — New message
Reached from room-list Options → New message.
Three sections, each a header row (11 sp, uppercase, not focusable) followed by
rows: **Contacts** (name 16 sp, address 11 sp) · **Recent** (address 16 sp,
"3 days ago" 11 sp) · a final row **Type an address**.
Focus order: contacts → recents → Type an address. Initial focus: first contact,
or `Type an address` when both lists are empty.
Softkeys: (blank) | Select | Back.
**No search box** — the two lists are short by construction (see `AGENTS.md §0`).
Empty: only `Type an address` shows, with the line "No saved contacts yet."
Hidden entirely when `policy.allowDirectChat` is false; then room-list Options
has no New message entry either.

### S21 — Type an address
Content: label "Address", a field pre-filled `@` … `:` with the cursor in the
first segment and the second segment defaulted to the last server used; hint
line "Example: @wayne:example.org"; `Continue`.
The `@` and `:` are part of the field furniture, not characters the user has to
find on a keypad.
Focus order: field → Continue.
Softkeys: Options | Select | Back. Options: Use my server · Clear.
On Continue: shape check → policy check → profile lookup → a confirmation step
showing "Send to", the resolved name, the full address and the encryption line,
with `Start chat` / `Change`. Softkeys there: (blank) | Select | Back; Back
returns to the field with the address intact.
Errors, all inline, all plain: "That does not look like an address." /
"Could not reach example.org." / "No one at that address." — the last is a
warning, not a block: `Start anyway` remains available, because a server may
simply not publish profiles.

### S22 — Address not allowed
Reached from S21 or from a blocked invitation.
Content: the domain in 17 sp, then "Your organization does not allow messages to
this server." then, if managed, "Managed by your organization" in 11 sp.
Focus: `Back` only. Softkeys: (blank) | (blank) | Back.
No workaround, no "request access", no explanation of how to get around it.

### S23 — Policy
Reached from Settings → Policy. Read-only, no actions.
Content: state line ("Managed by your organization" / "Not managed", 15 sp bold)
with a 11 sp subtitle, then labelled blocks — Home server · Allowed servers
(each on its own line, or "All servers allowed" when unmanaged) · Direct
messages (Allowed / Not allowed).
Focus: none (nothing is actionable). Softkeys: (blank) | (blank) | Back.
This screen exists so a user who has just been blocked can find out why without
phoning anyone. It never offers a way around the policy.

### S24 — Theme
Reached from Settings → Theme. **Appearance** (`Light` · `Dark`) is two
inline focusable rows, in fixed order. CENTER on a row selects it
immediately — no separate confirm — and the change takes effect right away
(the app recreates itself once, keeping the same screen). The selected row
carries a trailing checkmark; selection is never conveyed by color alone.
**Accent color** is a single row below Appearance, labeled with the current
choice ("Accent color: Green ›"). CENTER opens a scrollable picker (16
choices: `Green` · `Amber` · `Blue` · `Plum` · `Teal` · `Cyan` · `Indigo` ·
`Violet` · `Orchid` · `Rose` · `Rust` · `Ochre` · `Olive` · `Forest` ·
`Slate` · `Wine`) — the same shape as the reaction picker — with the
current accent carrying the trailing checkmark; selecting one closes the
picker and recreates the app the same way an Appearance row does.
The accent color governs only the focus-highlight bar (§2) and the system
accent tint. It never changes the "encrypted" green or the link color —
those stay fixed so they keep meaning what they mean regardless of the
user's taste.
Focus order: Light → Dark → Accent color. Initial focus: Light. Softkeys:
(blank) | Select | Back.

### S25 — Advanced
Reached from Settings → Advanced (docs/adr/0007). First row: "Swap
Left/Right keys", **on by default**, with an 11 sp subtitle explaining what
it does ("On: Options on the right, Back on the left. Turn off for Options
on the left."). CENTER toggles it immediately, same as S24's rows — no
separate confirm. The row carries a trailing checkmark when on; selection is
never conveyed by color alone. Takes effect on the very next key press — no
recreate, unlike S24 (there's no chrome to rebuild, just future key events
reading the new preference). Second row: "Run in background (system
setting)". CENTER opens Android's own battery-optimization dialog
(`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, falling back to the
`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` list on builds without the
dialog). An 11 sp status line under it reads "Allowed." or "Not allowed. New
messages may be late while the phone is idle." Third row: "Background helper
(system setting)", with a subtitle explaining its two jobs: stay connected
without the "MatChat is running" notification (docs/adr/0008), and fix the
right softkey on phones whose predictive-text keyboard captures it while
composing. CENTER opens the system Accessibility settings screen
(`ACTION_ACCESSIBILITY_SETTINGS`) so the user can turn on
`MatChatKeyAccessibilityService` there. An 11 sp status line under it reads
"On. No notification needed." / "On, but Run in background is not allowed.
The notification stays." / "Off. MatChat shows a notification." Both are
system-level permissions the app cannot set for itself, so these rows are
action rows with a read-only status line, not toggles; the status lines
refresh whenever the screen is shown again. Focus order: swap row → run in
background → helper. Softkeys: (blank) | Select | Back.

### S26 — Notifications
Reached from Settings → Notifications. Two focusable rows, in fixed order:
"Notifications" (a toggle, CENTER flips it immediately, trailing checkmark
when on — same convention as S24/S25) then "Sound" (CENTER launches the
system ringtone picker; its 11 sp subtitle shows the current choice —
"Default", "Silent", or the picked ringtone's name). Turning notifications
off silences only the incoming-message notification (S15); the persistent
sync notification is unaffected. Under Sound, an 11 sp note: "You can set a
custom room notification by going to the room info." A room's own sound (S12) overrides
this one for that room. On Android 7–9, opening Sound may first ask for
storage access to add MatChat's bundled sounds (docs/SOUNDS.md); if refused,
a second 11 sp note says they need storage access, and the picker opens
anyway. Per-room/per-thread sound is not offered
here — every room shares the one chosen sound (future work).
Focus order: Notifications → Sound. Initial focus: Notifications.
Softkeys: (blank) | Select | Back.

### S27 — Background helper ("Hide the running notification?")
Shown **once per install**, right after the room list first appears
following sign-in (existing installs: once after updating), and only when
the Background helper is not already on (docs/adr/0008). Before it, if
"Run in background" is not yet allowed, the app launches Android's own
battery-optimization dialog (see S25); S27 follows whatever the user chose
there. Content: one short paragraph ("MatChat can stay connected without the
'MatChat is running' notification. Turn on MatChat in Accessibility
settings."), one focusable row "Open Accessibility settings" (CENTER opens
`ACTION_ACCESSIBILITY_SETTINGS`), and the same 11 sp status line as S25's
helper row, refreshed when the user comes back from system settings.
Initial focus: the row. Softkeys: (blank) | Open | Back. RIGHT (Back)
means "not now" and returns to the room list; the S25 rows remain the way
back to this later. On Android 13+ a sideloaded app may need Settings > Apps
> MatChat > ⋮ > "Allow restricted settings" before the switch takes effect;
the helper's own system description says so.

## 4. Content voice

Short, concrete, no jargon. "Encrypted" is fine; "cross-signing", "megolm",
"homeserver" (outside the sign-in screen) are not. Errors say what happened and
what to do next, in that order, in one sentence each.

## 5. States every screen must define

`loading` · `empty` · `error` · `offline` · `focused` — all five are fields of
the screen's `State` data class, all five appear in the screenshot suite.

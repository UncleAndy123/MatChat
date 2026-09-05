# UX Specification — MatChat

Reference viewport: **240 × 320 px, mdpi (1×), 2.6″**. Landscape variant
**320 × 240** must not break. All measurements below are dp = px at mdpi.

## 1. Chrome

Every screen has three fixed bands:

```
┌────────────────────────────┐  ← 240 wide
│ Title bar            18 dp │  title left, sync/battery glyphs right
├────────────────────────────┤
│                            │
│ Content            282 dp  │  scrolls by focus movement only
│                            │
├────────────────────────────┤
│ Options | Select |  Back   │  softkey bar, 20 dp
└────────────────────────────┘
```

- Title bar: 14 sp, single line, ellipsized at the end. Right side shows sync
  state (`⟳` syncing, `!` offline) — nothing else.
- Softkey bar: three cells, left-aligned / centre / right-aligned, 11 sp, on the
  inverse surface so it is never confused with content.
- Content never scrolls by pixel drag. It scrolls because focus moved.

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
| `*` (hold) | Toggle large-text mode. |

**Type floor** (matches `PLAN.md` G5 and `AGENTS.md §5`): body 16 sp ·
interactive labels 14 sp · secondary metadata — timestamps, day separators,
sender names, field captions, softkey labels — 11 sp. Nothing below 11 sp.

Focus highlight: full-width inverse block, 2 dp border, no rounded corners.
It must be identifiable at a glance in direct sunlight — high contrast wins over
subtlety.

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
Rows (44 dp each): room name 16 sp bold · last message 13 sp grey, one line
ellipsized · relative time 11 sp top-right · unread badge (inverse pill, count)
right of the name.
Sorted by most recent activity. Focus = whole row inverse.
**Pending invitations** appear as an 18 dp band directly under the title bar —
"1 invitation" / "3 invitations", with a 2 dp border and the count in an inverse
pill. It is the *first* focus stop and opens S18; when focused it inverts like
any other row (inverse means focus, everywhere, and nothing else). No band when
there are none.
Focus order: invitation band (if any) → row 1 → row *n*. Initial focus:
invitation band, else first unread, else row 1.
Softkeys: Options | Open | Exit.
Options: New message · Mark all as read · Settings · Help · Sign out.
Empty: "Your groups will appear here. Ask your administrator to add you, or
start a message from Options."
Offline: title bar `!` plus a 16 dp banner "No connection — showing saved
messages."

### S9 — Timeline
Content: day separator rows (centred, 11 sp, grey rule); message rows —
sender name 12 sp coloured (shown only when the sender changes), body 16 sp,
time 11 sp right-aligned on the last line; own messages right-aligned with a
send-state glyph (`○` sending, `✓` sent, `!` failed).
If the room is **not encrypted**, a persistent 14 dp band sits directly under the
title bar: "This group is not encrypted." (G4). Encrypted rooms show nothing —
encryption is the norm, not a decoration.
Bottom: a one-line message input strip (18 dp) that is the **last** focus stop.
Focus order: oldest-loaded message → … → newest → input strip. Initial focus:
input strip (people come here to reply), ↑ walks back through history.
Reaching the top item triggers `paginateBack(20)`; a 16 dp "Loading earlier
messages…" row appears while it runs.
Softkeys: Options | Select | Back.
Options: Room info · Mark as read · Mute this group · Help.
Special rows:
- **Unable to decrypt** — italic "This message can't be read on this phone yet."
  + inline action `Fix encryption` (focusable) → S6.
- **Unsent** — red `!`, Options on that row offers Retry / Delete.
Empty: "No messages yet. Say hello."

### S10 — Compose (input focused)
The input strip expands to 3 lines max as text grows; the timeline shrinks.
System IME (T9 / multi-tap) provides text entry — we never draw a keyboard.
Softkeys while the input is focused: Options | **Send** | Back.
Options: Clear · Cancel.
Sending an empty message is a no-op, not an error.

### S11 — Message menu
Opened with CENTER on a message row. A bottom-anchored list, max 5 rows,
each 26 dp, dismiss with RIGHT softkey.
Items: `Reply` · `Copy text` · `Message info` · `Delete` (only when permitted).
Focus starts on `Reply`.
Softkeys: (blank) | Select | Back — the menu *is* the options list, so LEFT is
blank here.

### S12 — Room info
Content: room name, member count, encryption state line ("Encrypted — only
members can read this"), member list (name + power label).
Focus order: member rows.
Softkeys: Options | Select | Back.
Options: Mute this group · Leave group (confirm) · Help.
There is **no** "add member" here in v1 — group membership is administered on
the server. (Direct chats are different: those the user starts themselves, S20.)

### S13 — Settings
Rows: `Notifications` · `Text size` · `Encryption` (verification status) ·
`About this phone's session` · `Policy` · `Help` · `Sign out`.
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
Heads-up collapsed notification: room name + count ("Barn Crew · 3 new").
Selecting deep-links to S9 for that room, with the back stack rooted at S8.
Persistent low-priority notification while the sync service runs:
"MatChat is running."

### S16 — Large-text mode
Toggled by holding `*` (and from Settings → Text size). Every row grows: room
name 21 sp, preview 16 sp, row min-height 64 dp — four rooms visible instead of
six. Timeline body goes to 20 sp. Nothing is removed and no layout reflows into
a different shape; only the scale changes, which is why row heights are
`minHeight` and never fixed. The screenshot suite renders every screen in this
mode as well as normal.

### S17 — Landscape (320 × 240)
On landscape SKUs (DuraXE Epic) the bands are the same height, leaving a 202 dp
content band — four room-list rows, or roughly three messages plus the input
strip. Same screens, same focus order; no landscape-only layout exists. Every
screen must be checked at this size in the screenshot suite.

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

## 4. Content voice

Short, concrete, no jargon. "Encrypted" is fine; "cross-signing", "megolm",
"homeserver" (outside the sign-in screen) are not. Errors say what happened and
what to do next, in that order, in one sentence each.

## 5. States every screen must define

`loading` · `empty` · `error` · `offline` · `focused` — all five are fields of
the screen's `State` data class, all five appear in the screenshot suite.

# UX Specification — MatChat

Reference viewport: **240 × 320 px, mdpi (1×), 2.6″**. Landscape variant
**320 × 240** must not break. All measurements below are dp = px at mdpi.

## 1. Chrome

Every screen has three fixed bands:

```
┌────────────────────────────┐  ← 240 wide
│ Title bar            28 dp │  title centred (bold), sync glyphs right
├────────────────────────────┤
│                            │
│ Content            266 dp  │  scrolls by focus movement only
│                            │
├────────────────────────────┤
│ Options | Select |  Back   │  softkey bar, 26 dp
└────────────────────────────┘
```

- The phone's own white soft-key label bar (the system navigation bar) is
  hidden on every screen — our softkey bar replaces it — with no setting to
  show it (`SystemBars`, per the Flip-DumbPhoneGuide), **except while a text
  field has focus**: the system T9 keyboard shows its word and mode labels
  there, so the bar appears when focus moves into a text field and hides
  again when it leaves.
- Title bar: scales with Settings > Text size (§S16) like everything else —
  18 sp by default (Normal), 14 sp at Small — bold, centred, single line,
  ellipsized at the end (TurboText style), on the accent surface (`?attr/colorFocusAccent` — the user's chosen
  accent, Settings > Theme, not the inverse/near-black surface used
  elsewhere). Right side shows sync state (`⟳` syncing, `!` offline) —
  nothing else.
- Softkey bar: three cells, left-aligned / centre / right-aligned, 16 sp by
  default (its own role, `?attr/textSizeSoftkey` — not the shared 11 sp
  metadata floor). Scales with Settings > Text size (§S16) like the title
  bar: 13 / 16 / 20 sp at Small / Normal / Large, with both bars growing in
  height to match (24/26/32 dp softkey bar, 24/28/34 dp title bar). On the
  same accent surface as the title bar so it is
  never confused with content. Each non-blank cell is also a touch target:
  tapping it fires the same logical key as the matching hardware softkey
  (Options / Select / Back, mirrored with the swap preference), and a tapped
  cell shows a brief flat press-darken (no ripple) so a user on a phone whose
  hardware softkeys don't work gets visible confirmation. A no-op on a real
  non-touch feature phone.
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
| Options softkey | **Options** — context menu for the screen + focused item. Blank on a screen that has no options; never reassigned to anything else. On the **right** by default; on the **left** on a phone with a MENU key (set automatically, below). A MENU key always opens Options. |
| Back softkey | **Back** — the other softkey. On the room list (top level): **Exit**, with confirm. |
| END / BACK | Same as RIGHT softkey. |
| CALL | Ignored (never places a call from inside the app). |
| 0–9 | Text entry when an input is focused; otherwise jump to list item *n*. |
| `#` (hold) | Next unread room. |
| `*` (hold) | Cycle text size (Small → Normal → Large, §S16). |

"Unchangeable" above means no *screen* ever reassigns the softkeys to a
different meaning (AGENTS.md §4) — Options and Back are always exactly one
softkey each. Which physical side is Options is set **globally and
automatically**, like FlipWeather (docs/adr/0007, "Auto-detection"):
**Options on the right, Back on the left by default**; the first MENU key
press moves Options to the left (and opens it); a right-softkey press keeps
it on the right. It stays set until the phone shows a different kind of key
(once a MENU key is seen, it stays on the left). Settings > Advanced >
"Swap Left/Right keys" (S25) overrides it by hand. The softkey bar's labels
always match.

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
Options: Help · Software update. (QR sign-in and an About screen are v1.1
candidates, not built.)
(QR button hidden when `policy.qrLoginEnabled = false`.)

### S3 — Sign in (password)
Content: homeserver row (read-only when pinned, shown as grey text with a lock
glyph), `Username` field, `Password` field, `Show password` toggle (directly
below the password; CENTER flips masking, label reads `Show password` /
`Hide password`), `Sign in` button, `More options…` row.
Focus order: Username → Password → Show password → Sign in → More options.
Softkeys: Options | Select | Back.
Options: Software update · Help — from the Options softkey, or from the
`More options…` row (CENTER). The row is the route while a text box is
focused: the phone's T9 keyboard owns the softkeys then (its typing-mode menu
on the left; it can swallow the right one — Flip-DumbPhoneGuide keys.md).
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
Title: **Chats**. Rows have no divider line between them; the focus highlight
(§2) alone marks the current row.
**Opening the app always lands here** — from the launcher, or after leaving
with Home/End — whatever screen was last open. Exceptions: a notification tap
opens its own room or call; returning from a screen MatChat opened (gallery,
ringtone or file picker, a system settings page) goes back where you were;
closing and opening the flip keeps the screen; an ongoing call keeps its
screen (`shouldGoHome`, LaunchRouting.kt).
Rows (44 dp each): a 32 dp room avatar (a plain filled circle placeholder
until the real image decodes, or when none is set — Avatars round) at the
start · room name 16 sp bold · last message 13 sp grey, one line
ellipsized · relative time 11 sp top-right · unread badge (inverse pill, count)
right of the name.
Sorted by most recent activity. Focus = the standard focus highlight (§2).
**Spaces** (S29) appear as folder rows above the rooms — same row shape, with
"Space · 3 groups" as the preview line, the latest activity and the summed
unread count of every joined group inside it (at any depth). A room that
belongs to a joined space is shown **only inside that space**, never at top
level; a room whose space the user is not in stays at top level. CENTER on a
space row opens S29.
**Pending invitations** appear as an 18 dp band directly under the title bar —
"1 invitation" / "3 invitations", with the count in an inverse pill. It is
the *first* focus stop and opens S18; when focused it uses the
standard focus highlight like any other row (§2, everywhere, and nothing
else). No band when there are none.
Focus order: invitation band (if any) → row 1 → row *n*. Initial focus:
invitation band, else first unread, else row 1.
Softkeys: Options | Open | Exit.
Options: Contacts · Mark all as read · Settings · Help · Sign out.
Empty: "Your groups will appear here. Ask your administrator to add you, or
start a message from Options."
Offline: title bar `!` plus a 16 dp banner "No connection — showing saved
messages."

### S9 — Timeline
**The newest message sits flush with the bottom of the list.** New and sent
messages arrive that way — also when a row grows afterwards (an image loading)
or a band under the list appears — unless the user has scrolled up in the
thread; scrolling back down to the end follows again, and sending always
brings your message into view (`followAfterScroll`).
Title: the room's avatar (the person's, in a DM — same image or colored-initial
fallback as its S8 row) just left of the room name, the pair centred.
Content: day separator rows (centred, 11 sp, grey rule); message rows shown
as bubbles reaching nearly the full row width (matching the reference
device's own SMS app) — own messages trail (right, an accent fill: a pale
accent wash in Light, a solid accent bubble in Dark) and others lead (left, a
neutral fill: warm off-white in Light, `#333` in Dark); a 16 dp sender avatar sits
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
colored stripe (the user's chosen accent, Settings > Theme; lightened in Dark
so it reads on black) on its leading edge for received messages, trailing
edge for own. Bubbles are fully rounded (12 dp) with a small tail off the
bottom corner on the sender's side (bottom-left received, bottom-right own),
and the stripe runs inside the rounded outline, matching TurboText (per the
user's reference clip). In Light a
thin divider outlines each bubble; in Dark there is none. A focused bubble's
border recolors to the accent (lightened in Dark) and thickens, in place of the app's usual flat-fill-plus-bar
focus style (AGENTS.md §4's named exception) — the row itself has no
background.
**Voice messages** render as a bubble too (a waveform + duration), with their
own **play/pause button** on the leading side of the bubble. That button is a
*second* focus stop on the row (the only row in the app with two): CENTER on it
plays the clip in-app (feature phones have no media-player app), and the glyph
flips ▶ → ⏸ while playing; CENTER again pauses, then resumes from the same
spot. The waveform doubles as a **progress bar** — bars left of the playhead
fill with the accent — and the duration becomes an "elapsed / total" readout
(`0:05 / 0:12`) while playing. D-pad LEFT/RIGHT moves between the play button
and the rest of the bubble; CENTER on the bubble itself opens the message menu
(S11) — React/Pin/Message info, no "Open" (playback is the button now). Only
one clip plays at a time; starting another stops the first. (RIGHT still jumps
to Pinned messages, but only once focus is at the right edge of a row — see the
band below.)
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
image fills the screen; `#` zooms in, `*` zooms out, the D-pad / 2468 pan, `0`
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
`Unpin message` · `Copy text` · `Save to gallery` (image messages only) ·
`Save to files` (any image/video/file/voice attachment) · `Message info`.
Image and other-attachment rows prepend an `Open` item (image viewer / external
open). A text message containing a room link (`https://matrix.to/#/…`, a
`matrix:` URI, or a web client's `…/#/room/…` link) prepends `Join group`,
which opens S28 for the first such link in the message. Voice messages do **not**: they play from their own in-bubble play/pause
button (S9), so CENTER on a voice bubble opens this menu with no `Open`.
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

**Message info** (the menu's last item) shows, read-only: `Sent by` (the
sender's display name) · `Address` (@user:server) · `Sent` (date and time) ·
`Event ID` · then `Reactions` (who reacted with what) when there are any. Then
the actions for the sender — merged in from the old separate profile screen,
which no longer exists: `Send message` (opens or starts the direct chat;
hidden when policy turns direct chat off) · `Save contact` (or `Remove from
contacts` when already saved) · `Add to a phone contact…` (as in S20). None of
the actions show on your own messages.
Softkeys: (blank) | Select | Back.

### S12 — Room info
Content: room name, member count, encryption state line ("Encrypted — only
members can read this"), a **Notification sound** row (primary line: the
sound's name — "Default (app sound)", "Silent", or the picked sound; caption
"Notification sound"), member list (a 16 dp avatar beside each name — same
placeholder-until-decoded treatment as S8/S9 — plus a power label), then two
action rows: `Pinned messages` (Pinned messages round, below) and `Add
member`/`Leave room`.
CENTER on a member row (not yourself) opens a menu: `Save contact` (or
`Remove from contacts` when already saved) · `Add to a phone contact…` (see
Contacts below) · `Remove member`. This works the same in a group as in a direct
chat, so anyone you share a room with can be saved.
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
`Advanced` (opens S25) · `Save contacts to phone: Off/On` · `Encryption`
(verification status) ·
`About this phone's session` ·
`Policy` · `Help` · `Sign out`.
The `Policy` row reads "Managed by your organization" or "Not managed" and opens
a read-only screen listing the homeserver, the allowed servers (or "All servers
allowed"), and whether direct chat is on. A user who cannot message someone must
be able to find out why without calling anyone.
Softkeys: Options | Select | Back.
Sign out confirms: "Sign out? Messages on this phone will be removed."
`Save contacts to phone`: CENTER on Off asks Android for the contacts
permission (one system dialog). Allowed → On, and every saved contact is also
written to the phone's Contacts app. Not allowed → stays Off with the line
"Contacts stay in MatChat only". CENTER on On turns it off; nothing already on
the phone is deleted. If the permission is later taken away in system
settings, the row reads Off.

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
When the last invitation is accepted or declined (here or on another device),
the screen returns to the room list on its own.

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

### S20 — Contacts
Reached from room-list Options → Contacts (formerly "New message").
Three sections, each a header row (11 sp, uppercase, not focusable) followed by
rows: **Contacts** (name 16 sp, address 11 sp) · **Recent** (address 16 sp,
"3 days ago" 11 sp) · a final row **Type an address**.
Focus order: contacts → recents → Type an address. Initial focus: first contact,
or `Type an address` when both lists are empty.
CENTER on a contact opens its menu: `Send message` (opens or starts the direct
chat) · `Add to a phone contact…` · `Remove contact` (saved contacts only; an
admin-pushed contact can't be removed). CENTER on a recent: `Send message` ·
`Save contact`. CENTER on `Type an address` messages someone new (S21).
Removing a contact never deletes the phone's copy.
Softkeys: (blank) | Select | Back — the menu is on CENTER.
**No search box** — the two lists are short by construction (see `AGENTS.md §0`).
Empty: only `Type an address` shows, with the line "No saved contacts yet."

**Contacts** (PLAN.md §6.10) are saved on the phone, never uploaded, and kept
across restarts. Everyone you have a direct (1-to-1) chat with is saved
automatically, whoever started it, under the chat's name; a contact you removed
is not added back automatically, and a name already saved is never replaced.
Anyone else you share a room with can be saved from S12. With Settings > "Save
contacts to phone" on, each saved contact is also written to the phone's
Contacts app (name, the Matrix address as an IM entry labelled "Matrix", and a
matrix.to link).
`Add to a phone contact…` adds the Matrix address to a contact already on the
phone: it opens the phone's own contact picker and adds only the Matrix address
and link to the one picked — **the phone contact's name is never changed** —
and MatChat then shows that contact's name for them. If MatChat had already
made a separate phone contact for them, that copy is removed so they aren't
listed twice. Without the contacts permission it opens the phone's own "add to
existing contact" screen instead, with the Matrix address filled in. If the
phone has no Contacts app that can do this, a short message says so.
When `policy.allowDirectChat` is false the screen still lists contacts, but
`Send message` and `Type an address` are hidden.

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
choice ("Accent color: MatChat blue ›"). CENTER opens a scrollable picker (17
choices: `MatChat blue` (the app icon's indigo, the default) · `Green` · `Amber` · `Blue` · `Plum` · `Teal` · `Cyan` · `Indigo` ·
`Violet` · `Orchid` · `Rose` · `Rust` · `Ochre` · `Olive` · `Forest` ·
`Slate` · `Wine`) — the same shape as the reaction picker — with the
current accent carrying the trailing checkmark; selecting one closes the
picker and recreates the app the same way an Appearance row does.
The accent color governs only the focus-highlight bar (§2) and the system
accent tint. It never changes the "encrypted" green or the link color —
those stay fixed so they keep meaning what they mean regardless of the
user's taste.
**Dark** follows TurboText's Classic Dark: a pure black background, `#222`
dialogs and menus, `#333` dividers and received bubbles, and `#AAA` secondary
text. **Light** is the warm paper palette. Light is the default.
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
Last, a third toggle row, "Hide message on cover" (same checkmark convention,
off by default), with an 11 sp subtitle explaining both states. On Kyocera
flip phones a new message also appears on the small outside (cover) screen,
beside the MatChat icon, as the time then "Room: message", scrolling on one
line (docs/COVER-DISPLAY.md); with this on it reads
only the time and "MatChat message", so who wrote and what they said aren't visible on a
closed phone.
Focus order: Notifications → Sound → Hide message on cover. Initial focus:
Notifications.
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

### S28 — Join group
Reached from a message's `Join group` item (S11). The link names one known
room; this screen never searches for rooms (`AGENTS.md §0`).
Content, in order: the group's name (17 sp bold; the link's address until the
preview arrives) · the address from the link (13 sp, wraps, never truncated;
hidden when it is the name) · "12 members" (11 sp) · the topic (13 sp, 3 lines
max) · one plain-language line saying where the user stands · then one action
button.

| Situation | Line | Button |
|---|---|---|
| Anyone may join, or the rule is unknown | — | `Join` |
| Members let people in (knock) | "A member must let you in. Ask to join and they will see your request." | `Ask to join` |
| Join refused with 403 ("not invited") | "You need an invitation to join this group. You can ask its members to let you in." | `Ask to join` |
| Invite-only | "Only people a member invites can join this group." | — |
| Already invited | "You have an invitation to this group." | `Join` |
| Request already sent | "Request sent. The group will appear in your list once a member lets you in." | — |
| Already a member | "You are already in this group." | `Open` |
| Banned | "You are not allowed to join this group." | — |
| No preview available | "Could not show details for this group. You can still try to join." | `Join` |
| Not a room link | "This link does not lead to a group." | — |

`Join` (or `Open`) replaces this screen with the room (S9), or with S29 when
the link is to a space, so Back from the
room returns to the conversation the link was in. `Ask to join` opens a text
prompt (TextPromptSheet) titled "Message to the group (optional)"; the members
see the message with the request. Confirming a blank prompt sends the request
without one; Back cancels and sends nothing. No room can require a message —
it is optional in the protocol.
**Blocked by policy** (the room's server — its alias or id server, else the
first `via` server — is not in `allowedDomains`): no button; in its place
"Your organization does not allow messages from example.org." Observed live.
Focus: the button, when there is one; otherwise none.
Softkeys: (blank) | Select | Back.
Loading: "Loading…"; while joining or asking: "Please wait…".
Errors, inline, the button stays as the retry: "Could not join. Check your
connection and try again." / "Could not send your request. Try again, or ask a
member to invite you."

### S29 — Space
Reached from a space row on S8, or from another space's row (nested spaces).
Title: the space's name. Rows: its joined subspaces (folder rows, as on S8),
then its joined groups — the same rows and order as S8, scoped to this space.
**Only rooms and spaces the user has joined are listed.** A space's other
rooms are never shown or browsable: a space is a folder, not a directory
(`AGENTS.md §0`). Membership arrives, as everywhere, by invitation or a link
(S28 — a link to a space opens it here once joined).
Focus order: row 1 → row *n*. Initial focus row 1.
Softkeys: Options | Open | Back. Options: Leave space → a confirmation row
"Leave space (your groups stay)". Leaving a space keeps its groups; they move
to the top level of S8. A failed leave shows "Could not leave the space. Try
again." above the list.
Empty: "You are not in any group in this space yet. A member can invite you."
If the space is left (here or on another device) the screen returns on its own.

## 4. Content voice

Short, concrete, no jargon. "Encrypted" is fine; "cross-signing", "megolm",
"homeserver" (outside the sign-in screen) are not. Errors say what happened and
what to do next, in that order, in one sentence each.

## 5. States every screen must define

`loading` · `empty` · `error` · `offline` · `focused` — all five are fields of
the screen's `State` data class, all five appear in the screenshot suite.

### S30 — Save contacts to this phone?
Shown **once per install**, after the post-sign-in setup (after S27 when S27
shows; existing installs: once after updating), and only when the contacts
permission isn't already granted (if it is, the setting is simply turned on).
Content: one short paragraph ("MatChat saves the people you chat with. It can
also save them in this phone's Contacts app, with their Matrix address. You can
change this later in Settings."), one focusable row `Save to phone contacts`,
and an 11 sp status line. CENTER on the row asks Android for the contacts
permission. Allowed → the S13 setting turns on, saved contacts are copied, and
the screen closes. Not allowed → the status line reads "Contacts stay in
MatChat only" and the screen stays, so the user can read it and press Back.
Initial focus: the row. Softkeys: (blank) | Select | Back. Back means "not
now"; S13's row remains the way to turn it on later.

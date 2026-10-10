# Cover display (Kyocera front screen) notifications

Status: **working unrooted** via the sub-LCD alert card (see below). The
persistent idle-screen badge (InfoSign path) is a dead end for a third-party app
and remains a signed-build item. Branch `notifications`.

## Implemented: the sub-LCD alert card (`SubLcdManager`)

`CoverScreenNotifier` posts a transient card to the cover screen through the OEM
framework class `jp.kyocera.sublcd.SubLcdManager` + `SubLcdNotificationExtender`,
reached by reflection on Android's **public** `Notification.Builder.extend()`
API (the wearable-extender pattern). This is the surface SystemUI's own PowerUI
uses for battery cards — **a different path from InfoSign**, so it bypasses the
notification-listener mirror and the `KeyguardStatusView` package allow-list
that blocked us (below). Any app can post to it; it no-ops (reflection
try/catch) on non-Kyocera hardware.

`MessageNotifier` drives it on its existing lifecycle: a card is posted when a
message arrives and cancelled when the room is read. The card shows
"Room: message" (e.g. "Ann: see you at six", or "Barn Crew: Ann: see you at
six" in a group — the count when the text can't be shown yet). **Settings ▸
Notifications ▸ "Hide message on cover"** (UX-SPEC S26, off by default) swaps
that for a generic "MatChat message", for anyone who doesn't want the sender
and text readable on the outside of a closed phone.

**Known limitation (verified on-device by TurboText, which this mirrors —
github.com/Ben-Showalter/TurboText `OuterScreenNotifier`):** every post needs a
duration and clears when it expires; there is no setting that holds the card on
the idle cover indefinitely without keeping the screen on. So this is a timed
"a message arrived" card (currently `CARD_DURATION_MS` = 15 s), not a permanent
unread badge — and it keeps the cover screen on for that whole span, so the
duration is a visibility/battery trade-off.

- **A permanent idle-screen badge** still needs the signed SystemUI build (the
  InfoSign/`KeyguardStatusView` path below).
- **A wake pulse** (flash the icon when a hardware key wakes the closed phone,
  as TurboText does from its accessibility service) is a possible add-on, but
  MatChat's `MatChatKeyAccessibilityService` is scoped by AGENTS.md §4 to the
  right softkey + sync only, so adding it needs explicit direction and an
  AGENTS.md update — not done here.

### Scrolling and re-showing (probe results → implemented)

The debug-only `CoverProbeReceiver` dumped the OEM API and watched wake signals
on the DuraXV. Findings:

- **No native scrolling.** `SubLcdNotificationExtender` offers `setTitle`,
  `setText`, `setText2`, `setBlinkText/Title`, `setCancelBySideKey`,
  `setFullScreen`, `setDisplayPerson`, `setInfoSign`, `setRemoteViews` — no
  marquee. A long line posted once does not scroll. Templates are
  `k_sublcd_template_1`…`_5` (`0x010900ab`…`0x010900af`; we use `_5`).
- **App-side ticker works.** Reposting the same card id every 400 ms with a
  sliding window of the text scrolls cleanly on the cover.
- **The cover is not an Android `Display`.** Only display 0 exists. What an app
  *can* hear is the main display's `SCREEN_OFF` when the flip closes and
  `SCREEN_ON` when it opens.
- **Other OEM API worth knowing:** `SubLcdManager` has `notify` overloads taking
  a `long` cancel time (`DEFAULT_CANCEL_TIME = -1`), `wakeUpSecDisplay(boolean)`,
  `isKeyguard()`, `registerCallback(ISubLcdCallback)`, and
  `notifyIconToAnnunciatorTray` / `cancelAnnunciatorIconFromTray`.
- **Do not call `notifyIconToAnnunciatorTray`.** Testing it as a persistent
  "until cleared" cover icon **crashed SystemUI** on the DuraXV. The probe no
  longer has that test, and nothing in the app calls it.

Implemented in `CoverScreenNotifier`:

- Text longer than 14 characters scrolls (ticker) for the card's 15 s.
- The card is re-shown each time the flip closes (`SCREEN_OFF`) until the room
  is read (`MessageNotifier.cancel*` → `CoverScreenNotifier.cancel`), and stopped
  when the flip opens (`SCREEN_ON`). Only the most recent unread room's card is
  shown. Side effect: a main-screen timeout with the flip *open* also sends
  `SCREEN_OFF`, so the card re-shows (unseen) on the lid then — 15 s of cover
  screen, accepted for now.

Still open:

- **Waking the cover with an outside button while closed** produces no signal
  an app can hear. Handled (per explicit user direction, AGENTS.md §4) by
  `MatChatKeyAccessibilityService`, which already sees every key: each fresh
  key-down calls `CoverScreenNotifier.onKeyPress`, which re-shows the latest
  unread card if the main screen is off and the last card has expired
  (`shouldReshowOnKeyPress`). Never consumes the key, never records which key.
  Caveats: only works with Settings ▸ Advanced ▸ "Background helper" on, and
  not yet confirmed on the DuraXV that the service receives outside-button
  presses with the lid shut — `MatChatCover: key press with screen off` in
  logcat confirms it does. (A keylog check showed those buttons are *not*
  among the keys the system drops while closed, unlike the inner keypad's
  `drop key event:19 lid:0`.)
- **A persistent "until cleared" icon** on the idle cover has no safe unrooted
  route: the tray-icon API crashed SystemUI, and the InfoSign badge path (below)
  only draws allow-listed packages.

---

The rest of this file is the investigation of the **other** cover path
(InfoSign), kept as the record of why it's a dead end for a third-party app.

## Background: InfoSign path (the dead end)

Confirmed by decompiling `jp.kyocera.kcinfosignprovider` ("InfoSign").

## The mechanism (confirmed, not a guess)

The cover (outer) display is fed by InfoSign (logcat tag `kc_infosign`). It has
**two** input paths, both converging on the same output: it writes a row to its
`BadgeProvider` DB (`package_name, class_name, badge_count`) and fires broadcast
`jp.kyocera.kcinfosignprovider.action.INFOSIGN_DATA` carrying `String[][]{pkg,
cls, count}`. A separate cover-screen renderer (in SystemUI / a `sublcd`
component — **not** in this APK) consumes that and paints the front screen as
"N from \<app\>".

### Path 1 — NotificationListener (the clean, unrooted route we use)

`onNotificationPosted` does **not** look at the package. It reads
`notification.extras.getString("sublcd_notification")`. If that equals
`"messaging"` or `"email"`, it mirrors — hardcoding the downstream package to
vzmsgs / `jp.kyocera.email`, count 1 on post and 0 on removal. The earlier
"package whitelist" theory was a red herring: MatChat was dropped only because
it didn't set this extra (`extra=null` in the logs).

### Path 2 — the `BADGE_COUNT_UPDATE` receiver (arbitrary counts)

`KCInfosignBroadcastReceiver` takes an arbitrary `badge_count`, but **returns
early and does nothing if `badge_count_class_name` is null** — which is why the
earlier manual `am broadcast` tests lit nothing (they omitted the class name).
The working form is:

```
adb shell am broadcast -n jp.kyocera.kcinfosignprovider/.KCInfosignBroadcastReceiver \
  -a android.intent.action.BADGE_COUNT_UPDATE \
  --ei badge_count 7 \
  --es badge_count_package_name com.verizon.messaging.vzmsgs \
  --es badge_count_class_name x
```

That writes the row (`success update` in logcat) and broadcasts `count=7`.

## What MatChat does

`MessageNotifier.buildNotification` adds the Path-1 extra to the incoming-message
notification it already posts:

```kotlin
.addExtras(Bundle().apply {
    putString(MessageNotifier.EXTRA_SUBLCD, MessageNotifier.SUBLCD_MESSAGING) // "sublcd_notification" = "messaging"
})
```

Because it rides the existing notification lifecycle — posted on a new message,
cancelled when the room is read — the cover indicator tracks "message waiting"
for free, with no new service, broadcast, or permission. The extra is inert on
any non-Kyocera device (an unknown extra is ignored). The notification is also
already `CATEGORY_MESSAGE`, auto-cancel, not ongoing, high priority.

The **sync** foreground notification is deliberately `ongoing` with no messaging
category and no `sublcd` extra, so it never reaches the cover screen.

## Testing

`TestNotificationReceiver` (debug builds only — `app/src/debug/`) posts the real
production notification on demand so the cover can be tested without a live
message:

```
adb shell "logcat -c"
adb shell "logcat -v time -s kc_infosign" &
adb shell am broadcast -n org.matchat.client/.notify.TestNotificationReceiver
# clear it:  add  --ez clear true
```

Pass = `onNotificationPosted(... extra=messaging ...)` then a `sendBroadcast ...
count=1`, and the cover lights with the flip closed.

## Debugging ("nothing shows on the cover")

MatChat logs its whole side of the pipeline under one tag, `MatChatCover`
(diagnostics only, no PII), to watch next to InfoSign's own tag `kc_infosign`.

**PowerShell note:** bash-style backgrounding with `&` is a syntax error in
PowerShell, so `adb logcat ... &` never starts — that's why an earlier test
captured nothing. Use one of these instead:

- **Dump-after (simplest, one window):** clear, trigger, then dump the buffer.
  `-d` prints everything buffered since the clear and exits, so no backgrounding
  is needed:
  ```
  adb logcat -c
  adb shell am broadcast -n org.matchat.client/.notify.TestNotificationReceiver
  adb logcat -d -v time -s MatChatCover kc_infosign
  ```
- **Live, second window:** run `adb logcat -v time -s MatChatCover kc_infosign`
  in its own terminal and leave it; send the message in the first.
- **Live, background job (PowerShell):**
  `Start-Job { adb logcat -v time -s MatChatCover kc_infosign }` then
  `Receive-Job -Keep (Get-Job)[-1]` to read it.

**Do not use Android Studio's Logcat for the `kc_infosign` half.** It scopes to
the debugged app's process, and InfoSign runs in its own process
(`jp.kyocera.kcinfosignprovider`) — so its lines are filtered out and it looks
like InfoSign saw nothing when really it just wasn't captured. Use the `adb`
CLI, unfiltered by package. To be sure of catching it whatever the exact tag,
dump everything and grep:

```
adb logcat -c
adb shell am broadcast -n org.matchat.client/.notify.TestNotificationReceiver
adb logcat -d > cover.txt
# PowerShell:
Select-String -Path cover.txt -Pattern "infosign","sublcd","INFOSIGN_DATA"
```

Then send a message from another account (or fire `TestNotificationReceiver`).
Read it as a decision tree:

1. **No `MatChatCover onRooms:` line at all** when a message arrives → the sync
   observer isn't seeing the unread climb (app not syncing, or the message
   landed in the ADR-0004 window where no observer runs). Not a cover problem.
2. **`onRooms: ... seeded baseline ...` and nothing else** → the message arrived
   on the very first emission, which only seeds; it never alerts. Send a second
   message.
3. **`onRooms: id=… climbed but notifications are OFF`** → Settings ▸
   Notifications is off. Turn it on.
4. **`onRooms: id=… prev=… now=…`** but **no `show id=…`** → the post was
   decided but `show` never ran (unread didn't actually climb, or an exception
   upstream).
5. **`show id=… sublcd=messaging category=msg`** then **`notify ok`** → MatChat
   did everything right. Now look at `kc_infosign`:
   - `onNotificationPosted … extra=messaging` → InfoSign saw it; if the cover
     still doesn't light the downstream renderer is the issue (branding section).
   - `… extra=null` → the extra didn't survive to `notification.extras`
     (shouldn't happen — `show` logs it being present; report the mismatch).
   - no `kc_infosign` line → InfoSign isn't running / its listener isn't enabled
     for this build.
6. **`show id=… sublcd=null`** → the extra isn't on the built notification; the
   `addExtras` call regressed.

Post with the **flip closed** — some cover renderers only react to a
notification that posts while the lid is shut.

## The real wall — confirmed on-device

A full trace (flip-closed test post) shows the pipeline works right up to the
renderer, then stops:

```
kc_infosign: onNotificationPosted extra=messaging                         # matched our extra
BadgeProvider: update
kc_infosign: sendBroadcast pkg=com.verizon.messaging.vzmsgs cls= count=1   # RELABELED to vzmsgs
KeyguardStatusView: infosignData[0].pkgName = com.verizon.messaging.vzmsgs # SystemUI renderer got it
KeyguardStatusView: infosignData[0].count = 1
```

So:

- **Path 1 relabels.** On an `extra=messaging` match InfoSign throws away the
  real package and emits `com.verizon.messaging.vzmsgs`. So the cover can only
  ever show a Verizon-Messages identity via this path — and if Verizon Messages
  isn't installed, `KeyguardStatusView` has no icon/label to resolve and draws
  nothing. That is the observed blank cover.
- **The renderer is `KeyguardStatusView` in SystemUI** (not a `sublcd` app). It
  consumes `jp.kyocera.kcinfosignprovider.action.INFOSIGN_DATA`
  (`String[][]{pkg, cls, count}`).
- **InfoSign's notification-listener access is system-bound**, not via
  `enabled_notification_listeners` (that setting did not list InfoSign, yet
  `onNotificationPosted` still fired). So listener access is a non-issue.

### Does Path 2 carry our own package through? (the deciding experiment)

Path 1 is a dead end for us (always relabels to vzmsgs). Path 2
(`BADGE_COUNT_UPDATE` → BadgeProvider → `INFOSIGN_DATA`) passes the package
through *verbatim*, so it should reach `KeyguardStatusView` as
`pkg=org.matchat.client`. Whether the cover then paints depends on whether
`KeyguardStatusView` resolves the icon dynamically (via PackageManager — we'd
win, our icon shows) or from a fixed internal map of known packages (dialer,
vzmsgs, email, vvm — we'd lose, needs a signed SystemUI). Test it directly,
flip closed:

```
adb logcat -c
adb shell am broadcast -n jp.kyocera.kcinfosignprovider/.KCInfosignBroadcastReceiver \
  -a android.intent.action.BADGE_COUNT_UPDATE \
  --ei badge_count 3 \
  --es badge_count_package_name org.matchat.client \
  --es badge_count_class_name org.matchat.client.MainActivity
adb logcat -d > badge.txt
# PowerShell:  Select-String -Path badge.txt -Pattern "BadgeProvider","INFOSIGN_DATA","KeyguardStatusView"
```

- Cover shows MatChat's icon + "3" → **Path 2 is the unrooted win**; wire MatChat
  to fire this broadcast (a `CoverBadge` helper) on unread-count changes.
- Cover still blank (and `KeyguardStatusView` log shows `pkg=org.matchat.client`
  arriving but nothing drawn) → `KeyguardStatusView` has a fixed package map;
  real branding needs a signed SystemUI build. Pull `SystemUI.apk` +
  `framework-res.apk` and trace `KeyguardStatusView`'s handling of `infosignData`
  to find where to add `org.matchat.client`.

### Result (confirmed on-device)

Path 2 delivered our package to the renderer — `KeyguardStatusView:
infosignData[0].pkgName = org.matchat.client, count = 3` — but the cover drew
**nothing** (only the notification LED blinked once). So `KeyguardStatusView`
**receives any package but only draws a fixed allow-list** of known ones
(dialer, vzmsgs, email, vvm). Verizon Messages *is* installed on this unit, so
the blank cover is not about missing icons — it is the draw-time allow-list.

Conclusion: **a branded MatChat/KyCall cover indicator is not achievable from an
unprivileged app.** It requires a signed SystemUI build that adds
`org.matchat.client` to `KeyguardStatusView`'s allow-list (the signed-build path
already planned). Pull `SystemUI.apk` + `framework-res.apk` to find the exact
map.

### Unrooted fallback still open: masquerade as a known package

Path 2 passes the package verbatim, so firing it with a package the renderer
*does* draw (e.g. `com.verizon.messaging.vzmsgs`) plus our own count would give a
**functional but unbranded** "N waiting" indicator — Verizon Messages' identity,
MatChat's count. Deciding test (flip closed, watch the cover, not the log):

```
adb shell am broadcast -n jp.kyocera.kcinfosignprovider/.KCInfosignBroadcastReceiver \
  -a android.intent.action.BADGE_COUNT_UPDATE \
  --ei badge_count 3 \
  --es badge_count_package_name com.verizon.messaging.vzmsgs \
  --es badge_count_class_name com.verizon.messaging.vzmsgs.ui.LaunchConversationActivity
```

- Verizon badge + "3" appears → MatChat *can* drive a functional cover indicator
  unrooted by masquerading (a product call: unbranded is the cost). Would be a
  Kyocera-gated `CoverBadge` firing this on unread changes, count=0 to clear.
- Still nothing → this cover's app-badge rendering isn't usable unrooted at all;
  the whole feature waits on the signed SystemUI build.

# Cover display (Kyocera front screen) notifications

Status: **working unrooted** for the message-waiting indicator (branch
`notifications`). Custom branding on the cover is the only part still needing a
signed/root build. This file records how the Kyocera cover display is driven,
confirmed by decompiling `jp.kyocera.kcinfosignprovider` ("InfoSign").

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

## The one real limit — branding

Both paths carry only `package + count` downstream. The cover renderer decides
the icon/label from the package and only knows a handful (dialer, vzmsgs, email,
vvm). So via Path 1 the cover shows a *generic messaging identity* (Verizon
Messages' icon), not MatChat/KyCall — functionally "you have a message," which
is the goal, but not our branding.

To show MatChat's own icon/name on the cover, MatChat's package must be added to
that downstream renderer's map. The renderer is a system component (SystemUI /
`sublcd`), so that is the signed-build / root step — the same signed-build path
already planned, now an isolated, well-defined change rather than a mystery. The
renderer itself has not yet been located; pull SystemUI and any `*sublcd*` APK
to trace who consumes `INFOSIGN_DATA`.

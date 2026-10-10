# Cover display (Kyocera front screen) notifications

Status: **investigation / testing** (branch `notifications`). Not yet a shipped
feature. This file records how the Kyocera cover display is driven and how to
test whether MatChat can appear on it, so the next person doesn't start from
scratch.

## The mechanism

The cover (outer) display on these Kyocera flips is driven by
`jp.kyocera.kcinfosignprovider` ("InfoSign", logcat tag `kc_infosign`). Its
`NotificationListener.onNotificationPosted` sees every posted notification,
applies a filter, and for ones that pass calls
`BroadcastIntentSender.sendBroadcastIntent` — a broadcast carrying `pkg=` and
`count=`. Something downstream renders that as **"N from \<app\>"**. So the cover
screen shows a *count + source app*, never the message body.

## What passes the filter (from on-device `kc_infosign` logs)

| Notification | Result |
|---|---|
| `com.android.systemui` PWRMGT — `pri=-2` (MIN), `flags=0x2` (ongoing), no category | posted, **no** `sendBroadcast` → filtered out |
| `com.verizon.messaging.vzmsgs` — `category=msg`, `flags=0x311` | `sendBroadcast pkg=com.verizon.messaging.vzmsgs count=1` → **mirrored** |

So the discriminator is almost certainly **`category` set to a messaging/call
category + not ongoing**, possibly plus a package whitelist. Note vzmsgs carried
`FLAG_LOCAL_ONLY` (0x100) and was mirrored anyway — InfoSign ignores
`LOCAL_ONLY`, so that flag does not block us.

## What MatChat already does

The incoming-message notification (`MessageNotifier.buildNotification`) already
sets everything the filter looks for:

- `setCategory(NotificationCompat.CATEGORY_MESSAGE)`
- `setAutoCancel(true)` and **not** ongoing (no `setOngoing(true)`)
- `setPriority(PRIORITY_HIGH)` / channel `IMPORTANCE_HIGH`

The **sync** foreground-service notification is deliberately `ongoing` with no
messaging category, so it is correctly filtered *out* of the cover screen.

So no production change is needed for the notification to be *eligible*. The
open question is purely whether InfoSign requires a **package whitelist** on top
of the category rule.

## How to test (fastest answer, no decompile)

`TestNotificationReceiver` (debug builds only — `app/src/debug/`) posts the real
production notification on demand:

```
adb shell "logcat -c"
adb shell "logcat -v time -s kc_infosign" &
adb shell am broadcast -n org.matchat.client/.notify.TestNotificationReceiver
```

- **Pass:** `onNotificationPosted(... pkg=org.matchat.client ... category=msg ...)`
  then `sendBroadcast pkg=org.matchat.client count=1`. Category alone gets us on
  the front screen — nothing more to do in the app.
- **Posts but no `sendBroadcast`:** there is a package whitelist; see below.

Clear it: add `--ez clear true` to the broadcast.

## If there is a package whitelist

Two routes, in order of cleanliness, both needing the InfoSign APK decompiled to
confirm the exact rule and the broadcast contract:

```
adb shell pm path jp.kyocera.kcinfosignprovider
adb pull <printed path> kcinfosign.apk
```

Trace in jadx:
1. `NotificationListener.onNotificationPosted` — the exact category/whitelist rule.
2. `BroadcastIntentSender.sendBroadcastIntent` — the broadcast action + extras and
   its receiver. **If that receiver is not signature-protected, MatChat may be
   able to send that broadcast itself** and drive the front screen directly,
   bypassing the notification filter — the cleanest possible API. If it *is*
   protected, or there is a whitelist we must join, that needs root on the
   device.

(If jadx shows no code because the APK is deodexed, also pull the matching
`oat`/`vdex` from the same directory.)

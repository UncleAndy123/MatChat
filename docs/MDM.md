# Managed configuration — MatChat

How an administrator controls a MatChat phone, what each key does, and what
each layer actually enforces. Decision and reasoning: `docs/adr/0005`.

---

## 1. What holds, and what does not

| Layer | Where | Stops an ordinary user | Stops someone determined |
|---|---|---|---|
| No discovery (no directory, no search) | Compiled into the client; Detekt rule | Yes | Yes — the API is not in the build |
| `allowedDomains` | Managed configuration on the device | Yes | **No** — modified APK, another client, or an unenrolled phone |
| `federation_domain_whitelist` | Synapse | Yes | Yes |
| Invite-only rooms, closed registration | Synapse | Yes | Yes |

Read that table before promising anyone that the phone "can't" reach a server.
The client-side allowlist is there so the user gets a clear message instead of a
confusing failure — enforcement that has to hold belongs on the server, and the
two lists should be kept identical.

**No managed configuration ⇒ every domain allowed.** That is deliberate
(ADR 0005): an unmanaged phone is an open phone with no discovery, not a brick.

## 2. Getting a DPC onto these devices

Managed configuration only exists if something sets it — a device-owner or
profile-owner app (a DPC / EMM agent). Kyocera-class AOSP flips generally ship
**without Google Play Services**, so the ordinary Android Enterprise enrollment
path (Managed Google Play) is not available. Two paths that do work:

1. **An EMM agent that supports non-GMS AOSP devices** (SOTI, Ivanti, 42Gears
   and similar). This is the sustainable option for a fleet; pick one before M5,
   because it decides whether the allowlist ever arrives.
2. **`adb shell dpm set-device-owner <package>/<receiver>`** on a freshly
   factory-reset device with no accounts added. Fine for pilot devices and lab
   work; it does not scale, and it is lost on factory reset.

If neither happens, everything in this document is inert and Settings → Policy
reads "Not managed". That is a visible state, not a hidden failure.

## 3. Restriction keys

Declared in `app/src/main/res/xml/app_restrictions.xml` and referenced from the
manifest:

```xml
<application ...>
  <meta-data android:name="android.content.APP_RESTRICTIONS"
             android:resource="@xml/app_restrictions"/>
</application>
```

| Key | Type | Default when absent | Meaning |
|---|---|---|---|
| `pinnedHomeserver` | string | none (user may type one) | Homeserver URL; when set, the sign-in screen shows it read-only |
| `allowedDomains` | string | **absent ⇒ all domains allowed** | Comma-separated homeserver domains the user may message or accept invites from. Exact match, no wildcards in v1 |
| `allowDirectChat` | bool | `true` | When false, "New message" disappears entirely — invitations still work |
| `invitePolicy` | choice: `ask` \| `autoAllowed` | `ask` | `ask` = every invitation waits on S18/S19. `autoAllowed` = invitations from `allowedDomains` join silently; everything else still asks |
| `contacts` | string (JSON) | empty | `[{"name":"Wayne Zimmerman","address":"@wayne:example.org"}]` — pushed contacts, shown first in New message |
| `mediaSend` | bool | `true` | Allow sending images |

`contacts` is a JSON string rather than a `bundle_array` on purpose:
`bundle_array` requires API 26 and MatChat supports API 24.

### Example: `app_restrictions.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<restrictions xmlns:android="http://schemas.android.com/apk/res/android">
    <restriction
        android:key="pinnedHomeserver"
        android:title="@string/policy_homeserver_title"
        android:description="@string/policy_homeserver_desc"
        android:restrictionType="string"
        android:defaultValue=""/>
    <restriction
        android:key="allowedDomains"
        android:title="@string/policy_domains_title"
        android:description="@string/policy_domains_desc"
        android:restrictionType="string"
        android:defaultValue=""/>
    <restriction
        android:key="allowDirectChat"
        android:title="@string/policy_dm_title"
        android:restrictionType="bool"
        android:defaultValue="true"/>
    <restriction
        android:key="invitePolicy"
        android:title="@string/policy_invites_title"
        android:restrictionType="choice"
        android:entries="@array/policy_invite_entries"
        android:entryValues="@array/policy_invite_values"
        android:defaultValue="ask"/>
    <restriction
        android:key="contacts"
        android:title="@string/policy_contacts_title"
        android:restrictionType="string"
        android:defaultValue=""/>
    <restriction
        android:key="mediaSend"
        android:title="@string/policy_media_title"
        android:restrictionType="bool"
        android:defaultValue="true"/>
</restrictions>
```

### Example: what the EMM pushes

```json
{
  "pinnedHomeserver": "https://chats.carpathianserver.org",
  "allowedDomains": "carpathianserver.org,example.org",
  "allowDirectChat": true,
  "invitePolicy": "ask",
  "contacts": "[{\"name\":\"Wayne Zimmerman\",\"address\":\"@wayne:example.org\"},{\"name\":\"Shop Floor\",\"address\":\"@merv:carpathianserver.org\"}]",
  "mediaSend": true
}
```

## 4. Reading it in the app

Only `:core:policy` touches this. It reads the bundle at startup, registers a
receiver for `ACTION_APPLICATION_RESTRICTIONS_CHANGED`, and re-emits — so an
admin can tighten or loosen a phone from the console and see the effect without
a restart or a reinstall.

`isManaged` is false when the bundle is empty. `allowedDomains` parses to `null`
in that case, and `Policy.allows(address)` returns true for everything — the
fail-open path.

## 5. The server half

Keep these in step with `allowedDomains` whenever the allowlist is meant to be
real rather than advisory:

```yaml
# homeserver.yaml
federation_domain_whitelist:
  - carpathianserver.org
  - example.org

enable_registration: false

room_list_publication_rules:
  - user_id: "*"
    alias: "*"
    room_id: "*"
    action: deny
```

`federation_domain_whitelist` is a homeserver-wide setting: it applies to every
account on the server, not per-device. Per-device differences can only come from
the managed configuration, with the limits in §1.

## 6. Testing policy changes

- `adb shell dumpsys device_policy` to confirm a DPC is present.
- Change a value in the EMM console and confirm the phone reacts without a
  restart — that is the acceptance test for M5, not "the value is read at
  startup".
- Test the unmanaged case explicitly: factory-fresh device, no DPC, expect
  Settings → Policy to read "Not managed" and every domain to be reachable.
- Test a blocked invitation: it must appear on S18 and explain itself on S19,
  never vanish.

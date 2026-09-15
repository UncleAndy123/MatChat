# Voice calls — MatChat

How MatChat makes and receives voice calls that interoperate with Element.
Decision record: `docs/adr/0006`.

---

## 1. What Element actually speaks now

This is the fact that decides everything else: **legacy Matrix 1:1 VoIP
(`m.call.invite` / `answer` / `candidates` / `hangup`) is a dead end.** Only
Element classic still speaks it; Element X and the current generation of clients
do not. Building it would buy MatChat-to-MatChat calls and nothing more.

Element's calling is **MatrixRTC** (MSC4143): call membership and signalling
ride Matrix state events, and media goes to a pluggable backend. In practice
there is one backend — **LiveKit**, an SFU — reached with a JWT issued by
`lk-jwt-service` (MSC4195). Element Call is the *web UI* on top of that; it is
not the protocol, and we do not need it to interoperate.

So: **speak MatrixRTC, connect to the same LiveKit room, and Element users hear
us.** They see a normal call; they do not care that our client is native and
has no video.

## 2. The route

Native MatrixRTC signalling + native LiveKit audio. No WebView anywhere.

```
   MatChat (Kyocera flip)                       Element X (phone/desktop)
   ─────────────────────                        ────────────────────────
   :feature:call      UI, D-pad, CALL/END keys
        │
   :core:rtc          m.call.member state events ──► Matrix room ◄── same events
        │             (join / leave / expiry)              │
        │                                                  │
        │  POST /get_token  ────────────────────►  lk-jwt-service
        │       (Matrix identity + room)                    │ validates membership
        │  ◄──────────────  LiveKit JWT  ───────────────────┘
        │
   LiveKit Android SDK ══ Opus audio ══►  LiveKit SFU  ◄══ Opus audio ══ Element
```

Why this and not the alternatives is in ADR 0006. The short version: the widget
route (what Element X does) means running a React app and WebRTC inside an old
system WebView on a Helio A22, with a touch-designed UI on a phone with no
touchscreen. It is not a fit.

## 3. Components we depend on

| Piece | What it is | Notes |
|---|---|---|
| `m.call.member` state events | MatrixRTC membership (MSC4143) | Ruma already models these (`ruma::events::call::member::CallMemberEventContent`). **Spike first**: confirm the pinned `sdk-android` FFI can send and observe arbitrary state events — see §7. |
| `lk-jwt-service` | Issues LiveKit JWTs against Matrix identity | Self-hosted alongside Synapse. Endpoints include `/get_token`, `/sfu/get`, `/delegate_delayed_leave`, `/sfu_webhook` |
| LiveKit SFU | Media transport | Self-hosted. Also removes the need for a separate TURN server in most deployments — the SFU does that job |
| `io.livekit:livekit-android` | Native client, bundles WebRTC | Audio-only configuration. **Verify minSdk against our 24** and measure the APK growth per ABI |
| Transport discovery | `GET /_matrix/client/unstable/org.matrix.msc4143/rtc/transports` (MSC4519) | Replaces the older `.well-known` discovery, which Element Call 0.24.0 deprecated. Implement the endpoint with a `.well-known` fallback |
| Delayed events (MSC4140) | Leave events that fire if a client dies mid-call | Without this, a phone that loses signal stays "in" the call forever. Not optional on a flip phone |

## 4. Encryption

**v1 ships SFU-trusted, not end-to-end.** Media is encrypted in transit (DTLS-SRTP)
and decryptable only at our own self-hosted LiveKit — not by any third party, but
by our server. Element Call's per-sender key distribution over Matrix is the
fiddliest interop surface in this whole feature, and pairing "get calls working"
with "match a key schedule exactly" is how a feature slips two months.

Two obligations that come with that choice:

1. **Say so in the UI.** The in-call screen shows "Not end-to-end encrypted" when
   the call is SFU-trusted. Encrypted messaging and unencrypted calls in the same
   app, with no visible difference, would be a quiet lie.
2. **Schedule the follow-up.** LiveKit supports frame-level E2EE natively; the
   work is matching Element Call's key distribution, not inventing crypto. It is
   a v1.1 item with its own spike, not a "someday".

### 4.1 E2EE-interop spike result (2026-09-15)

On-device confirmation: **a MatChat↔Element call is clean in an unencrypted
room and pure noise in an encrypted one.** That is the E2EE mismatch, not a
codec, routing, or transport fault — Element Call turns on per-participant
frame E2EE whenever the Matrix room is encrypted (and MatChat's rooms always
are), so Element sends SFrame-encrypted Opus that MatChat plays as noise, while
MatChat's plaintext Opus is noise to Element. One participant alone is silent;
the noise starts the moment a second, encrypting participant joins.

Element Call shares those keys as **encrypted `io.element.call.encryption_keys`
to-device events**, so interop requires MatChat to send and receive them. The
follow-on FFI spike (§7 spike 1) is the blocker:

**The pinned `sdk-android 26.09.3` FFI exposes no to-device primitive.**
`Client`/`Encryption`/`Room` have no `sendToDevice`, no incoming-to-device
subscription — only the SDK's own internal verification-request handling. The
one SDK-provided path that does this key sharing is the **widget driver**
(`makeWidgetDriver`, `WidgetDriverHandle`, `generateWebviewUrl`,
`getElementCallRequiredPermissions` — the last literally negotiates
`io.element.call.encryption_keys`), and it is **WebView-bound** — the route §2
rejected for these devices.

So E2EE calls hit exactly the fork §7.1 anticipated, and the cheap option is
gone. The three real paths:

- **Uniffi Rust shim** (native, matches this app's design): compile our own
  binding exposing `send_to_device` + a to-device receive stream over the
  matrix-rust-sdk core (which *does* have these internally), package the `.aar`
  per ABI, then do the key schedule + LiveKit `E2EEOptions`/key-provider wiring
  on top. Biggest lift is the Rust/`cargo-ndk`/uniffi build pipeline, not the
  Kotlin.
- **Widget driver / WebView**: let the SDK's widget machinery run Element Call
  and handle keys. Fastest to working E2EE, but it is the touch-UI-in-a-WebView
  route §2 ruled out on a Helio A22 / msm8909.
- **Track upstream**: matrix-rust-sdk is building native MatrixRTC; when its FFI
  surfaces call-key sharing, use it directly. Not present in `main` as of this
  spike, so it is a "wait", not a today option.

Until one lands, calls are only intelligible in **unencrypted** rooms; keep the
in-call "Not end-to-end encrypted" banner (§4 obligation 1) honest.

## 5. Ringing without push

No Play Services means no FCM. The foreground sync service that already runs is
what notices an incoming call: a new `m.call.member` event in a room the user is
in raises a full-screen intent.

- **Ring latency = sync latency.** Usually a second or two; worse under Doze.
  **Measure this on hardware in the first week of the milestone** — if it is bad,
  the fallback is a self-hosted UnifiedPush path, and that decision needs to
  happen early, not at the end.
- The call notification uses a full-screen intent and a high-importance channel,
  and must survive the screen being off and the flip being closed.
- A ring that arrives after the caller has given up is worse than no ring: if the
  membership event is older than the ring timeout when we see it, do not ring —
  show a missed call.

## 6. Phone-shaped details that are easy to miss

- **CALL answers, END hangs up.** These phones have real call keys and users have
  thirty years of muscle memory. During an incoming call CALL = Answer and
  END = Decline; in a call, END = Hang up. This is the one place the global key
  map gains meanings, and `docs/UX-SPEC.md §2` records it.
- **A cell call always wins.** If the PSTN rings during a MatChat call, the cell
  call takes the audio. Use a **self-managed `ConnectionService`** (API 26+) so
  the platform arbitrates properly; on API 24–25, fall back to `AudioManager`
  with `MODE_IN_COMMUNICATION` and audio-focus handling. Test both paths — the
  fleet spans both.
- **Earpiece by default**, speaker on the `*` key, and route correctly when the
  flip is closed. Getting this wrong makes every call feel broken.
- **Screen off during a call** to save battery; the proximity sensor may not
  exist on these devices, so use a timeout, not the sensor.
- **APK size.** Bundled WebRTC is the biggest single addition to the binary. The
  25 MB per-ABI budget in `PLAN.md` will need revisiting — measure, then set an
  honest number rather than quietly blowing through the old one.

## 7. Spikes, in order — before any UI is built

1. **Can the FFI carry the signalling?** Confirm that the pinned
   `org.matrix.rustcomponents:sdk-android` can send and subscribe to arbitrary
   `m.call.member` state events. If it cannot, the options are: drive the SDK's
   widget machinery headlessly, or compile a small Rust shim with our own uniffi
   binding. **This determines whether the whole route is cheap or expensive, so
   it goes first.**
   **Result (2026-09-15):** state events ride `sendStateEvent`/`sendRawEvent`
   fine, so signalling works — but the same spike found the FFI exposes **no
   to-device primitive**, which E2EE call keys need. See §4.1 for the fork this
   forces.
2. **Two-way audio to Element.** MatChat ↔ Element X on the same room, media
   through self-hosted LiveKit. Nothing else matters until this works once.
3. **Ring latency on hardware**, screen off, flip closed, after an hour idle.
4. **Cell-call interaction** on a real SIM, both ConnectionService paths.
5. **CPU, heat and battery** for a ten-minute call on the reference device.

Each spike is throwaway code with a written answer. None of them needs a UI.

## 8. Interop checklist (the definition of "supports Element calls")

- [ ] MatChat can join a call started by Element X, and be heard.
- [ ] Element X can join a call started by MatChat, and be heard.
- [ ] Hanging up from either side ends cleanly on both.
- [ ] A MatChat phone that loses signal mid-call disappears from the call within
      the delayed-event window, rather than lingering as a ghost participant.
- [ ] A call in a room with three or more people works (the SFU makes this nearly
      free — do not build a separate 1:1 path).
- [ ] Transport discovery works via the MSC4519 endpoint, with `.well-known`
      fallback.

## 9. Non-goals for the call feature

Video, in any form. Screen sharing. Call recording. Voicemail. Call transfer.
Ringtone customization beyond the system default. If any of these appear in a
PR, something has gone wrong.

# Spike 2 — two-way audio to Element (client wiring)

Server infra is deployed (LiveKit SFU + lk-jwt-service + Synapse delayed events
+ `.well-known` foci on `chats.carpathianserver.org`). This wires the client
media half so the spike can actually run.

## What is now wired (client)

- **Token fetch** (`HttpTokenService`): `MatrixSession.openIdToken()` +
  `deviceId()` → POST `<matrix-rtc.carpathianserver.org>/sfu/get` → `{url, jwt}`.
  The OpenID token lets lk-jwt-service validate our Matrix identity over
  federation, so this works for any homeserver the service federates with.
- **Media** (`LiveKitAudioTransport`): `io.livekit:livekit-android` joins the SFU
  room with that JWT, publishes the mic, auto-subscribes remote audio. Earpiece
  default; Options toggles speaker. Audio-only, no video. SFU-trusted (no E2EE).
- **Config**: `RtcConfig` now points at the real endpoints (provided by :app).
- Signalling (`m.call.member`) was already real from spike 1.

## To verify on hardware (the actual spike)

1. MatChat ↔ Element X in the same room, audio flows through the SFU both ways.
2. Hang up from either side ends cleanly on both.
3. A MatChat phone that loses signal disappears within the delayed-event window.
4. **APK size per ABI** with the bundled WebRTC natives — against the 25 MB
   budget (PLAN.md §4); reset the budget honestly from the measurement.
5. **minSdk 24** actually runs the LiveKit/WebRTC natives on the reference
   device (Helio A22 / Snapdragon 215).
6. Ring latency, cell-call interaction, CPU/heat/battery (VOICE.md §7.3–7.5).

## Known interop gaps (deliberate, tracked)

- **Single focus.** We announce/consume our own SFU and fetch from our own
  lk-jwt-service. A call an Element user starts on a *different* SFU needs
  dynamic focus resolution (read the active focus from `m.call.member` +
  MSC4519/`.well-known`, fetch the token from *that* service). Works today when
  MatChat starts the call or everyone is on our infra.
- **Device-scoped membership.** `m.call.member` uses the user id as the state
  key; the newer `_<user>_<device>` format is a follow-up once validated.
- **Ring pipeline.** Incoming-call full-screen intent from the sync service
  (VOICE.md §5) is not wired yet — outgoing calls and in-call work first.
- **FFI/interop shapes** (the `/sfu/get` path + JSON, the `m.call.member`
  content) follow the current specs but are confirmed only when this runs
  against the live Element client.

## Dependency note

`io.livekit:livekit-android` bundles WebRTC — the largest single addition to the
APK. Release builds already split per ABI; re-measure and reset the PLAN.md
budget once built.

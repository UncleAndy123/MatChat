# ADR 0003 — No room discovery, enforced structurally

**Status:** Accepted · **Date:** 2026-09

## Context

These phones exist to remove open-ended discovery. Every mainstream messenger
that has been tried in this setting failed on exactly one surface — a directory,
a people search, or a media feed. A settings toggle is not sufficient: toggles
get flipped, and code that exists gets called.

## Decision

Discovery is removed at three layers:

1. **API absence.** `:core:policy` exposes no method that can return a public
   room list, a user-directory result, or an arbitrary homeserver URL. Features
   cannot call what does not exist.
2. **Build enforcement.** A Detekt rule (`NoDiscoveryApis`) fails the build on
   any reference to the SDK's directory/search calls. Suppressions are not
   permitted; changing this requires changing this ADR.
3. **Server enforcement.** Synapse is configured with closed registration,
   deny-all `room_list_publication_rules`, invite-only rooms, and
   admin-provisioned accounts (`docs/SERVER.md`). This is the layer that actually
   holds if a modified client ever reaches the device.

## Consequences

- There is no in-app way to join a room. Membership is an administrative act.
  The room-list empty state says so in plain language.
- No search box of any kind ships without a human decision — including "filter
  my own rooms", because that control is how search re-enters a product.
- The client alone is not a security boundary; the server config is. Both ship
  together.

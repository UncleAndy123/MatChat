# ADR 0002 — Android Views, not Jetpack Compose

**Status:** Accepted · **Date:** 2026-09

## Context

The entire product is hardware-key navigation on a 2.6″ 240×320 screen with a
Helio A22 / Snapdragon 215 and 2 GB RAM. Focus behaviour is not a detail here —
it *is* the UI.

## Decision

Use Android Views + ViewBinding + RecyclerView. Do not use Compose.

## Rationale

- The View system's focus model (`focusable`, `nextFocusDown`,
  `descendantFocusability`, focus-state drawables) is mature, predictable, and
  well documented for D-pad hardware. It has been carrying set-top boxes and
  feature phones for over a decade.
- Compose's focus system is capable (Compose for TV proves it) but the behaviour
  on non-TV hardware-key devices is less well-trodden, and debugging a focus
  escape in Compose costs more than writing the XML did.
- Compose adds runtime cost and several MB of APK on a device where both are
  scarce.
- View XML is more legible to AI contributors and produces smaller, more
  reviewable diffs for simple layouts.

## Consequences

- More boilerplate per screen; mitigated by the strict five-file pattern.
- Screenshot testing uses Paparazzi against View hierarchies.
- Revisit only if a concrete focus or performance problem is measured on the
  reference device — not because Compose is newer.

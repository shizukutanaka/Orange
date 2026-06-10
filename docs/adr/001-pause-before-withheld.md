# ADR 001 — Pause tile precedes withheld-number block (Layer 2 > Layer 3)

**Date:** 2025-05  
**Status:** Accepted  
**Deciders:** Orange maintainers

## Context

Orange's 16-point engine initially placed withheld-number blocking (Layer 2)
before the Pause tile check (Layer 3). A user waiting for a callback from a
hospital, which typically presents as a restricted/withheld number, would tap
the Pause tile expecting all calls to ring — but withheld calls were silenced
anyway.

## Decision

Move Pause to Layer 2, Withheld to Layer 3. Pause semantics = "everything
rings, no exceptions, until the hour expires."

## Consequences

- Users can receive hospital/clinic callbacks by tapping Pause.
- During Pause, withheld scam callers also ring. This is documented in
  HONESTY_ADDENDUM.md §9.
- The decision is not configurable — adding a "pause except withheld" option
  would violate Rams #10 (minimal design).

# ADR 003 — Decision.warnPayload eliminates double PoliceStationDirectory lookup

**Date:** 2025-05  
**Status:** Accepted

## Context

`decide()` called `PoliceStationDirectory.isPoliceHQ()`, which internally
called `lookup()`. Then `SilentBlockerService.handleDecision()` called
`PoliceStationDirectory.lookup()` again to get the display name for the
warning notification. Two HashMap traversals per screened police-number call.

## Decision

Add `warnPayload: String?` field to `Decision`. The engine calls `lookup()`
once, stores the result in `warnPayload`, and the adapter reads it directly.

## Consequences

- One fewer HashMap lookup per police-number call (hot path improvement).
- Decision is now a richer type — callers must not assume `warnPayload` is
  always null for non-warning decisions.
- Tests verify `warnPayload` correctness (see CallDecisionTest).

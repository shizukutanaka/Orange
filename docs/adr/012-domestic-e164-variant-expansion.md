# ADR 012 — Domestic↔E.164 variant expansion at exact-match call sites

## Status
Accepted

## Context

Android's Telecom framework delivers phone numbers to `onScreenCall()` in
whichever format the carrier assigns for that particular call. For Japanese
numbers this means either domestic form (`09012345678`) or E.164 form
(`+819012345678`), and the choice varies by carrier, call direction, and
roaming state — sometimes for the SAME physical number across consecutive
calls from the same device.

Orange stores numbers at three call sites:

| Store | Written by | Example format |
|-------|-----------|----------------|
| `outbound-known` StringSet | `addToOutbound()` | `09012345678` (domestic from dialer) |
| `WangiriTracker` | `CallStateObserver.onIdle()` | domestic (incoming RINGING) |
| `OutboundGuard` | `handleDecision()` | whichever form the incoming call used |

When the READING side of one of these stores is called with a number in a
different format than the stored key, an exact-string lookup returns false even
though the numbers are semantically identical.

### Three bugs this caused

**Bug 1 — OutboundGuard missed outbound-to-flagged warning** (fixed in earlier
session): `handleOutgoing()` called `OutboundGuard.wasRecentlyFlagged(p,
number, now)` with the raw outgoing number. A blocked call stored as domestic
("09012345678") was never matched when the user dialed E.164 ("+819012345678").

**Bug 2 — Wangiri Layer 7 miss** (fixed in earlier session): `decide()` looked
up `state.recentShortRings[ctx.number]` with exact-string match. A short-ring
stored as domestic while the callback arrived as E.164 → Layer 7 never fired →
Wangiri callback rang through unblocked.

**Bug 3 — WangiriTracker.forget() stale entry** (fixed in this session):
After a WANGIRI_CALLBACK block, `forget(p, number)` was called with the
callback's format. If the short-ring was stored under a different format, the
`Map.remove()` was a no-op and the stale entry lingered until the 6-hour
window expired.

## Decision

Apply `phoneVariants(number, callingCode)` at every point where a stored number
is compared with a runtime number that may have arrived in a different format:

1. **Reading from outbound-known/family/business sets in `screenIncoming()`**:
   expand `variants = phoneVariants(number, cc)` and check
   `variants.any { it in setA || it in setB }`. ← already done before this ADR.

2. **Reading WangiriTracker in `decide()` Layer 7**:
   ```kotlin
   val recentRingAt = phoneVariants(ctx.number, wangiriCc)
       .firstNotNullOfOrNull { state.recentShortRings[it] }
   ```
   ← Bug 2 fix.

3. **Reading OutboundGuard in `handleOutgoing()`**:
   ```kotlin
   val flagged = phoneVariants(number, cc).any { OutboundGuard.wasRecentlyFlagged(p, it, now) }
   ```
   ← Bug 1 fix.

4. **Calling `WangiriTracker.forget()` in `handleDecision()`**:
   ```kotlin
   phoneVariants(number, wfCc).forEach { WangiriTracker.forget(p, it) }
   ```
   ← Bug 3 fix.

### Where variant expansion is NOT applied

- **`RepeatCallerTracker`**: record and lookup use the same runtime format for
  the same caller. Repeat-calling automated dialers use consistent format per
  session. Expanding variants here would require a canonical-form choice (which
  form to store), adding complexity for a theoretical edge case (format flip
  between calls from the same human dialer).

- **`SpamCache`**: block and restore use the same `number` string from the same
  call-processing chain (add in `handleDecision`, remove via `RestoreReceiver`
  which receives the number from a PendingIntent extra set at notification
  creation time). Consistent by construction.

- **`BlockHistoryStore`**: stores masked numbers (last 4 digits only). The mask
  intentionally discards prefix information, so variant mismatch is impossible.

## Consequences

- Three behavioral bugs are eliminated: Wangiri callbacks that change format
  are now blocked, OutboundGuard warnings fire regardless of dial format, and
  short-ring entries are cleaned up promptly after a confirmed block.
- **Invariant for future developers**: whenever adding a new store that maps
  phone numbers to state, check whether the writing side and reading side may
  receive the same number in different formats. If so, apply `phoneVariants()`
  at the reading side (reading is O(2) — domestic and E.164 — so the cost is
  negligible).
- `phoneVariants()` is the single canonical expansion function. It lives in
  `CallDecision.kt` alongside `callingCodeOf()` (the country-code lookup).
  Do not inline manual "+81" prefix logic at call sites.

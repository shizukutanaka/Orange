# ADR 008 — Masked block history with suffix-based restore

## Status
Accepted (v1.1).

## Context
v1.0 had no way to review what Orange blocked, and after the 7-day trust
window there was no way to recover a false positive at all. For the target
user (elderly JP users), a silently-dropped hospital callback is the
single worst failure mode the product can have.

The obvious fix — store the full blocked numbers and show them in a list —
conflicts with ADR 006: the spam cache is salted-hashed precisely so that
plaintext numbers never touch disk. A history screen backed by plaintext
numbers would reintroduce the PII store ADR 006 removed.

## Decision
1. `BlockHistoryStore` records each SILENCE verdict as
   `(masked number, timestamp, BlockReason)` where the mask keeps only the
   final 4 digits (`****5678`). Bounded at 50 entries, 30-day TTL.
   The full number is never written.
2. Restore from history is implemented by `AllowSuffixStore`: tapping
   "Allow" stores the 4-digit suffix, and `SilentBlockerService` rings any
   incoming call whose normalized number ends with an allowed suffix,
   before all other rules.
3. The suffix check lives in the adapter, not in `decide()` — the pure
   engine stays unchanged and the override is an adapter-level concern,
   same as the repeat-caller pre-check.

## Consequences
- A 4-digit suffix collides with ~10^-4 of the number space. An allowed
  suffix therefore also rings other numbers sharing that suffix. We accept
  this: the user's intent ("stop blocking that caller") is honored, and a
  rare scammer sharing the suffix still hits the warn layers (police
  directory, premium-rate are RING/warn paths anyway).
- History entries cannot be tied back to full numbers forensically, which
  is the point.
- The trust-week Restore notification (exact number, in memory at tap
  time) remains the precise path; history-Allow is the best-effort path
  for blocks the user notices later.

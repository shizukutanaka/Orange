# ADR 007 — normalize() folds full-width characters to ASCII

**Date:** 2025-05
**Status:** Accepted

## Context

`PhoneNumbers.normalize()` kept any character for which `Char.isDigit()` is
true, plus the ASCII `'+'`. But Kotlin/Java `Char.isDigit()` returns true for
the entire Unicode Nd category, including full-width digits �０–９
(U+FF10–FF19) and other scripts' digits (Arabic-Indic, etc.). Full-width
numbers occur in real input from some Japanese IMEs, copy-paste, and a few
carrier/handset combinations.

The result: a full-width number like ＋８１９０… survived normalization as
８１９０… (full-width digits kept, full-width plus U+FF0B dropped because it
is not ASCII '+'), and then failed EVERY half-width prefix test in the engine
(`startsWith("090")`, the police directory, the spoof detector). The call was
silently misclassified — typically allowed through when it should have been
screened.

## Decision

Fold full-width plus and full-width digits to ASCII first, then keep only
ASCII `0`–`9` and `'+'`. Digits from other scripts are now stripped rather than
kept, so the engine only ever sees `[0-9+]`.

## Consequences

- Full-width-formatted numbers are classified identically to half-width.
- `normalize` remains idempotent (folding ASCII is a no-op).
- Non-ASCII, non-full-width digits (e.g. Arabic-Indic) are stripped instead of
  passed through — correct, since they cannot be part of a dialable JP number.
- Added unit tests for full-width, mixed-width, and foreign-digit inputs.

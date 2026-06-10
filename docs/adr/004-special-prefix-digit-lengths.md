# ADR 004 — Special-prefix digit-length rules are split by actual length, not grouped

**Date:** 2025-05
**Status:** Accepted

## Context

During a refactor of `DomesticSpoofDetector.isImpossibleJpNumber` (to bring the
function under the 40-line limit), the special-number prefixes were grouped into
a single "must be 11 digits" check: `0570 / 0800 / 0120`. This was wrong:

| Prefix | Service | Correct domestic length |
|--------|---------|------------------------|
| 0120 | freephone | **10** (0120-XXX-XXX) |
| 0800 | freephone | **11** (0800-XXX-XXXX) |
| 0570 | navi-dial | **10** (0570-XXX-XXX) |
| 0990 | teledome | **10** (0990-XXX-XXX) |
| 050  | IP phone  | **11** (050-XXXX-XXXX) |
| 06x/07x/08x/09x | mobile | **11** |

The grouping made Orange silence every legitimate 0120 freephone and 0570
navi-dial call (10-digit numbers failing an 11-digit test), and it dropped the
050 IP-phone length check entirely. The static CI (`check_comprehensive.sh`)
did not catch this because it does not execute the JVM unit tests; the existing
`valid_toll_free_0120_passes` assertion would have failed immediately under
`gradlew testReleaseUnitTest`.

## Decision

Split the prefixes into two explicit lists by their actual required length:

```kotlin
private val ELEVEN_DIGIT_PREFIXES = listOf("050","060","070","080","090","0800")
private val TEN_DIGIT_PREFIXES    = listOf("0120","0570","0990")
```

## Consequences

- Legitimate freephone (0120), navi-dial (0570), and teledome (0990) calls ring.
- 050 IP-phone length validation is restored.
- Added six regression tests in `DomesticSpoofDetectorTest`.
- `check_comprehensive.sh` now prints an explicit warning that it performs
  static analysis only and that the JVM test suite must run before release.
- Process lesson: a refactor that "just reorganizes" logic must still be
  validated against the JVM test suite, never against static checks alone.

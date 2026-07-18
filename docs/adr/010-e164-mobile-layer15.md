# ADR 010 — E.164 form JP mobile numbers must match Layer 15 high-risk-hour warning

## Status
Accepted

## Context
Layer 15 (`HIGH_RISK_HOUR_DOMESTIC`) fires when `isUnknownDomesticMobile()` returns true
and the call arrives during business-hours risk windows (Mon–Fri 09:00–11:59 / 13:00–15:59 JST).
The implementation uses `hour in 9..11 || hour in 13..15` where `hour` is the JST
hour-of-day (0–23), so 12:00 noon and 16:00 are excluded (matching the half-open convention
used in Japanese telecommunications fraud-peak research).

Android delivers incoming calls via `CallScreeningService.onScreenCall()`. The number
format depends on the carrier stack:

- Domestic SIM receives domestic trunk form: `09012345678`
- Roaming / some VoLTE paths receive E.164 form: `+819012345678`

The original `isUnknownDomesticMobile()` checked only trunk prefixes (`090`, `080`, `070`,
`060`). Numbers arriving in E.164 form (`+8190…`, `+8180…`, `+8170…`, `+8160…`) bypassed
the check — Layer 15 never fired for them, meaning risk-window calls from unknown mobiles
silently rang through with no warning to the user.

## Decision
Extend `isUnknownDomesticMobile()` in `CallDecision.kt` to also recognise the four E.164
JP mobile prefixes:

```kotlin
internal fun isUnknownDomesticMobile(number: String): Boolean {
    if (number.startsWith("090") || number.startsWith("080") ||
        number.startsWith("070") || number.startsWith("060")) return true
    if (number.startsWith("+8190") || number.startsWith("+8180") ||
        number.startsWith("+8170") || number.startsWith("+8160")) return true
    return false
}
```

Two unit tests added to `CallDecisionTest.kt`:
- `e164_jp_mobile_high_risk_hour_gets_warning` — confirms warning fires
- `e164_jp_mobile_outside_high_risk_hour_no_warning` — confirms no false-positive outside window

## Consequences
- Layer 15 now fires consistently regardless of whether the carrier delivers the number
  in domestic or E.164 form.
- No other layers are affected; `phoneVariants()` already handles E.164 ↔ domestic
  normalisation for the outbound-known and family-number checks.
- The fix is additive (no existing test cases regress).

## Addendum (2026-07)
This ADR's operative decision — recognising E.164 JP mobile prefixes in
`isUnknownDomesticMobile()` — remains in force unchanged. The **hour windows**
described in the Context above (`09:00–11:59 / 13:00–15:59`, noon and 16:00
excluded) were a separate, later-superseded parameter: `isHighRiskHour()` now
covers `hour in 9..12 || hour in 13..16 || hour in 18..20` (the noon and 16:00
hours were folded in, and an evening 18:00–20:59 アポ電 window was added — see
CHANGELOG and the `RESEARCH_BASIS.md` 2026 field-data section). The window
figures in this ADR's Context are preserved as the historical record of the
decision at the time it was made, not as a description of current behaviour.

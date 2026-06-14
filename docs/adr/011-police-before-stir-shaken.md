# ADR 011 — Police directory check must precede STIR/SHAKEN silence

## Status
Accepted

## Context
The decision engine's layer ordering matters because it is first-match-wins.
Two layers interact in a non-obvious way:

- **Layer 9 (original: STIR/SHAKEN)**: if the carrier reports
  `VERIFICATION_STATUS_FAILED`, silence the call as `CARRIER_VERIFICATION_FAILED`.
- **Layer 10 (original: Police check)**: if the number is a known police HQ,
  return `RING` with a `POLICE_IMPERSONATION` or `POLICE_IMPERSONATION_HIGH`
  warning (the severity depends on whether `verificationFailed` is also true).

With police at Layer 10, a call from a real police HQ phone that also had
carrier verification fail (e.g., a JP prefectural police HQ routed through a
VoIP trunk with misconfigured STIR/SHAKEN) would be:

1. Caught by Layer 9 (STIR/SHAKEN): `verificationFailed = true` → **SILENCE**
2. Layer 10 never reached: police warning never shown

The call is permanently blocked — a real officer cannot get through — and the
user never knows the number was a police HQ.

## Decision
Swap the layers: police check is now **Layer 9**, STIR/SHAKEN is now **Layer 10**.

```
Layer 9: if calleeCountryIso == "JP" && PoliceStationDirectory.lookup(number) != null:
           → RING + POLICE_IMPERSONATION (or POLICE_IMPERSONATION_HIGH if verificationFailed)
Layer 10: if verificationFailed:
           → SILENCE + CARRIER_VERIFICATION_FAILED
```

This ensures:
- Police HQ numbers **always ring** regardless of STIR/SHAKEN status.
- When STIR/SHAKEN also fails on a police HQ number, the failure **escalates the
  warning severity** to `POLICE_IMPERSONATION_HIGH` rather than blocking the call.
- Non-police numbers with `verificationFailed = true` still reach Layer 10 and
  are silenced.

## Consequences
- The existing unit test `police_hq_plus_stir_shaken_fail_escalates` (which
  asserted `Verdict.RING` + `POLICE_IMPERSONATION_HIGH`) was already passing in
  static analysis but would have failed in JVM `decide()` tests. It now correctly
  passes end-to-end.
- The general principle established: any layer that overrides a SILENCE with a
  RING (police, emergency, outbound-known) must come **before** the SILENCE-
  producing layer it is meant to override.

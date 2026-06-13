# ADR 009 — Fix FOREIGN_GENERIC layer coverage (isoOfCountryCode → callingCodeOf)

## Status
Accepted (v1.2).

## Context
Layer 13 (FOREIGN_GENERIC) was supposed to silence all unsolicited international
calls to JP users whose number isn't in outbound-known. The implementation:

```kotlin
val callerIso = cc?.let(::isoOfCountryCode)
if (callerIso != null && callerIso != ctx.calleeCountryIso) {
    return Decision(Verdict.SILENCE, BlockReason.FOREIGN_GENERIC)
}
```

`isoOfCountryCode()` only mapped 16 country codes (US, CN, KR, GB, DE, FR, IT,
RU, AU, IN, VN, PH, MA, NG, PW, JP). Any call from a country not in this list
returned `null`, so `callerIso` was null, and the call rang through.

Affected examples: Brazil (+55), Thailand (+66), Indonesia (+62), Turkey (+90),
Egypt (+20), Pakistan (+92), Bangladesh (+880) — all common VoIP fraud transit
points — silently bypassed Layer 13.

`ScamPrefixSeed.countryCodeOf()` already covers 150+ country codes in its
1/2/3-digit tables, so the data was there; only the bridge was wrong.

## Decision
Replace the `cc → ISO → compare ISO` path with a direct comparison:

```kotlin
val callerCc  = ScamPrefixSeed.countryCodeOf(ctx.number)
val calleeCc  = callingCodeOf(ctx.calleeCountryIso)
if (callerCc != null && calleeCc != null && callerCc != calleeCc) {
    return Decision(Verdict.SILENCE, BlockReason.FOREIGN_GENERIC)
}
```

`callingCodeOf(iso)` maps the callee's ISO country code to their ITU calling code.
For Orange's primary use case (JP), this is "81". A call with country code "55"
(Brazil) != "81" (JP) → silenced.

The function only needs to cover the countries where Orange is deployed, so a
small lookup table (JP, US, KR, CN, GB, DE, FR, AU, IN, BR, TH, ID) is fine.
Unknown callee ISOs return null → layer doesn't fire → conservative safe default
(ring through), same as before.

## Consequences
- FOREIGN_GENERIC now fires for ~150+ country codes instead of 16.
- Regression tests added for Brazil, Thailand, Indonesia, Turkey.
- outbound-known override still applies (Layer 5, before this layer) so a JP
  user who called Brazil won't have the callback blocked.
- FOREIGN_ELEVATED (Layer 12, checked before Layer 13) is unchanged and
  unaffected.

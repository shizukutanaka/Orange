# ADR 015 — The US country pack: ADR 014's split, proven with a second country

## Status
Accepted

## Context

ADR 014 split Orange's bundled data into per-country packs and claimed
that adding a country had become a data change instead of engine
surgery. With only the JP pack shipped, that claim was unproven: a
structure exercised by exactly one country can still be a JP-shaped
structure wearing a generic interface. The US is the natural second
pack — the largest NANP market, and a numbering plan whose shape
(no trunk prefix, NPA-NXX-XXXX, N11 service codes) differs from
Japan's in exactly the places where a JP-shaped assumption would hide.

Three JP assumptions were in fact lurking, and the US pack exposed all
three:

1. **The default directory conversion inserts a trunk 0.** The
   `CountryPack` interface's shared `lookupIn` rebuilds a domestic form
   by prefixing "0" to the digits after the calling code — correct for
   +81, wrong for +1, where it would fabricate a leading zero the NANP
   never has and silently miss every entry.
2. **Key-shape invariant tests assumed the JP domestic form.** The
   police/tax test suites asserted every merged key starts with "0";
   that was a JP numbering-plan fact stated as a global invariant.
3. **The plan check itself is plan-specific.** "Impossible number"
   means different rules per plan (trunk/leading-digit rules in JP;
   NPA/NXX 2–9, N11 reservation, and the 555 fictitious range in NANP).

## Decision

1. **Ship `UsCountryPack` as the second compiled pack**, registered by
   one line in `CountryPacks.all`, holding the United States' own
   verified data (all verified 2026-11 from each organisation's OWN
   official page, the same bar as the JP pack):
   - Police warn directory: FBI Headquarters, (202) 324-3000 (fbi.gov).
     The FBI itself warns that scammers spoof exactly this number,
     which is the warn-but-ring directory's reason to exist. Local
     departments are deliberately not enumerated: there is no single
     verifiable national list at this pack's accuracy bar, and a wrong
     row is worse than a missing one.
   - Tax warn directory: IRS individual assistance, 1-800-829-1040
     (irs.gov).
   - Emergency: 988 (Suicide & Crisis Lifeline — FCC-designated 2020,
     live nationwide 2022-07-16; SAMHSA/FCC) and its legacy number
     1-800-273-8255. 911 stays with `EmergencyWhitelist`'s roaming set,
     per the interface contract.
   - Elevated-risk corridors for US users: +91 only — US DOJ
     prosecutions (2016–2020, Ahmedabad call-centre networks) document
     IRS/USCIS impersonation aimed at US victims as that corridor's
     signature fraud. +1 is never elevated (it is the home plan), and
     the JP pack's corridors are not copied: risk is relative to the
     callee's country.

2. **Ship the US business directory as an asset pack**
   (`assets/packs/us/business_directory.csv`, 9 entries: SSA, USPS,
   FTC, FCC, FEMA, National Passport Information Center, Medicare, VA
   Benefits, Poison Control — each from the organisation's own contact
   page) plus one `us` line in `assets/packs/index.txt`. FBI and IRS
   numbers are deliberately absent from the CSV, for the same reason
   警察庁/国税庁 are absent from the JP file: the bundle grants silent
   trust, and these are the numbers impersonators spoof.

3. **The NANP plan check is a new `NanpSpoofDetector`**, the structural
   counterpart of `DomesticSpoofDetector`, delegated to by
   `UsCountryPack.isImpossibleDomesticNumber`. It flags only what the
   plan (FCC/NANPA) makes provably impossible: NPA or NXX starting
   0/1, N11 in either position, area code 555 (unassigned), and the
   NANPA-reserved fictitious range 555-0100–555-0199. It abstains on
   other countries, short codes, malformed input, and valid premium
   assignments (toll-free NPAs, 900; Caribbean premium NPAs remain
   `CaribbeanPremiumNANP`'s layer).

4. **The US pack overrides both directory lookups** with NANP-correct
   normalization (`NanpSpoofDetector.toNanpNational`: strip a leading
   country code 1, never insert a trunk digit). The interface's default
   conversion is unchanged and remains the JP pack's.

5. **The two key-shape invariant tests became per-pack** (JP keys:
   10 digits starting with 0, exactly as before; US keys: 10 digits
   starting 2–9), and the aggregate tests assert the merged view with
   the JP entries byte-for-byte intact. No test was weakened: every JP
   assertion survives at full strength, and the US assertions were
   added beside them.

## Consequences

- The ADR 014 claim is now exercised end-to-end: a second country
  shipped as one data object + one asset file + two registry lines,
  with **zero changes to `decide()`'s logic** (one comment updated to
  name both plan checks). JP behaviour is unchanged — the full suite
  passes with the JP data pinned byte-for-byte by the aggregate and
  lookup tests, and the Python oracle (JP rules) still passes 31/31.
- Suite: 545 SDK-free tests (was 515), 649 total. New coverage:
  `UsCountryPackTest` (registration, lookups, decide() integration —
  FBI/IRS warnings fire for US callees only, +91 elevated for US
  callees only, +675 stays JP-only, fictitious-555 silenced via
  Layer 8) and `NanpSpoofDetectorTest` (the NANP rules above).
- The interface default `lookupIn` is now documented by contrast as a
  trunk-prefix-plan conversion. A future pack for another trunk-0 plan
  can use it as-is; a future no-trunk plan follows the US override.
- `ProtectionDataVersion.LAST_UPDATED` stays "2026-04": the constant
  tracks the OLDEST bundled data by design, and the US pack's 2026-11
  verification is newer than that floor.
- Known limits, stated plainly: the US warn directories hold one entry
  each (the two nationally-uniform impersonation targets). State/local
  police and tax bodies are out of scope until a source exists that
  meets the own-page verification bar. The business directory's 9
  entries are a seed at the same bar, not a census.

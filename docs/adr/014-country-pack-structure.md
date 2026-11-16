# ADR 014 — Country packs: bundled data split by country code, in two tiers

## Status
Accepted

## Context

Every piece of bundled screening data was Japan-shaped: the business
directory was a single JP CSV, and the police/tax directories, emergency
numbers, numbering-plan rules and elevated-risk codes were JP data inside
objects whose names and call sites hardcoded the country (`decide()` gated
three layers on `calleeCountryIso == "JP"`). The engine's design was sound;
its *data geography* capped the addressable user base at "Android users in
Japan" for a product whose input — phone calls — is universal. Adding a
second country would have meant editing the engine.

The data itself falls into two kinds with opposite failure requirements:

- The **business directory** is curation data: comparatively large,
  expected to grow per country, and already file-based with a
  degrade-and-retry load contract.
- The **safety data** (emergency numbers, police/tax warn directories,
  numbering-plan rules, elevated-risk codes) is small and must not be
  able to fail to load. It feeds a pure, Context-free decision engine,
  and its current form — compile-time constants — is precisely why it
  cannot silently vanish at runtime. EmergencyWhitelist's own KDoc states
  the requirement: "it never fails."

## Decision

1. **Bundled data is organised as country packs, keyed by ISO code.**
   A pack carries, for one country: its business directory, emergency
   numbers, warn-but-ring police and tax directories, elevated-risk
   caller codes, and its numbering-plan validity check.

2. **Asset packs (file tier).** The business directory ships as
   `assets/packs/<code>/business_directory.csv`, registered in
   `assets/packs/index.txt`. `BusinessDirectoryBundle.load()` merges all
   registered packs with its existing all-or-nothing, cache-on-success
   contract. Adding a country here is data-only: drop the file, add one
   index line, no code change. JP is the first pack; its file is the
   previous `assets/business_directory.csv`, moved byte-identical.

3. **Compiled packs (safety tier).** Safety data ships as implementations
   of the `CountryPack` interface, registered in `CountryPacks.all`.
   `JpCountryPack` holds the JP data, moved unchanged from
   PoliceStationDirectory / TaxAgencyDirectory / EmergencyWhitelist /
   ScamPrefixSeed. Those objects remain as merged views with their
   existing APIs, so every current caller and test is untouched in shape.
   EmergencyWhitelist additionally keeps the roaming numbers (911/112/
   999/000 and their international forms) that belong to no single pack.
   Adding a country here is one new data object + one registry line.

4. **The engine names no country.** `decide()` resolves the callee's
   pack via `CountryPacks.forIso()` for the numbering-plan layer (8),
   the government-impersonation layers (9/9b — still scoped to the
   callee's own pack, as the JP gate was), and the elevated-risk layer
   (12). Countries without a pack skip those layers, exactly as
   non-JP callees did before.

## Consequences

- Behaviour is unchanged: the JP data is byte-identical, the 504-test
  suite passed before and after (515 with the new pack tests), and a
  pre/post equivalence run over ~19k dumped engine outputs (directory
  contents, lookups, and 18,880 `decide()` cases across callees, flags
  and pause/DND states) is identical.
- The ceiling moves: a second country's *business* data is now a
  data-only change, and its safety data is a single self-contained
  object instead of engine surgery.
- Two registries exist by design (asset `index.txt` for files, compiled
  `CountryPacks` for safety data), each documented at its location;
  a country with warn directories needs both, a country with only a
  business directory needs just the index line.
- Invariant checks that pinned the old locations (comprehensive-check
  §5/§9/§11, the business-bundle regression test's CSV path) were
  retargeted at the packs, preserving each check's intent: emergency
  coverage, CSV integrity, and doc/data count drift are still gated.

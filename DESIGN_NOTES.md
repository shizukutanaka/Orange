# Orange — Apple Redesign (Subtraction Log)

## One-line product
Phone rings. If it's spam, it doesn't.

## What this version deletes (and why)

| Deleted                           | Reason                                                        |
|-----------------------------------|---------------------------------------------------------------|
| 5-step SetupWizard                | One-screen `OnboardingActivity`. Product promise = the button. |
| Settings screen                   | Defaults are the product. If a setting matters, it's wrong.    |
| Statistics dashboard              | Dashboards require attention. Product's goal is zero attention.|
| Pro tier + donations              | Monetization before trust kills trust. Revisit after 1M installs. |
| Country selector                  | `TelephonyManager.networkCountryIso` already knows.            |
| Language picker                   | Follow system locale. Period.                                  |
| SmartCallFilter (50 heuristics)   | 3 rules handle ~95%. The other 5% needs user-controlled undo, not more rules. |
| GlobalOrangeFramework             | One country table, inline, extended only when users complain.  |
| Per-block expandable notification | Trust-week silent notification only. Day 8+: silent.           |
| "Report to community" button      | We don't run a community yet. Don't ship the pretense.         |
| 4x1 rich home widget              | One number. No buttons. Trophy, not dashboard.                 |
| Toggle switch in app              | Moved to Quick Settings `PauseTile`. One control, one location. |

## What stays

1. **OnboardingActivity** — single screen, single button, triggers role request, self-destructs.
2. **SilentBlockerService** — three rules (outbound-known, spam-cached, foreign-unsolicited).
3. **TrustNotifier** — transparent for 7 days (every block → silent notification + restore), silent thereafter.
4. **RestoreReceiver** — one-tap whitelist forever. No confirmation dialog.
5. **OrangeWidget** — one number, zero interactions.
6. **PauseTile** — Quick Settings, 1-hour pause, the only runtime control.

## The trust curve
- Day 0: User taps "Protect." Role granted. App disappears from task list.
- Days 1–7: Each blocked call shows a low-priority silent notification with "Restore." User sees the app working without being interrupted by it.
- Day 8+: Notifications stop. App has earned invisibility. Widget shows the cumulative count.
- Anytime: Pull down Quick Settings → "Orange" → pause 1 hour. Tile becomes inactive, resumes automatically.

## Future restraint checklist
Before adding a feature, ask:
1. Does this make the phone stop ringing when it shouldn't?
2. Could the default value of this setting be the right answer for 95% of users?
3. Does this feature survive the 7-day trust test (would a new user welcome it on day 8 without being told it exists)?

If any answer is "no," the feature doesn't ship.

## What to build next (in order, and only if the prior is complete)

1. **Watch complication** — same as widget: one number, zero interactions.

That is the whole list, and it used to be three items. The other two were struck
in 2026-07 because they contradicted the section immediately below this one — in
the same file, a few lines apart:

- ~~**SMS spam screen**~~ — "What NOT to build" already forbids *"SMS filtering
  in same APK (separate role + doubles permission surface)"*. Android grants SMS
  read access essentially only to the default SMS handler, so this is not a
  feature to add to Orange; it is a different product. Worth noting from the
  §1-2 research: fake 不在通知 SMS really is the dominant delivery-impersonation
  vector, so the market instinct was right — but a call screener structurally
  cannot see SMS, and pretending otherwise on a roadmap does not change that.
- ~~**Carrier-reported scam signatures**~~ ("fetch JP telecom's public block
  lists at install time, never again") — "What NOT to build" forbids
  *"Background network sync of any kind (INTERNET permission = product soul
  lost)"*. The "install time, never again (offline product)" hedge does not
  survive contact with the mechanism: a fetch of any frequency needs the
  INTERNET permission, and `tools/check_no_network.sh` hard-fails on that string
  appearing in any manifest. This item was an instruction to do something CI is
  built to reject.

The contradiction is recorded rather than quietly deleted because of where this
list sits: README and CONTRIBUTING both send contributors *here* as the gate on
new features. A roadmap that proposes what the constraints forbid does not just
waste a contributor's afternoon — it invites the argument "but the roadmap says
so" against the product's two load-bearing promises. A roadmap is a requirement
like any other, and it gets audited like any other.

## What NOT to build
- Call recording (legal minefield in Japan — 盗聴禁止法, scope creep)
- "Reverse lookup" of who's calling (privacy violation dressed as a feature)
- AI-generated block explanations (more notification noise)
- Cross-device sync (requires accounts → servers → money → business model)
- Crowd-sourced block reports (requires INTERNET + privacy model)
- Background network sync of any kind (INTERNET permission = product soul lost)
- AccessibilityService for scam-app-install detection (Play policy risk + massive surface)
- SMS filtering in same APK (separate role + doubles permission surface)
- Bubbles API for in-call warnings (designed for chat Person objects, not screeners)
- **CEIVE-style callback inference** (Deng et al., MobiCom'18) — placing a silent
  callback to infer the caller's call state needs call-origination + low-level
  signaling access a CallScreeningService lacks, and its 10–23s verification
  delay blows the 5s screening budget. See RESEARCH_BASIS.md.
- **LLM call-content analysis** (arXiv:2409.11643, arXiv:2501.15290) — high
  reported accuracy but requires RECORD_AUDIO + network egress. Both violate the
  privacy manifesto. See RESEARCH_BASIS.md.
- **Google libphonenumber** (github.com/google/libphonenumber) — the obvious
  "just use the standard library" choice for number classification. Rejected:
  it adds ~7k methods to the dex and ships heavyweight metadata, blowing the
  ≤1 MiB APK budget for a tool whose entire JP rule set fits in ~50 lines
  (`DomesticSpoofDetector`). We instead encoded the specific MIC numbering-plan
  rules we need by hand and cross-check them against the Python oracle. We DID
  use libphonenumber's public number-type taxonomy (Fixed-line/Mobile/Toll-free/
  Premium/VoIP/Pager) to verify our prefix rules are complete — e.g. confirming
  020 is M2M/pager and correctly blocked for voice.
- **Property-based testing dependency** (Kotest/jqwik) — valuable, but adds a
  test-time dependency. We get most of the benefit with zero dependencies via
  `EngineInvariantTest`, which generates a large input space in plain Kotlin and
  asserts engine invariants (determinism, emergency-always-rings, salted-hash
  properties) over all of it.

## Deletion log

Every removed line was a decision. Major ones:

| Removed | Why | What replaced it |
|---------|-----|------------------|
| `secondDigit == '2'` domestic spoof | Blocked 022-029 = Tohoku + inland Kanto (8 prefectural police HQs) | `startsWith("020")` only |
| 0110-tail heuristic for police spoof | False positives; missed real impersonation numbers | 47-entry HQ directory exact-match |
| Inline notification builders in `SilentBlockerService` | 240-LOC service, mixed concerns | `WarningNotifier` 92 LOC |
| `normalize()` defined twice | Silent divergence risk | `PhoneNumbers.normalize()` |
| 5× `!!` operators | SharedPrefs crash on corruption | `.orEmpty()` |
| 2× hardcoded strings | i18n gap | `R.string.*` |
| `isPoliceHQ()` + `lookup()` double call | Two HashMap traversals on hot path | `Decision.warnPayload` |
| `updatePeriodMillis > 30min` on widget | Battery drain on older devices | 1800000ms (30min) – system-capped anyway |

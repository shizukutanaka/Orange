# Orange Specification

## Product Overview

**Orange** is a privacy-first call-screening application for Android that blocks unwanted calls locally without requiring any network access, contact upload, account creation, or advertising.

**Core Promise:** "Your phone stops ringing when it shouldn't."

### Non-goals (What Orange Does NOT Do)

Orange is defined by deliberate restraint. The product refuses:

1. **Network access** — No INTERNET permission. App runs entirely on-device. No telemetry, no crash reporting, no A/B testing, no sync.
2. **Contact reading** — No READ_CONTACTS permission. Caller ID matching uses a bundled business directory, not user's address book.
3. **Number upload** — Spam cache is stored locally in app-private SharedPreferences with salted SHA-256 hashing. Nothing is transmitted.
4. **Account system** — No sign-in, no email, no password, no OAuth, no device sync. App works immediately after granting CallScreening role.
5. **Monetization** — No ads, no subscriptions, no premium tier, no in-app purchases, no referral bounties.
6. **Silent rule updates** — Business directory, country-code tables, and scam-prefix rules are compiled at build time in the APK. Updates ship only through Play Store's signed update channel.
7. **Emergency override** — 110, 118, 119, 189, 171, 911, 112, 999, 000 and regional equivalents are hard-coded to always ring. No setting can disable this.

## Architecture

### Deployment Model

- **Platform:** Android 7.0+ (API 24+), target API 35
- **Role:** CallScreeningService (requires explicit user permission via RoleManager; adaptive-icon fallback PNGs ship for API 24-25 devices below the adaptive-icon floor)
- **Storage:** app-private SharedPreferences (no READ/WRITE_EXTERNAL_STORAGE)
- **APK size ceiling:** ≤1 MiB (with CI gate `check_apk_size.sh`)
- **Permissions requested:** `POST_NOTIFICATIONS` and `RECEIVE_BOOT_COMPLETED` only — no `READ_CALL_LOG` (never needed: `Call.Details` from the granted `ROLE_CALL_SCREENING` role supplies everything the screener reads)

### Decision Engine (16-Layer Decision Tree)

When a call arrives, Orange evaluates in strict order; first match wins:

| Layer | Name | Decision | Output |
|-------|------|----------|--------|
| 1 | Emergency bypass | 110, 118, 119, 189, 171, 911, 112, 999, 000 | **ALLOW** (ring) |
| 2 | Pause tile | User activated 1-hour pause in Quick Settings | **ALLOW** (ring) |
| 3 | Withheld caller ID | Caller ID is anonymous/restricted/unavailable | **SILENCE** |
| 4 | Outbound-known | Caller number dialed by user before | **ALLOW** (ring) |
| 5 | Business bundle | Number in `BusinessDirectoryBundle` (business_directory.csv) | **ALLOW** (ring) |
| 6 | Spam cache | Number in device spam cache (salted SHA-256 hash) | **SILENCE** |
| 7 | Wangiri callback | Same number short-rang (<15s) in last 6 hours | **SILENCE** |
| 8 | Domestic spoofing | JP number violates MIC numbering plan | **SILENCE** |
| 9 | Police HQ impersonation | Number matches one of 54 police numbers (47 prefectural HQ + NPA + 6 Tokyo-area stations); STIR/SHAKEN failed escalates to 🚨 high alert | **ALLOW** (ring) + warning |
| 9b | Tax agency impersonation | Number matches the National Tax Agency; same warn-but-ring treatment and STIR/SHAKEN escalation as Layer 9 | **ALLOW** (ring) + warning |
| 10 | STIR/SHAKEN failed | Carrier verification says caller ID not authentic. Dormant on JP carriers as of 2026-07 (STIR/SHAKEN not yet deployed domestically) — kept as zero-cost forward-insurance | **SILENCE** |
| 11 | International premium | +800/+979/+882/+883 or Caribbean NANP (23 area codes) | **SILENCE** |
| 12 | Foreign elevated-risk | 20 country codes for JP users: +675/+7/+86/+44/+39/+212/+234/+63 plus IRSF/Wangiri corridors +371/+370/+239/+232/+252/+53/+682/+676/+678/+855/+856/+95. +1 deliberately excluded (highest-volume *legitimate* corridor; still silenced by Layer 13) | **SILENCE** |
| 13 | Foreign generic | Any international not in outbound history | **SILENCE** |
| 14 | DND honor | Device Do Not Disturb active + unknown domestic | **SILENCE** |
| 15 | High-risk hours | Unknown domestic mobile (090/080/070/060) Mon–Fri 09–12, 13–16, or 18–20 JST | **ALLOW** (ring) + soft warning |
| 16 | Default | All else | **ALLOW** (ring) |

### Core Components

#### Decision Engine (`CallDecision.kt`)
- Pure Kotlin function `decide(ctx: CallContext, state: CallState): Decision` — no Android dependencies, no `System.currentTimeMillis()` reads (clock is injected via `CallContext.nowMillis`)
- Returns `Decision` with `verdict: Verdict` (`RING`/`SILENCE`), `reason: BlockReason?`, `warning: WarnReason?`, `warnPayload: String?`
- Exhaustive `when` (`isCacheableSilence()`) enforces compiler-verified coverage of all `BlockReason` cases
- Variant expansion via `phoneVariants(number, callingCode)` — handles domestic↔E.164 equivalence for JP (0…) ↔ (+81…) forms, including carrier-mangled "+810…" variants

#### Call Screening Service (`SilentBlockerService.kt`)
- Implements Android's `CallScreeningService` API (`ROLE_CALL_SCREENING`, API 29+; on API 24-28 the role is unavailable and Orange falls through without screening)
- Intercepts all incoming calls before ring reaches user
- Adapts `Call.Details` into `CallContext`/`CallState` and calls the pure `decide()` function
- Handles the resulting `Decision`: silence, disconnect, notification dispatch, plus repeat-caller/Wangiri/OutboundGuard side-effect recording — all gated by `PauseTile.isPaused()` before any `SILENCE` is acted on
- Expands phone variants when forgetting Wangiri short-rings

#### Outbound Adapter (`SilentBlockerService` + `CallStateObserver`)
- Records dialed numbers in "outbound" cache for Layer 4 (Outbound-known)
- Detects short-rings (<15s) for Wangiri detection
- Warns user if calling back a recently-blocked number

#### Spam Cache (`SpamCache.kt`, `SaltVault.kt`)
- Stores blocked-call hashes with salted SHA-256
- Per-install salt encrypted via Android Keystore AES-GCM (non-exportable key)
- Graceful degradation if Keystore is unavailable (falls back to plaintext salt, still hashed)

#### Business Directory (`BusinessDirectoryBundle.kt`, `business_directory.csv`)
- 74 verified legitimate business/government numbers, deliberately NOT including police or tax-agency numbers (those live in `PoliceStationDirectory.kt`/`TaxAgencyDirectory.kt` on the warn-but-ring Layer 9/9b instead — a police/tax number here would be silently trusted, exactly what a spoofer relies on)
- E.164 format (+81…) with CSV key-uniqueness validated by CI (`BusinessDirectoryBundleTest.shipped_csv_never_bundles_a_warn_directory_number` also asserts no Layer-9/9b number ever appears here)
- Lookups expanded via `phoneVariants()` to handle both domestic (0…) and E.164 forms
- Loaded once at app startup via `EngineWarmup` (a zero-authority `ContentProvider` that runs before any `Service.bind()`), parsed on-demand per call thereafter

#### Repeat Caller Tracker (`RepeatCallerTracker.kt`)
- Tracks consecutive calls from same number within a 60-minute window
- Blocks on 4th call (N_THRESHOLD=3: `calls.size > 3` → block)
- Space-separated format: "number:timestamp|number:timestamp|…"
- Clear operation filters malformed entries without ':' delimiter
- Silencing is gated on Pause status ("Pause means every call rings" — see `decide()`'s Layer 2 KDoc); recording still happens while paused so velocity tracking has no gap once the pause window ends

#### Wangiri Tracker (`WangiriTracker.kt`)
- Records numbers with short-ring (<15s, covering both classic 1-ring Wangiri and "Wangiri 2.0" brief-connect variants) in last 6-hour window
- Blocks callbacks from same number within window
- Variant expansion on forget operation

#### Domestic Spoof Detector (`DomesticSpoofDetector.kt`)
- Checks for structurally impossible JP numbers
- Rules: 020 reserved (M2M/pager), wrong digit lengths for mobile/freephone/IP, 8+ repeating digits, 00x intl-access, d[1]=='0' double-zero form
- E.164 conversion via `to_domestic()` handles "+810…" leading-zero variant

#### Police Station Directory (`PoliceStationDirectory.kt`)
- Hard-coded 54 police numbers: 47 prefectural HQ (Kyushu to Hokkaido) + National Police Agency + 6 verified Tokyo-area stations
- Accepts both domestic (0…) and E.164 (+81…) forms including "+810…" mangled variant
- Used for Layer 9 police-impersonation detection + high-severity alert on STIR/SHAKEN failure

#### Tax Agency Directory (`TaxAgencyDirectory.kt`)
- Same warn-but-ring pattern as `PoliceStationDirectory`, for the National Tax Agency's number (targeted by 還付金詐欺/税金未納詐欺)
- Layer 9b, checked immediately after Layer 9 via the shared `govAgencyImpersonationWarning()` helper

#### Phone Numbers Utility (`PhoneNumbers.kt`)
- `normalize(raw)` — strips to [0-9+], folds full-width (U+FF0B, U+FF10–FF19) to ASCII
- `mask(n)` — shows last 4 digits for block history display
- Single source of truth for normalization (prevents drift across codebase)

#### Business Directory Shortcodes
- Emergency hotlines (110, 188, 119, 118) + post-call advisory numbers
- Post-call advisor fires 30s after call ends if caller unknown, rate-limited to once per 24h per number

#### Family Callback (`FamilyCallback.kt`)
- Stores up to 3 trusted family numbers in SharedPreferences
- Numbers auto-normalized via `PhoneNumbers.normalize()`
- Always ring, no blocking or warning

#### Block History Store (`BlockHistoryStore.kt`)
- Stores last 50 blocked calls with timestamp and reason
- Numbers masked to last 4 digits for privacy
- Entries auto-delete after 30 days via timestamp check on each read
- User can tap "Allow" to recover false positive — clears spam cache entry + removes history entry

#### User Interface
- **Main screen (`OnboardingActivity`):** Tap white circle to grant CallScreening role (one-time setup); skipped entirely if the role is already held
- **Family number registration:** 3 slots, manual entry on first setup (no READ_CONTACTS)
- **Widget (`OrangeWidget`):** Shows cumulative silenced-call count; tap opens block history (or re-onboarding if the role was lost) — never a menu
- **Quick Settings tiles:** "Pause" (1-hour silence exemption, capped by `MAX_PAUSE_MS` against backward clock jumps), "家族に連絡" (tap to call first family number)
- **Block history (`HistoryActivity`):** Last 50 blocked calls (masked to last 4 digits), one-tap "Allow" recovery for every `BlockReason` except `WITHHELD_NUMBER`/`DOMESTIC_SPOOF` (where an Allow would be meaningless or misleading), 30-day auto-delete
- **Settings (`SettingsActivity`):** Family number registration, a manual "Block a number" action (`ManualBlock`) for scam numbers learned about some other way, and an "Allowed numbers" list (`AllowSuffixStore`) to manage false-positive recoveries — three narrow, purpose-built escape hatches, not general configuration

#### Internationalization
- Strings in: Japanese (ja), English (en), Simplified Chinese (zh), Korean (ko) — all four locale files hold an identical key set, verified on every locale-touching change
- Play Store listing metadata in fastlane/metadata/
- Threat model assumes JP user (Layers 9-9b, 12, 15); foreign users should only rely on Layers 1-11, 13-14, 16

### Testing

- **Unit tests:** ~530 Kotlin tests across 33 files in `app/src/test/`
  - Pure decision-engine tests (`CallDecisionTest`, `DecisionPriorityTest`, `EngineInvariantTest`)
  - Component tests (`PoliceStationDirectoryTest`, `TaxAgencyDirectoryTest`, `CaribbeanPremiumNANPTest`, `RepeatCallerTracker`/`WangiriTracker` coverage in `ComponentTests`, `PhoneVariantsTest`, `PauseTileTest`, etc.)
  - No Android emulator or device required — files exercising `Context`-dependent Android adapter classes (`SilentBlockerService`, the Activities, the Widget) are deliberately outside this suite; Robolectric is intentionally not used
- **Static checks:** 10/10 CI gates
  - `check_privacy.sh` — forbid network keywords
  - `check_comprehensive.sh` — forbid wildcard permissions, require ADRs, validate CSV keys, count @Test annotations (ensuring tests didn't regress)
  - `check_apk_size.sh` — enforce ≤1 MiB release APK
  - `check_no_network.sh` — reject any code that imports network libs
  - `check_oracle_test.py` — run Python oracle reference implementation, assert both agree on edge cases

## Design Principles

### Rams (Requirements As Motivating Statements)

These guide every decision in Orange:

1. **Silence** — block calls user doesn't want to hear
2. **Speed** — on-device, no network latency, <100ms per call decision
3. **Safety** — emergency numbers always ring; user retains control via Pause tile
4. **Subtraction** — every feature must remove bloat from the product (see DESIGN_NOTES.md)
5. **Simplicity** — user-visible UI is three screens: grant role, set family, view history
6. **Honesty** — every claim is testable; threat model published in advance (THREAT_MODEL.md)
7. **Privacy** — no contacts, no network, no account, no upload; phone stays private

### Known Limitations (from `HONESTY_ADDENDUM.md`)

Orange does NOT catch:

- Structurally valid non-STIR/SHAKEN spoofs (spoofed caller ID that passes length/prefix checks)
- Delayed Wangiri callbacks (after 6-hour window)
- International robocalls to unknowns (partially blocked by Layer 13 foreign-generic)
- Vishing calls that are technically legitimate numbers (only voice content would expose them)
- Hiya-style database lookups (no network, no contact graph)

These are not bugs; they are accepted limitations of an on-device, contact-free approach.

## Security Invariants

### Code-level

- `normalize()` is single source of truth — never re-implement digit filtering
- `phoneVariants()` is single source of truth — all variant expansion calls this function
- `isCacheableSilence()` is exhaustive `when` — every new `BlockReason` must declare if it's cached
- All blocking decisions are reversible — user can "Allow" from block history to undo false positives
- Emergency numbers hard-coded, not read from CSV or prefs

### Build-level

- APK size always ≤1 MiB
- No network code paths (strictly enforced by `check_no_network.sh`)
- All permissions required and listed in AndroidManifest (no sneaky permissions)
- Business directory CSV validated for duplicate keys (ci gate)
- All ADRs documented and referenced in comments

### Deployment-level

- App works immediately after role grant; no server handshake
- Updates flow through Play Store only (signed APK)
- F-Droid builds are reproducible (deterministic; diff-friendly)

## File Structure

33 files under `app/src/main/java/com/orange/apple/` (~4900 LOC). Representative subset — see `DEVELOPING.md`'s "Key source files" tables for the complete, categorized list:

```
├── CallDecision.kt          ← Pure decision engine (16 layers, decide())
├── SilentBlockerService.kt  ← CallScreeningService entry point (adapter)
├── CallStateObserver.kt     ← Outbound adapter + Wangiri short-ring detector
├── DomesticSpoofDetector.kt
├── PoliceStationDirectory.kt ← 54 police numbers (Layer 9)
├── TaxAgencyDirectory.kt     ← National Tax Agency number (Layer 9b)
├── BusinessDirectoryBundle.kt ← CSV parser + 74 businesses (Layer 5)
├── CaribbeanPremiumNANP.kt   ← 23 premium NANP area codes (Layer 11)
├── ScamPrefixSeed.kt         ← 20 elevated-risk country codes (Layer 12)
├── PhoneNumbers.kt           ← normalize() + mask() (single source of truth)
├── (phoneVariants() lives in CallDecision.kt — single source of truth)
├── SpamCache.kt              ← Hashed blocklist, FIFO, 10,000 entries
├── SaltVault.kt              ← AndroidKeystore encryption for the salt
├── BlockHistoryStore.kt      ← Last 50 blocked, 30-day auto-delete
├── WangiriTracker.kt         ← Short-ring callback detection (15s/6h window)
├── RepeatCallerTracker.kt    ← Consecutive-call tracking (60min, N_THRESHOLD=3)
├── FamilyCallback.kt         ← 3 trusted family numbers
├── OutboundGuard.kt          ← 24h LRU: warns on callback to a flagged number
├── ManualBlock.kt            ← Settings "Block a number" action
├── AllowSuffixStore.kt       ← Settings "Allowed numbers" list
├── PostCallAdvisor.kt        ← 30s post-call notification + hotlines
├── WarningNotifier.kt        ← Police/tax/high-risk-hour/outbound warnings
├── EmergencyWhitelist.kt     ← 110, 119, 118, etc. (Layer 1)
├── HistoryActivity.kt        ← Block history UI
└── SettingsActivity.kt       ← Family/manual-block/allowed-numbers UI

app/src/main/res/
├── layout/widget_orange.xml  ← Home widget (silenced count + caption)
├── values*/strings.xml       ← en/ja/zh/ko, key-set-identical
└── mipmap-*/ic_launcher.png  ← Legacy launcher icon fallback (API 24-25)

docs/adr/
├── 001-pause-before-withheld.md     ← Layer 2 must precede Layer 3
├── 002-020-only-not-02x.md          ← 020 spoof detection scope
├── 003-warn-payload-single-lookup.md ← PostCallAdvisor fires once/24h per number
├── 004-special-prefix-digit-lengths.md ← Mobile/freephone/IP length rules
├── 005-no-manual-callscreening-start.md ← Role grant only via RoleManager
├── 006-hashed-spam-cache.md         ← Salted SHA-256 + Android Keystore
├── 007-fullwidth-normalization.md   ← Full-width character folding (IME artifact)
├── 008-masked-block-history.md      ← Last 4 digits only for privacy
├── 009-fix-foreign-generic-coverage.md ← International L13 expansion
├── 010-e164-mobile-layer15.md       ← E.164 international mobile detection
├── 011-police-before-stir-shaken.md ← L9 police must precede L10 STIR/SHAKEN
└── 012-domestic-e164-variant-expansion.md ← phoneVariants() canonical pattern
```

## Recent Fixes

See `CHANGELOG.md` for the maintained, chronological record of every fix —
this section previously duplicated a snapshot of one past session's fixes
and, labeled "This Session," became misleading as soon as later sessions
added more. `docs/FEATURE_AUDIT.md` additionally tracks what's fixed vs.
still awaiting a product decision, for a session picking up this branch cold.

## Strengths

✅ **Privacy** — No network, no contacts, no upload; fully on-device

✅ **Simplicity** — Three user screens; no settings bloat; widget shows status at a glance

✅ **Safety** — Emergency numbers hard-coded; user retains control via Pause tile; all false positives reversible from history

✅ **Speed** — Pure Kotlin decision engine, no JNI or network latency; <100ms per call

✅ **Honesty** — Threat model published; limitations acknowledged in HONESTY_ADDENDUM; all claims testable

✅ **Subtraction** — Every decision principle in Rams asks "what can we remove?" (see DESIGN_NOTES.md)

✅ **Testing** — ~530 unit tests, 10/10 CI gates, Python oracle reference implementation

✅ **Variant handling** — phoneVariants() handles carrier inconsistencies (domestic 0… ↔ E.164 +81… ↔ mangled +810…)

## Weaknesses / Known Issues

⚠️ **Structurally valid non-STIR/SHAKEN spoofs not caught** — Can only detect spoofs that violate MIC digit-length rules or fail STIR/SHAKEN verification. A spoofed "0335814321" that matches valid format will ring (by design—false negatives preferred over false positives).

⚠️ **No contact-based matching** — Foreign callers unknown to user will be silenced (Layer 13), even if they call frequently. By design; no READ_CONTACTS to enable smarter matching.

⚠️ **Wangiri 6-hour window is fixed** — Delayed callbacks after 6 hours ring. Could be made adaptive, but adds complexity. Current window covers ~99% of attack patterns.

⚠️ **Repeat caller threshold at 4 calls** — User must endure 3 calls before block. Could lower to 2, but risks false positives (wrong numbers trying multiple times). N_THRESHOLD=3 balances these risks.

⚠️ **Business directory CSV not auto-updated** — Requires app update for new business numbers. Manual curation keeps it small (<50KB) but static. Could ship with two CSVs (core + optional download), but violates "no network" promise.

⚠️ **APK size ceiling of 1 MiB is tight** — Limits business directory growth, string translations, and future features. Compression (ProGuard, etc.) is at max. Further reduction requires dropping content.

⚠️ **DND integration only checks interrupt-filter level** — Reads a single integer, doesn't parse priority contacts. Safe but blind to complex DND rules.

⚠️ **Post-call advisor notification rate-limited to 1/24h per number** — If user calls same number multiple times, only the first gets advisory. Could per-session rate-limit instead, but persisted state would be needed.

## Improvement Opportunities

### High Impact

1. **Proactive research-based domain list** — Currently only app-bundled directory. Could add minimal (~5KB) known-scam domain patterns (e.g., "0120-*-*-110" scam prefix signature). Requires careful whitelisting to avoid false positives.

2. **Smarter Wangiri window** — Current 6-hour fixed. Could make adaptive: if call silenced by Wangiri, extend to next 12 hours on attempt 2. Reduces false negatives without hurting false positives.

3. **Frequency-based heuristic for unknown calls** — If a number has called and been blocked 3+ times in a week, auto-block on next ring without waiting for 4th call. Requires per-week window (vs. current 60-minute `RepeatCallerTracker` window). Test for false positives in block history.

4. **Better Wangiri detection for multi-line numbers** — Some scammers use "0120-*-*-111" and "0120-*-*-112" variants from same PBX. Current single-number tracking misses the pattern. Could cache prefix+PBX signature instead.

### Medium Impact

5. **Intelligent DND override for priority contacts** — Instead of reading only interrupt-filter level, parse DND priority-contact list and allow those through (requires READ_DO_NOT_DISTURB permission addition). User must be informed of new permission.

6. **Repeat-caller threshold tuning** — Currently N_THRESHOLD=3 (block on 4th call). Could make configurable (2–5) in a hidden settings screen. Must test for false positive cases first.

7. ~~**Masking improvements for block history**~~ — **Done.** `HistoryActivity` shows "****1234 · 3×" (repeat-count badge) computed from `BlockHistoryStore` entries grouped by masked number.

8. ~~**Reason badge in block history**~~ — **Done.** Every entry shows its `BlockReason` as localized text (`reason_spam_cache`, `reason_wangiri`, etc.), with an extra explanatory sub-label for `DOMESTIC_SPOOF`, `FOREIGN_ELEVATED`/`FOREIGN_GENERIC`, `DND_HONOR`, and `MANUAL_BLOCK`.

### Lower Impact (Design Risk)

9. **Per-operator scam rules** — NTT Docomo, Softbank, au use different fraud-detection criteria. Could bundle per-operator hints, but adds complexity and per-operator testing burden.

10. **Machine-learning based reputation** — Fine-grained number scoring (0–100) based on frequency, time-of-day, duration. Requires significant testing and introduces black-box decision-making (violates Honesty ram).

## Next Review Steps

1. Read `RESEARCH_BASIS.md` — verify academic citations match decision engine layers
2. Audit gradle build files for unintended dependencies or size leaks
3. Check `DESIGN_NOTES.md` against current code to ensure documented cuts stayed cut
4. Review `HONESTY_ADDENDUM.md` against threat model for consistency
5. Test full user journey: install → grant role → set family → receive calls → view history
6. Measure actual call-decision latency under load
7. Fuzz `PhoneNumbers.normalize()` with various Unicode/symbol inputs
8. Stress-test BlockHistoryStore 30-day auto-delete with large timestamps

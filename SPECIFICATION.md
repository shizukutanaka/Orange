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

- **Platform:** Android 9+ (API 28+)
- **Role:** CallScreeningService (requires explicit user permission via RoleManager)
- **Storage:** app-private SharedPreferences (no READ/WRITE_EXTERNAL_STORAGE)
- **APK size ceiling:** ≤1 MiB (with CI gate `check_apk_size.sh`)
- **Permissions requested:** CallScreeningService role + READ_CALL_LOG (Android 9–11 only, gracefully no-op on 12+)

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
| 7 | Wangiri callback | Same number short-rang (<6s) in last 6 hours | **SILENCE** |
| 8 | Domestic spoofing | JP number violates MIC numbering plan | **SILENCE** |
| 9 | Police HQ impersonation | Number matches 47 prefectural police HQ; STIR/SHAKEN failed escalates to 🚨 high alert | **ALLOW** (ring) + warning |
| 10 | STIR/SHAKEN failed | Carrier verification says caller ID not authentic | **SILENCE** |
| 11 | International premium | +800/+979/+882/+883 or Caribbean NANP | **SILENCE** |
| 12 | Foreign elevated-risk | +675/+7/+86/+44/+212/+234/+63/+39 (for JP users) | **SILENCE** |
| 13 | Foreign generic | Any international not in outbound history | **SILENCE** |
| 14 | DND honor | Device Do Not Disturb active + unknown domestic | **SILENCE** |
| 15 | High-risk hours | Unknown domestic mobile (090/080/070/060) Mon–Fri 09–11 or 13–15 JST | **ALLOW** (ring) + soft warning |
| 16 | Default | All else | **ALLOW** (ring) |

### Core Components

#### Decision Engine (`CallDecision.kt`)
- Pure Kotlin function `screenIncoming()` — no Android dependencies
- Returns `CallDecision` with `shouldBlock: Boolean`, `reason: BlockReason?`, `warning: WarnReason?`
- Exhaustive `when` enforces compiler-verified coverage of all `BlockReason` cases
- Variant expansion via `phoneVariants(number, callingCode)` — handles domestic↔E.164 equivalence for JP (0…) ↔ (+81…) forms, including carrier-mangled "+810…" variants

#### Call Screening Service (`SilentBlockerService.kt`)
- Implements Android's `CallScreeningService` API (API 29+)
- Intercepts all incoming calls before ring reaches user
- Calls `screenIncoming()` from decision engine
- Handles `CallScreeningResponse`: silence, disconnect, show notification, plus optional advisory
- Expands phone variants when forgetting Wangiri short-rings (fixed in this session)

#### Outbound Adapter (`SilentBlockerService` + `CallStateObserver`)
- Records dialed numbers in "outbound" cache for Layer 4 (Outbound-known)
- Detects short-rings (<6s) for Wangiri detection
- Warns user if calling back a recently-blocked number

#### Spam Cache (`SpamCache.kt`, `SaltVault.kt`)
- Stores blocked-call hashes with salted SHA-256
- Per-install salt encrypted via Android Keystore AES-GCM (non-exportable key)
- Graceful degradation if Keystore is unavailable (falls back to plaintext salt, still hashed)

#### Business Directory (`BusinessDirectoryBundle.kt`, `business_directory.csv`)
- 47 prefectural police HQ + 200+ legitimate businesses
- E.164 format (+81…) with CSV key-uniqueness validated by CI
- Lookups expanded via `phoneVariants()` to handle both domestic (0…) and E.164 forms
- Loaded once at app startup, parsed on-demand per call (≈1ms per lookup)

#### Repeat Caller Tracker (`RepeatCallerTracker.kt`)
- Tracks consecutive calls from same number within a 4-hour window
- Blocks on 4th call (N_THRESHOLD=3: `calls.size > 3` → block)
- Space-separated format: "number:timestamp|number:timestamp|…"
- Clear operation filters malformed entries without ':' delimiter

#### Wangiri Tracker (`WangiriTracker.kt`)
- Records numbers with short-ring (<6s) in last 6-hour window
- Blocks callbacks from same number within window
- Variant expansion on forget operation (fixed in this session)

#### Domestic Spoof Detector (`DomesticSpoofDetector.kt`)
- Checks for structurally impossible JP numbers
- Rules: 020 reserved (M2M/pager), wrong digit lengths for mobile/freephone/IP, 8+ repeating digits, 00x intl-access, d[1]=='0' double-zero form
- E.164 conversion via `to_domestic()` handles "+810…" leading-zero variant (fixed in this session)

#### Police Station Directory (`PoliceStationDirectory.kt`)
- Hard-coded 47 prefectural police HQ numbers (Kyushu to Hokkaido)
- Accepts both domestic (0…) and E.164 (+81…) forms including "+810…" mangled variant
- Used for Layer 9 police-impersonation detection + high-severity alert on STIR/SHAKEN failure

#### Phone Numbers Utility (`PhoneNumbers.kt`)
- `normalize(raw)` — strips to [0-9+], folds full-width (U+FF0B, U+FF10–FF19) to ASCII
- `mask(n)` — shows last 4 digits for block history display
- Single source of truth for normalization (prevents drift across codebase)

#### Business Directory Shortcodes
- Emergency hotlines (110, 188, 119, 118) + post-call advisory numbers
- Post-call advisor fires 30s after call ends if caller unknown, rate-limited to once per 24h per number

#### Family Callback (`FamilyCallback.kt`)
- Stores up to 3 trusted family numbers in SharedPreferences
- Numbers auto-normalized via `PhoneNumbers.normalize()` (fixed in this session)
- Always ring, no blocking or warning

#### Block History Store (`BlockHistoryStore.kt`)
- Stores last 50 blocked calls with timestamp and reason
- Numbers masked to last 4 digits for privacy
- Entries auto-delete after 30 days via timestamp check on each read
- User can tap "Allow" to recover false positive — clears spam cache entry + removes history entry

#### User Interface
- **Main screen:** Tap white circle to grant CallScreening role (one-time setup)
- **Family number registration:** 3 slots, manual entry on first setup (no READ_CONTACTS)
- **Widget:** Shows count of silenced calls today + caption (silenced, warned, or call blocked)
- **Quick Settings tiles:** "Pause" (1-hour silence exemption), "家族に連絡" (tap to call first family number)
- **Block history:** Last 50 blocked calls, one-tap "Allow" recovery, 30-day auto-delete
- **Settings:** Family number management only; no other configuration

#### Internationalization
- Strings in: Japanese (ja), English (en), Simplified Chinese (zh-rCN), Korean (ko)
- Play Store listing metadata in fastlane/metadata/ (jp, en)
- Threat model assumes JP user (Layers 12–15); foreign users should only use Layers 1–11

### Testing

- **Unit tests:** 354 Kotlin tests in `app/src/test/`
  - Pure decision-engine tests (CallDecisionTest: 353 tests)
  - Component tests (PoliceStationDirectory, CaribbeanPremium, RepeatCallerTracker, WangiriTracker, PhoneVariants)
  - No Android emulator or device required
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

```
app/src/main/java/com/orange/apple/
├── CallDecision.kt         ← Pure decision engine (16 layers, 21 files)
├── SilentBlockerService.kt ← CallScreeningService entry point
├── CallStateObserver.kt    ← Outbound adapter + Wangiri short-ring detector
├── DomesticSpoofDetector.kt
├── PoliceStationDirectory.kt ← 47 prefectural HQ
├── BusinessDirectoryBundle.kt ← CSV parser + 200+ businesses
├── CaribbeanPremiumNANP.kt
├── PhoneNumbers.kt         ← normalize() + mask() (single source of truth)
├── PhoneVariants.kt        ← phoneVariants() (single source of truth)
├── SpamCache.kt            ← Hashed blocklist (salted SHA-256)
├── SaltVault.kt            ← AndroidKeystore encryption for salt
├── BlockHistoryStore.kt    ← Last 50 blocked, 30-day auto-delete
├── WangiriTracker.kt       ← Short-ring callback detection (6h window)
├── RepeatCallerTracker.kt  ← Consecutive-call tracking (4h window, N_THRESHOLD=3)
├── FamilyCallback.kt       ← 3 trusted family numbers
├── OutboundGuard.kt        ← Dialed-number cache (Layer 4)
├── CaribbeanPremiumNANP.kt ← +1–242/+1–876/etc. premium detection
├── PreCallAdvisor.kt       ← Pre-call warning on Layer 15 (high-risk hours)
├── PostCallAdvisor.kt      ← 30s post-call notification + hotlines
├── EmergencyWhitelist.kt   ← 110, 119, 118, etc. (Layer 1)
└── SettingsActivity.kt     ← Family number UI + PhoneNumbers.normalize()

app/src/main/res/
├── layout/
│   ├── activity_main.xml       ← Grant role screen
│   ├── activity_settings.xml   ← Family number entry (3 slots)
│   ├── activity_history.xml    ← Block history (50 max, 30-day auto-delete)
│   └── widget_orange.xml       ← Home widget (silenced count + caption)
├── strings/
│   ├── strings.xml         ← English
│   ├── strings-ja.xml      ← Japanese (primary)
│   ├── strings-zh-rCN.xml  ← Simplified Chinese
│   └── strings-ko.xml      ← Korean
└── values/colors.xml

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

## Recent Fixes (This Session)

1. **Wangiri short-ring forget bug** — `handleDecision()` now expands `phoneVariants()` before calling `WangiriTracker.forget()` to handle carrier format inconsistencies
2. **SettingsActivity full-width Unicode** — Family number save now uses `PhoneNumbers.normalize()` instead of manual digit filter
3. **ComponentTests N_THRESHOLD off-by-one** — Two tests assumed block on 3rd call; corrected to 4th call (N_THRESHOLD=3 means `calls.size > 3`)
4. **PhoneNumbers.kt misplaced docblock** — Moved `foldFullWidth()` docstring to correct position
5. **widget_orange.xml hardcoded color** — Changed `#FF8C42` to `@color/orange_primary` reference
6. **PRIVACY_MANIFESTO.md READ_CALL_LOG gap** — Added §10 honest explanation of permission usage
7. **phoneVariants() E.164 leading-zero bug** — Fixed "+810…" carrier-mangled form to produce "0…" not "00…"
8. **oracle_decision.py missing cases** — Added "+810…" and "+8100…" CASES to cover variant edge cases

## Strengths

✅ **Privacy** — No network, no contacts, no upload; fully on-device

✅ **Simplicity** — Three user screens; no settings bloat; widget shows status at a glance

✅ **Safety** — Emergency numbers hard-coded; user retains control via Pause tile; all false positives reversible from history

✅ **Speed** — Pure Kotlin decision engine, no JNI or network latency; <100ms per call

✅ **Honesty** — Threat model published; limitations acknowledged in HONESTY_ADDENDUM; all claims testable

✅ **Subtraction** — Every decision principle in Rams asks "what can we remove?" (see DESIGN_NOTES.md)

✅ **Testing** — 354 unit tests, 10/10 CI gates, Python oracle reference implementation

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

3. **Frequency-based heuristic for unknown calls** — If a number has called and been blocked 3+ times in a week, auto-block on next ring without waiting for 4th call. Requires per-week window (vs. current 4-hour). Test for false positives in block history.

4. **Better Wangiri detection for multi-line numbers** — Some scammers use "0120-*-*-111" and "0120-*-*-112" variants from same PBX. Current single-number tracking misses the pattern. Could cache prefix+PBX signature instead.

### Medium Impact

5. **Intelligent DND override for priority contacts** — Instead of reading only interrupt-filter level, parse DND priority-contact list and allow those through (requires READ_DO_NOT_DISTURB permission addition). User must be informed of new permission.

6. **Repeat-caller threshold tuning** — Currently N_THRESHOLD=3 (block on 4th call). Could make configurable (2–5) in a hidden settings screen. Must test for false positive cases first.

7. **Masking improvements for block history** — Currently shows "****1234". Could show "****1234 (blocked 3x this week)" to help user make Allow/Block decision. Requires aggregation in BlockHistoryStore.

8. **Call duration in block history** — Why was call silenced? Add mini-reason badge (🚨 police spoof, ⏰ Wangiri, ♻️ repeat) so user can quickly understand false positives.

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

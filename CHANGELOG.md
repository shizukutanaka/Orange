# Changelog

All notable changes to Orange will be documented in this file.
Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versioning: [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased] — v1.5 (patch)

### Fixed
- **`HistoryActivity` identity comparison** — filter used `!==` (object identity) on a `data class` Entry. After Compose recomposition, a new Entry instance with identical field values has a different reference, so the identity check silently failed to remove the tapped entry from the visible list even though SharedPreferences was already updated. Changed to `!=` (data class value equality).
- **`SpamCache` Keystore round-trip on every call** — `SaltVault.salt()` performs an Android Keystore decrypt (10–50 ms) on every `hash()` invocation. The salt is per-install and never mutates. Added `@Volatile cachedSalt` field; first call loads from Keystore, subsequent calls return cached value without entering the synchronized block.
- **`WarningNotifier` stale rate-limit key accumulation** — `highrisk_last_*` (24 h) and `outbound_warn_ts_*` (1 h) keys were never pruned, unlike `PostCallAdvisor`'s `postcall_last_*` keys. Each distinct scam caller that triggered a warning wrote one permanent SharedPreferences entry. Added `pruneStaleRateLimitKeys()` sweeping both prefixes in a single `prefs.all` pass, called at the start of each `show*Warning()` invocation.
- **Emergency numbers in `CallStateObserver.addToOutbound()`** — `SilentBlockerService.handleOutgoing()` was fixed in v1.4 to exclude emergency numbers from outbound-known, but `CallStateObserver` had the same path without the guard. A user dialling 110 via the system dialer (OFFHOOK without prior RINGING) would have "110" recorded in outbound-known via `CallStateObserver`, causing Layer 4 to bypass the Layer 9 police-impersonation warning on future spoofed-police calls.
- **`SpamCache` salt cache invalidation in tests** — the initial singleton-level `cachedSalt` cache caused `SpamCacheTest.salt_differs_across_installs` to fail because two `FakePrefs` instances (simulating distinct installs) would share the cached salt from the first invocation. Cache now keyed by the prefs sentinel string that `SaltVault` writes (`spam_salt` plaintext or `spam_salt_enc` ciphertext), ensuring each distinct FakePrefs instance forces its own SaltVault call. On a real device (single prefs instance per lifetime), the cache still hits every time after first initialization.
- **`BlockHistoryStore` TTL boundary off-by-one** — eviction used `nowMs - ts > TTL_MS` (`>`), keeping entries that are exactly 30 days old. Changed to `>=` so the 30-day boundary is treated as expired, matching the documented TTL claim.
- **`isUnknownDomesticMobile()` misses carrier-mangled E.164 `+810...`** — Layer 15 high-risk-hour warning checked `+8190/+8180/+8170/+8160` (standard E.164 JP mobile) but not `+81090/+81080...` (carrier-mangled form where the domestic leading zero is retained after `+81`). A call delivered as `"+8109012345678"` fell through Layer 15 silently. Fixed by adding a `"+810"` prefix branch that strips `+81` and checks the remainder for mobile prefixes. This mirrors the same carrier-mangled handling already in `phoneVariants()` and `DomesticSpoofDetector.toDomestic()`.
- **`RepeatCallerTracker.clear()` variant mismatch** — `CallStateObserver.onOffhook()` called `RepeatCallerTracker.clear()` with the exact-string number from the PHONE_STATE broadcast. The screening service (`Call.Details.handle`) can deliver the same caller in a different format (domestic vs E.164). Without variant expansion, the `clear()` missed the stored form, leaving the repeat-caller count alive and silencing the legitimate caller on their next call. Fixed by passing the home calling code from `TelephonyManager` to `onOffhook()` and clearing all `phoneVariants()` of the answered number.

### Fixed (continued)
- **`SpamCache` cross-format miss** — `SpamCache` hashes the exact number string. A call blocked in domestic form (`"09012345678"`) is cached under `hash("09012345678")`; the same caller's next attempt in E.164 (`"+819012345678"`) produces a different hash, so Layer-6 fast-path misses and all 15+ decision layers re-execute unnecessarily. Fixed in three coordinated places: (1) `screenIncoming()` now checks `variants.any { SpamCache.contains(p, it) }` so Layer 6 fires for any cached format; (2) `handleDecision()` stores all `phoneVariants()` in SpamCache on block; (3) `RestoreReceiver` removes all variants on Restore so the un-removed form cannot re-block a restored number.
- **`AllowSuffixStore.allow/revoke()` suffix extraction order** — both methods used `maskedNumber.takeLast(4).filter { it.isDigit() }` (wrong order). If the masked string ends in a non-digit character (e.g. `"****1234X"`), `takeLast(4)` produces `"234X"`, then `filter` yields only 3 digits → entry silently discarded. `isAllowed()` already used the correct order (`filter` then `takeLast`). Fixed by matching `isAllowed()`'s order in both `allow()` and `revoke()`.
- **`WarningNotifier` backward-clock guard missing** — `showHighRiskHourWarning()` and `showOutboundWarning()` used `now >= last && now - last < WINDOW` for rate-limiting. When `now < last` (clock regression), the condition fails and the notification fires through the rate-limit window. Fixed to `last > 0L && (now < last || now - last < WINDOW)` so backward clock suppresses instead of bypasses.
- **`PostCallAdvisor` backward-clock guard missing** — two independent backward-clock bugs: (1) `maybeShow()` rate-limit check `now >= lastShown && now - lastShown < WINDOW_MS` returns `false` when `now < lastShown`, allowing the notification to fire through the rate-limit window on backward clock; (2) `pruneStaleRateKeys()` subtracted `now - ts` without guarding `now >= ts` first — a future-dated timestamp produces a negative Long that wraps to a huge positive value exceeding `WINDOW_MS`, incorrectly pruning a still-valid rate-limit key. Fixed both: `maybeShow()` now uses `lastShown > 0L && (now < lastShown || now - lastShown < WINDOW_MS)` to suppress even on backward jump; `pruneStaleRateKeys()` adds `now >= (v as Long)` guard before subtraction.

### Added
- **Wangiri outbound-warning blind spot** — `handleOutgoing()` checked only `OutboundGuard` (numbers Orange had formally blocked/warned). If a user dials back a Wangiri bait number within the 6-hour short-ring window but before Orange blocks the second call, `OutboundGuard` has no entry and no warning fires. Fixed by also checking `WangiriTracker.snapshot()` in `handleOutgoing()`; any number in the short-ring tracker now triggers `showOutboundWarning()` on callback just as if Orange had already blocked it.
- **`FamilyCallback.normalizeAndValidate()` E.164 15-digit number incorrectly rejected** — the length check was `cleaned.length !in 3..15`, but `cleaned` includes the `+` prefix. An E.164 number with the maximum 15 digits ('+' + 15 digits = 16 chars) was rejected as too long. The comment said "Maximum: 15 digits (ITU-T E.164 maximum)" but the code enforced 15 **characters**. Fixed by counting only digits: `digitCount = cleaned.count { it.isDigit() }; if (digitCount !in 3..15) return null`. Also removed the now-redundant separate `< 3` digit check.
- **"Restore &amp; Call" action on block notifications** — When Orange blocks a legitimate call, the user had to: (1) tap Restore, (2) open the dialer, (3) manually retype the number. Now a second action button "Restore &amp; Call" (JP: 解除して折り返す) does all three in one tap. Uses `ACTION_DIAL` so the user confirms before the call is placed — no new permissions required. Available in both the 7-day trust-window notification and the post-trust silent notification.
- **`WarningNotifierRateLimitTest.kt`** — covers: highrisk 24 h dedup key canonicalisation (domestic and E.164 variants share one bucket), backward-clock guard, outbound 1 h window, distinct-number bucket isolation, stale-key pruning (expired vs fresh highrisk and outbound keys, unrelated-key safety, backward-clock prune guard).
- **`CallStateObserverTest` emergency-number regression** — verifies that 110 and 119 are never stored in outbound-known set.
- **`AllowSuffixStoreTest` extraction-order regression** — two new tests covering the `takeLast.filter` vs `filter.takeLast` divergence on masked strings with trailing non-digit characters.
- **`SpamCacheTest` format-sensitivity documentation** — test documenting that `SpamCache.contains()` is exact-string sensitive and that adding both variants solves the cross-format miss.

### Changed
- Test count: 388 → 423 (35 new tests across this session).

## [Unreleased] — v1.4 (patch)

### Fixed
- **`phoneVariants()` carrier-mangled E.164** — `"+810XXXXXXXXX"` (domestic leading zero not stripped before `+81` country prefix) was expanded to `"00XXXXXXXXX"` instead of the correct domestic form `"0XXXXXXXXX"`. Fixed by adding a leading-zero guard mirroring `PoliceStationDirectory.lookup()`. Layers affected: Wangiri callback detection, OutboundGuard variant matching, all other variant-expansion consumers.
- **Emergency numbers recorded in outbound history** — `handleOutgoing()` recorded all dialed numbers, including emergency numbers (110, 119, etc.). After calling 110 to verify a suspicious call, subsequent spoofed police calls would ring with no warning (Layer 4 outbound-known bypassed Layer 9 police warning). Fixed by checking `EmergencyWhitelist.isEmergency()` at the start of `handleOutgoing()`.
- **`AllowSuffixStore` no undo path** — when a user tapped "Allow" in block history there was no API to reverse the decision. Added `revoke()` method which removes a suffix from the ordered allow list. Ready for a future "Block again" UI path.
- **`PostCallAdvisor` unbounded prefs growth** — rate-limit keys `"postcall_last_*"` accumulated indefinitely (one key per unique unknown call answered for >30 s). Added `pruneStaleRateKeys()` which removes expired keys on each advisory opportunity.
- **`RESEARCH_BASIS.md` incomplete reference** — Truecaller 2024 statistic cited in repeat-caller mechanism table but missing from References section. Added formal citation.
- **`RESEARCH_BASIS.md` layer-mapping scope unclear** — the literature-grounded table covers only research-backed layers, not all 16 engine layers. Added explanatory note directing readers to README.md for the full 16-layer list.
- **`THREAT_MODEL.md` missing DND threat** — `DND_HONOR` (Layer 14) was documented in HONESTY_ADDENDUM and implemented but absent from the STRIDE D-axis enumeration. Added threat row explaining that emergency numbers bypass DND via Layer 1 hard-coded whitelist.

### Added
- **`SPECIFICATION.md`** — comprehensive product specification covering the 16-layer decision engine, all core components with file references, design principles (Rams), security invariants, file structure tree, known limitations, strengths, and improvement opportunities.
- **`EmergencyWhitelistTest.kt`** — 24 tests covering JP codes (110/119/118/189/171), international codes (911/112/999/000), E.164 variants, negative cases, and edge cases (special characters documented as design limitation).
- **`PostCallAdvisorTest.kt`** — 6 tests for rate-limit key pruning: stale removal, fresh key survival, prefix isolation, multi-key prune, empty prefs no-op, and backward clock-jump guard.
- **`AllowSuffixStore.revoke()` tests** — 4 new tests: removes suffix, no-op for absent, no-op for too-short input, re-allow after revoke.
- **`oracle_decision.py` cases** — 2 new CASES covering `+810…` (valid Tokyo via leading-zero normalisation) and `+8100…` (international-access prefix, impossible).

### Changed
- Test count: 296 → 388 (across this and the previous session's improvements).

## [Unreleased] — v1.3 (patch)

### Fixed
- **Wangiri forget variant mismatch** — `WangiriTracker.forget()` was called with the callback number's exact form. If the short-ring was stored in domestic form but the callback arrived in E.164 (or vice versa), `forget()` was a no-op and the stale entry lingered until the 6-hour window expired. Fixed via `phoneVariants()` expansion before calling `forget()`.
- **OutboundGuard variant mismatch** — `handleOutgoing()` called `OutboundGuard.wasRecentlyFlagged()` with exact-string match. A blocked call stored in domestic form was missed when the user dialled the same number in E.164 form, silently skipping the outbound-warning notification.
- **`business_directory.csv` duplicate E.164 keys** — 文部科学省 was stored under `+81352538111` (国土交通省's number). Corrected to `+81352534111`. 個人情報保護委員会 entry duplicated 内閣官房's number and has been commented out pending verification.
- **SettingsActivity full-width family number** — manual `filter { c.isDigit() }` kept full-width Unicode digits (e.g. ０９０) intact since Kotlin's `Char.isDigit()` covers the Unicode Nd category. The UI field showed full-width digits after save until next prefs reload. Fixed by using `PhoneNumbers.normalize()` which folds full-width to ASCII in one step.
- **HistoryActivity missing `semantics` import** — `Modifier.semantics {}` requires `import androidx.compose.ui.semantics.semantics`; only `contentDescription` was imported. Static CI does not compile Kotlin so this slipped through; would have crashed at runtime on any device.
- **NotificationRateLimiter no-op writes** — two early-return branches each called `prefs.edit {}` writing back values that were already in prefs. Removed both redundant writes.
- **PostCallAdvisor double-write** — `if (lastShown > 0L) prefs.edit { remove(rateKey) }` before `prefs.edit { putLong(rateKey, now) }` was a no-op. Removed.
- **`PhoneNumbers.mask()` duplication** — `mask()` was privately defined in both `BlockHistoryStore` and `TrustNotifier` with identical logic. Extracted to `PhoneNumbers.mask()` as a single source of truth.
- **`PhoneNumbers.kt` misplaced docblock** — `/** Map full-width... */` was positioned before `mask()` instead of before `foldFullWidth()`. Moved to its correct position.

### Added
- **ADR 012** (`docs/adr/012-domestic-e164-variant-expansion.md`) — documents the domestic↔E.164 variant-expansion pattern, the three bugs it caused, and the invariant for future developers.
- **CSV duplicate-key CI check** — `check_comprehensive.sh` section 9/10 now detects duplicate E.164 keys and malformed lines in `business_directory.csv`.
- **Layer 15 boundary-hour tests** — 7 new `CallDecisionTest` cases verify exact boundary hours (8, 9, 11, 12, 13, 15, 16) for the `isHighRiskHour()` predicate.
- **`PRIVACY_MANIFESTO.md` §10** — honest explanation of why `READ_CALL_LOG` is requested and what it is NOT used for.

### Changed
- `widget_orange.xml` background changed from hardcoded `#FF8C42` to `@color/orange_primary`.

## [Unreleased] — v1.2 (patch)

### Fixed
- **Critical: police HQ call silenced by layer ordering bug** — `decide()` ran STIR/SHAKEN check (former Layer 9) before the police-directory check (former Layer 10). A real officer calling from a police HQ phone that also had `verificationFailed=true` was silenced with `CARRIER_VERIFICATION_FAILED` instead of ringing with `POLICE_IMPERSONATION_HIGH`. Police numbers now checked first (new Layer 9) so STIR/SHAKEN failure escalates the warning rather than blocking the call.
- **Post-trust notification group summary missing** — `setGroup("orange_blocks")` on individual notifications requires an explicit `setGroupSummary(true)` notification for Android to visually collapse them. The summary was never posted; added `postGroupSummary()` which fires after each block and shows the live block count.
- **HistoryActivity "Allow" UX bug** — tapping Allow now removes the entry from `BlockHistoryStore` persistent storage via new `BlockHistoryStore.remove()` method. Previously the entry was removed from in-memory state only and reappeared after the next app launch.
- **E.164 mobile Layer 15** — `isUnknownDomesticMobile()` now covers E.164 JP mobile prefixes (`+8190/+8180/+8170/+8160`); Layer 15 high-risk-hour warning now fires for carriers that deliver numbers in E.164 form.
- **CHANGELOG** — corrected "7 automated checks" → "9 automated checks" to match current `check_comprehensive.sh`.

- **`CallStateObserver.onOffhook()` fallback** — when the OFFHOOK broadcast omits the phone number (carrier-dependent), fall back to `KEY_RING_NUMBER` stored during RINGING so `RepeatCallerTracker.clear()` always fires for answered calls.
- **`OrangeWidget` resource refs** — replaced `getIdentifier()` string-reflection with direct `R.layout` / `R.id` references; renames now caught at compile time.
- **`WarningNotifier` channel constant** — extracted inline `"orange_highrisk"` string to `CHANNEL_HIGHRISK` constant, consistent with other channel constants.
- **`FamilyCallbackTile` deprecated API** — `startActivityAndCollapse(Intent)` deprecated in API 34 (Android 14). Added Build.VERSION_CODES.UPSIDE_DOWN_CAKE guard: API 34+ uses `PendingIntent` form; older APIs use `@Suppress DEPRECATION` fallback.
- **`TrustNotifier` dead code** — removed unused `CHANNEL_DIGEST` constant (the channel is created and owned by `WeeklyDigest.kt`). Fixed misleading doccomment: "Day 8+ no notifications" and "digest stops after month 2" both contradicted the actual behavior (minimal drawer notifications post-trust; digest switches to monthly after 8 weeks, never stops).
- **`RepeatCallerTracker.clear()` silent no-op** — `clear()` called `snapshot(prefs, System.currentTimeMillis())` which applies the 60-minute window filter. Entries recorded with synthetic test timestamps were filtered out before the remove, leaving the prefs string unchanged. The unit test `clear resets counter` would fail as a result. Fixed to parse the raw storage string directly without window filtering.

### Added
- `BlockHistoryStore.remove(prefs, entry)` — synchronized removal of a single entry from block history.
- `R.string.notif_summary_text` — grouped block count string in all 4 locales (EN/JA/KO/ZH).
- `docs/adr/010-e164-mobile-layer15.md` — documents the E.164 mobile Layer 15 fix.
- 3 new unit tests: `BlockHistoryStore.remove()` coverage (2 cases) + `CallStateObserver` KEY_RING_NUMBER fallback.

## [1.1.0] — 2026-06-11

### Added
- **BlockHistoryStore** — last 50 blocked calls (masked ****1234, timestamp, reason) stored on-device with 30-day TTL. Full number never written to disk.
- **HistoryActivity** — review blocked calls and allow false positives with one tap.
- **AllowSuffixStore** — suffix-based allow-override for false-positive recovery from history.
- **SettingsActivity** — family number registration UI (3 slots) + link to block history.
- **Post-trust-window recovery notification** — after the 7-day window, a silent IMPORTANCE_MIN notification still fires per block with "Restore" + "Review history" actions.
- **Onboarding → Settings handoff** — first-launch opens SettingsActivity after role grant if no family numbers configured.
- **Business directory v1.1** — 29 → 95+ entries. Carriers, credit cards, net banks, logistics, transit, utilities, medical hotlines, Digital Agency.
- **AllowSuffix check in SilentBlockerService** — suffix-allowed numbers ring through unconditionally.
- **BlockHistory recording** — every SILENCE verdict persisted to BlockHistoryStore.
- **Test coverage** — BlockHistoryStoreTest (5 cases), AllowSuffixStoreTest (4 cases).

## [1.0.0] — 2026-06-06

### Added
- **15-point pure decision engine** (`CallDecision.kt`) — Android-free, fully testable.
- **Emergency bypass** — 110/119/118/911/112/999/000 hard-coded, untouchable.
- **Withheld number (非通知) blocking** — anonymous/hidden caller ID silenced.
- **Domestic JP spoof detector** — MIC numbering plan structure checks (02x, wrong digit count, 060 mobile, 0990 premium, 8+ repeating digits).
- **Wangiri 2.0 tracker** — 15-second threshold catches both 1-ring and recorded-message patterns; 6-hour callback window; 64-entry bounded LRU.
- **STIR/SHAKEN verification** — API 30+ `VERIFICATION_STATUS_FAILED` silences carrier-flagged spoofs.
- **Police HQ impersonation warning** — 47 prefectural police HQ numbers bundled; calls RING with heads-up warning + "家族に連絡" button. Replaces old 0110-tail heuristic.
- **Family callback system** — pre-set up to 3 family numbers (manual entry, no READ_CONTACTS); Quick Settings tile for one-tap dial; action button on every warning notification.
- **Outbound guard** — warns when user dials a number that was blocked/warned within 24 hours.
- **Caribbean NANP premium area codes** — 22 high-fraud NANP codes (+1-242/+1-876 etc.) silenced as premium-rate.
- **International premium rate blocking** — +800/+979/+882/+883 silenced.
- **Elevated-risk country corridors** — +675/+7/+86/+44/+212/+234/+63/+39 for JP users.
- **Bounded spam cache** — LRU eviction at 10,000 entries.
- **Notification rate limiter** — 5 per 5-minute window; burst-scam protection.
- **Weekly/monthly digest** — weekly for weeks 2-8, monthly thereafter. Never stops entirely.
- **Role-loss monitor** — widget shows "·" if screening role revoked; tap to re-grant.
- **Cold-start warmup** — ContentProvider pre-loads CSV directories before first call.
- **Warning notification system** (`WarningNotifier.kt`) — extracted from SilentBlockerService for Carmack-compliant separation of concerns.
- **Outbound call recording** — dual path: CallScreeningService DIRECTION_OUTGOING (API 29+) + CallStateObserver BroadcastReceiver fallback.
- **Business directory** — 29 entries (central ministries, couriers, mega-banks, NHK) from verified public sources.
- **Adaptive icon** — 3-layer (background/foreground/monochrome) + pre-O fallback.
- **4-locale support** — en/ja/zh/ko, full string parity (21 keys each).
- **TalkBack accessibility** — contentDescription on widget, Compose semantics on Onboarding.
- **Theme.Orange** — brand-color window/status/nav bars; no white-flash on launch.
- **CI pipeline** — Privacy guard + lint + test + R8 release build + APK size budget.
- **Release pipeline** — tag-push signed AAB/APK + optional Fastlane Play Console upload.
- **Documentation** — README, PRIVACY_MANIFESTO, HONESTY_ADDENDUM, THREAT_MODEL, COMPETITIVE_ANALYSIS, DESIGN_NOTES, STORE_LISTING, CONTRIBUTING, SECURITY, DEVELOPING, CHANGELOG.
- **Privacy policy** — HTML, GitHub Pages-ready.
- **Phone number normalization** (`PhoneNumbers.kt`) — single source of truth, eliminates duplicate `normalize()` across files.
- **F-Droid metadata** — `metadata/com.orange.apple.yml`.
- **Play Data Safety** — `docs/play_data_safety.json`.

### Security
- No `INTERNET` permission. No `READ_CONTACTS`. No backup sync.
- Emergency numbers unconditionally exempt, asserted by unit tests.
- All `!!` operators eliminated; `orEmpty()` pattern throughout.
- No hardcoded user-facing strings; all R.string-referenced.
- Privacy guard CI gate (`check_no_network.sh`) blocks any network code.
- APK size budget CI gate (`check_apk_size.sh`), ≤1 MiB ceiling.
- Comprehensive static analysis (`check_comprehensive.sh`): 9 automated checks.

### Changed
- Wangiri threshold: 6s → 15s (catches "Wangiri 2.0" recorded-message variant).
- Police detection: 0110-tail heuristic → 47-entry exact-match directory + RING with warning (never block police).
- Police warning escalation: when STIR/SHAKEN also reports FAILED, warning upgrades to 🚨 POLICE_IMPERSONATION_HIGH.
- Weekly digest: 8-week cutoff → indefinite monthly continuation.
- SilentBlockerService: 240 LOC → 161 LOC (notification logic extracted to WarningNotifier).
- Layer ordering: Pause > Withheld (pause = all calls ring, including restricted IDs).
- `normalize()` function: deduplicated from 2 files → single `PhoneNumbers.normalize()`.
- Withheld detection: 2 patterns → 7 (added restricted/private/unavailable/unknown/withheld).
- `OutboundGuard.record()`: empty-string guard added internally.
- `DomesticSpoofDetector`: `02x` → `020` only (fixes false-positive on 022=仙台, 023=山形, etc.).

### Added (continued)
- `DND honor mode` (Layer 14): when device DND is active, unknown domestic calls silenced.
- `Time-of-day risk multiplier` (Layer 15): unknown 090/080/070/060 during Mon-Fri 09-12/13-16 JST shows `HIGH_RISK_HOUR_DOMESTIC` warning. Rate-limited to once per 24h per number.
- `RepeatCallerTracker`: same number ringing 3+ times in 60 minutes → 4th call blocked (`REPEAT_CALLER`).
- `PostCallAdvisor`: after answered call >30s from unknown number, low-priority notification with #9110 / 188 / 0120-210-364 official hotlines.
- `TileService.requestListeningState()`: Quick Settings tiles refresh after every block event.
- IRSF/Wangiri elevated-risk corridors added to `ScamPrefixSeed` (Latvia +371, Lithuania +370, Sao Tome +239, Sierra Leone +232, Somalia +252, Cuba +53, Cook Islands +682, Tonga +676, Vanuatu +678) — recurrent IPRN termination points per NDSS 2021 IRSF research.

### Fixed
- `OnboardingActivity` no longer calls `startService()` on the CallScreeningService — threw on Android 8+ background-start limits and was semantically wrong (Telecom binds it via the role grant). See ADR 005.
- `DomesticSpoofDetector` special-prefix digit lengths corrected: 0120/0570/0990 are 10-digit, 0800/050/06x-09x are 11-digit (a refactor had grouped them as all-11). See ADR 004.
- Removed unused `android.content.Intent` import from `OnboardingActivity`.
- `PhoneNumbers.normalize()` now folds full-width '＋' and digits (U+FF0B, U+FF10–FF19) to ASCII before filtering — full-width-formatted numbers were surviving normalization yet failing every half-width prefix test, silently misclassifying the call. Foreign-script digits are now stripped, not kept. See ADR 007.

### Security (continued)
- Spam cache now stores SHA-256 hashes instead of plaintext numbers — the user's blocklist no longer appears in cleartext on disk (defense against malware, forensic imaging, backup leakage). A per-install 128-bit CSPRNG salt defeats cross-user precomputation, and the salt itself is encrypted with a non-exportable AndroidKeyStore AES-256-GCM key so a forensic `/data` image cannot recover it (KeyDroid arXiv:2507.07927). `CallState.spamCached` (Set) became `isSpamCached` (Boolean), keeping the engine pure. See ADR 006, arXiv:2304.02810.

## [Unreleased] — v1.2 (patch)

### Fixed
- **Spam cache (Layer 6) never populated** — `SpamCache.add()` was called only from tests; nothing in production ever wrote to the cache, so the documented "numbers you've previously blocked stay silent" layer never fired and the SHA-256 salted-hash infrastructure (ADR 006) sat unused. The `RestoreReceiver` already removed numbers from `KEY_SPAM`, implying a writer that was never wired. `SilentBlockerService.handleDecision()` now adds every silenced number to the cache, gated by the new pure `isCacheableSilence()` predicate which excludes `DND_HONOR` (a contextual silence that must not persist once DND is turned off).
- **OnboardingActivity compilation blocker** — `finishToSilent()` launched SettingsActivity via `Intent(...)` but the `android.content.Intent` import had been removed in v1.0.0 and never re-added; the build would fail. Re-added Intent/Context/edit imports.
- **Trust window anchored at first block instead of role grant** — `KEY_INSTALL_TS` was only set lazily on the first block. A user with no spam in their first week would have the 7-day loud-notification window restart whenever the first block finally landed. Now set once at role grant (protection start).
- **PostCallAdvisor false alarm on trusted callers** — the comment promised it skips outbound-known + business-bundle callers, but the code only checked outbound-known; a long call from a registered family member or bundled bank would show the "#9110 に相談を" scam sheet. Now checks outbound + family + business bundle, with E.164 variant matching.
- **FOREIGN_GENERIC (Layer 13)** — `isoOfCountryCode()` only covered 16 countries; calls from Brazil, Thailand, Indonesia, Turkey, etc. rang through. Replaced with direct calling-code comparison via `ScamPrefixSeed.countryCodeOf()` — now covers 150+ countries. See ADR 009.
- **Post-trust notification channel** — `maybeNotifyPostTrust()` used `orange_trust` ("Blocked calls (first week)") after day 7; now uses `orange_ongoing` ("Blocked calls") so Android notification settings are not misleading.
- **RestoreReceiver cancel() silent drop** — `(mgr) ?: return .cancel()` never cancelled the notification. Fixed to two-statement pattern.
- **RepeatCallerTracker off-by-one** — `>= N_THRESHOLD` fired on 3rd call; `> N_THRESHOLD` correctly fires on 4th as documented.
- **RepeatCallerTracker recording gap** — silenced calls never raised `PHONE_STATE_CHANGED RINGING`; `record()` now called from `SilentBlockerService.screenIncoming()` before the decision check, covering all calls including silenced ones.
- **CallContext duplicate definition** — orphaned field block (lines 89–99) from a previous edit caused compilation failure.
- **WangiriTracker empty-number guard** — `record("")` could store `""` as a Wangiri candidate; empty input now rejected.
- **EmergencyWhitelist incomplete** — added `+81189` (児童相談所), `+81171` (災害用伝言), `+61000` (AU 000) international roaming forms.
- **ScamPrefixSeed per-call set allocation** — `allCodes` set is now a `val` built once at class load, not on every call.
- **OrangeWidget PendingIntent request code** — changed from `0` to `0x0BABE` to avoid collision with other PendingIntents using request code 0.

- **Outbound-callback silenced** — the same domestic↔E.164 mismatch affected the WHOLE outbound-known path, not just family numbers: a number the user dialed in domestic form ("09012345678") would have its callback ("+819012345678") silenced. Introduced pure `phoneVariants(number, callingCode)` helper; `SilentBlockerService` now checks every variant of the incoming number against outbound-known + family, so either stored form matches. Efficient O(2) lookup rather than expanding the (up to 10k-entry) outbound set.
- **`CallStateObserver` stale answer-time after process death** — if the app process was killed between OFFHOOK (which sets `KEY_ANSWER_TIME`) and IDLE (which reads it to compute call duration), the stale `KEY_ANSWER_TIME` would persist across sessions. The next incoming call's IDLE event would compute `duration = now - staleAnswerTime` (potentially hours), satisfying the 30-second threshold and firing a spurious "consult #9110" PostCallAdvisor notification for a call that ended long ago. Fixed: `onRinging()` now clears `KEY_ANSWER_TIME` at the start of each new call, resetting any orphaned state from a prior session. Also consolidated two separate `prefs.edit` transactions in `onRinging()` into one.
- **`REPEAT_CALLER` silences were permanently cached** — `isCacheableSilence(REPEAT_CALLER)` returned `true`, so a number blocked for calling 4 times in 60 minutes was added to SpamCache. After the 60-minute velocity window expired, the number remained permanently silenced by Layer 6 (SPAM_CACHE). A legitimate urgent caller (e.g. an elderly parent who couldn't get through) would need the user to manually "Restore" the number. Like `DND_HONOR`, `REPEAT_CALLER` is a contextual/temporary silence — the block condition self-resolves. Added to the exclusion list in `isCacheableSilence()` and updated tests.
- **`AllowSuffixStore` overflow eviction was arbitrary** — when the suffix list exceeded 100 entries, `set.toList().takeLast(100)` was called on a `HashSet`-backed set with no defined iteration order. The newly added suffix (the one the user just tapped "Allow" for) could be discarded immediately, making the "Allow" action silently no-op when the list was full. Rewrote storage to use a space-separated ordered string (newest last, matching WangiriTracker/OutboundGuard pattern); overflow now correctly evicts the oldest entry. Also fixed duplicate adds (idempotent). Tests added.
- **`NotificationRateLimiter` withheld-number dedup broken** — `shouldNotify(prefs, "", nowMs)` uses `number = ""` as the seen-set key. `seen.add("")` works, but `seen.joinToString(" ")` produces `""`, and on reload `"".split(' ').filter { it.isNotBlank() }` drops the empty-string entry. Result: the "one notification per number per window" guard silently failed for withheld calls, which could fire up to 5 notifications per 5-minute window instead of 1. Fixed by mapping `""` to the sentinel `"#"` (which cannot appear in any normalized phone number) before all seen-set operations. Regression test added.
- **Weekly digest alarm lost on reboot** — `AlarmManager.setInexactRepeating` is cancelled when the device reboots. `WeeklyDigest.schedule()` was called only once from `OnboardingActivity` (at role grant). After a power cycle, the alarm was gone permanently — the user would never receive the weekly "N calls silenced" digest again unless they uninstalled and re-installed. Fixed: `RoleMonitorReceiver.onReceive(ACTION_BOOT_COMPLETED)` now calls `WeeklyDigest.schedule()` after the role refresh; `FLAG_UPDATE_CURRENT` makes it idempotent on package-replace where the alarm is still live. Also removed the dead `weekStart` variable in `WeeklyDigest.onReceive()`.
- **`RoleMonitor.refresh()` widget update was a silent no-op** — `AppWidgetProvider.onUpdate()` is only called when the `ACTION_APPWIDGET_UPDATE` broadcast includes `EXTRA_APPWIDGET_IDS`. Without it, `onReceive()` receives the broadcast but drops it and never calls `onUpdate()`. When Orange's screening role was silently revoked (user changed default app), the widget would never switch to the "·" role-lost indicator. Fixed: query live widget IDs via `AppWidgetManager.getAppWidgetIds()` and include them in the broadcast; skip the broadcast when no widget is placed.
- **`SettingsActivity` keyboard Done didn't handle empty input** — the Save button correctly routes an empty-after-filter field to `FamilyCallback.clearNumber()`, but the keyboard IME Done action called `setNumber(ctx, slot, "")` directly. `setNumber` returns false for empty strings (length 0 not in 3..15), so pressing Done on an empty field silently failed rather than clearing the slot. Fixed to match the button's empty-case branch.
- **`PhoneNumbers.normalize()` kept embedded `+` signs** — the normalizer passed all `+` characters through, so a raw input like `"++819012345678"` (user typed double-plus, or a carrier SIP URI fragment) produced `"++819012345678"`. This string starts with `"++"` rather than `"+"`, so `phoneVariants()` could not expand it to domestic form, and neither `startsWith("+81")` nor any prefix test matched — the number was effectively invisible to all trusted-set and country-code checks. Fixed: `+` is only kept if it is the very first character of the output (E.164 prefix). Any subsequent `+` is discarded. `PhoneNumbersTest` added.
- **DomesticSpoofDetector: 11-digit geographic landlines not flagged** — `violatesElevenDigitRule` only applies to numbers in `ELEVEN_DIGIT_PREFIXES` ("050","060","070","080","090","0800"). A fake "03XXXXXXXXXX" (11 digits, Tokyo area code) was not in any special-prefix list, so the residual check `d.length !in 10..11` passed it (11 IS in 10..11) and it rang through. JP geographic landlines are always exactly 10 digits per MIC mandate. Added explicit check: if the number is not an eleven-digit-service prefix, length 11 is impossible. Regression tests added for Tokyo/Osaka/Sendai 11-digit spoofs.
- **DomesticSpoofDetectorTest: orphaned tests outside class braces** — test functions at lines 98–125 were placed after the class closing brace, causing a compilation error. Moved all tests inside the class.
- **RestoreReceiver spam-cache removal was always a no-op** — `RestoreReceiver` removed the raw phone number string from `KEY_SPAM`, but `SpamCache` stores salted SHA-256 hashes there. `spam.remove(n)` never matched any entry, so Layer 6 (SPAM_CACHE) remained permanently armed after a Restore: the next call from that number was still silenced. Fixed to call `SpamCache.remove(prefs, n)` which hashes correctly before removal.
- **OutboundGuard spurious warning after Restore** — `OutboundGuard.record()` is called whenever a call is blocked or warned. When the user then taps "Restore" and calls that number back within 24 hours, `wasRecentlyFlagged()` still returned true, showing "この番号は先ほど警告された番号です" about a number the user just explicitly trusted. Added `OutboundGuard.forget()` and called it from RestoreReceiver so the guard entry is cleared alongside the spam-cache and outbound-known updates.
- **`!!` operator reintroduced in CallStateObserver** — `getStringSet(...)!!` violated the documented "all `!!` eliminated" invariant; replaced with `.orEmpty()`.
- **Family numbers silenced** — registered family numbers (stored as "09012345678") never matched E.164 incoming calls ("+819012345678"). Now handled by the same `phoneVariants()` matching against `familyNumbers(p)`.
- **Trusted contacts hit by repeat-caller** — outbound-known + family numbers were subject to the repeat-caller velocity check. A family member calling 4+ times in 60 minutes would have their 4th call silenced. Trusted numbers now bypass RepeatCallerTracker entirely.
- **Roaming SIM country detection** — `networkCountryIso` returns the visited network country while roaming; a JP SIM roaming in the US would have `calleeCountryIso="US"`, silencing all +81 JP calls as FOREIGN_GENERIC. Fixed to use `simCountryIso` (SIM home country) with `networkCountryIso` as fallback.
- **FamilyCallback full-width digit normalization** — `setNumber()` accepted full-width digits (０９０…) which passed `Char.isDigit()` but stored unfolded, causing `tel:` URI failures in dialPrimary(). Now uses `PhoneNumbers.normalize()` before validation.
- **`CallDecisionTest` / `DecisionPriorityTest` orphaned tests outside class braces** — `@Test` functions appeared after the class closing `}` in both files (same pattern as the DomesticSpoofDetectorTest fix). Added missing closing braces and removed a contradictory `withheld_overrides_pause` test that asserted `SILENCE` where the engine (Pause > Withheld, Layer 2 > Layer 3) correctly returns `RING`.
- **Rate-limit key hashCode collision in `PostCallAdvisor` and `WarningNotifier`** — `"postcall_last_${number.hashCode()}"` and `"highrisk_last_${number.hashCode()}"` used 32-bit `hashCode()` as the SharedPreferences key. Two different phone numbers with the same hash would share a rate-limit slot, suppressing PostCallAdvisor/high-risk-hour notifications for the second number. Phone numbers are at most 16 chars; replaced with the number itself as the key.
- **Layer 15 (アポ電 warning) silently skipped E.164 JP mobile numbers** — `isUnknownDomesticMobile()` only checked domestic trunk prefixes (`090/080/070/060`). Android typically delivers incoming calls in E.164 format (`+819012345678`), so the high-risk-hour warning never fired in practice. Added `+8190/+8180/+8170/+8160` prefix checks. Regression tests added.
- **WarningNotifier PendingIntent request code** — `addFamilyAction()` used request code `0`; changed to `0x0FAB1`.
- **`KEY_WAS_RINGING` visibility** — `private const val` in `CallStateObserver.companion` was referenced by `CallStateObserverTest`, causing a compilation error. Changed to `internal`.
- **Dead writes `KEY_LAST_TS` / `KEY_LAST_NUM`** — `SilentBlockerService.recordBlock()` wrote these two prefs keys on every silence verdict but no code ever read them. Removed the writes, the parameter, and the constant declarations.
- **CI oracle drift** — `oracle_decision.py` was missing the geographic-landline 11-digit spoof check added to `DomesticSpoofDetector` in v1.0. Oracle claimed 26/26 passing but would not catch a regression in that rule. Added Python logic and 3 new cases (Tokyo/Osaka 11-digit spoof). Also added `AllowSuffixStore`, `BlockHistoryStore`, `HistoryActivity`, `SettingsActivity` to step 7 class-reference check.

### Tests Added
- `RepeatCallerTrackerTest` (8 cases) — threshold boundary, window expiry, clear, multi-number isolation.
- `WangiriTrackerTest` (6 cases) — record/snapshot, 6-hour expiry, empty guard, forget, MAX_ENTRIES bound.
- `CallDecisionTest` additions — 189/171 domestic and international emergency variants, AU +61000, Brazil/Thailand/Indonesia/Turkey FOREIGN_GENERIC regression tests, family E.164/domestic round-trip tests.
- `PhoneVariantsTest` (9 cases) — domestic↔E.164 expansion, symmetry, null/empty guards, foreign mismatch, US calling code, real outbound-callback scenario.

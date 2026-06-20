# Orange — Threat Model

Rams #6 (honest) applied to security: publish the threats before a reviewer
asks. Structure follows STRIDE (Spoofing / Tampering / Repudiation /
Information disclosure / Denial of service / Elevation of privilege).

## Assets (what we're defending)

| Asset | Value | Leakage consequence |
|-------|-------|---------------------|
| User's call history (incoming + outbound) | High | Social graph reconstruction, blackmail enablement |
| User's spam-cache contents | Medium | Reveals which numbers user considers bad (sometimes ex-partners, creditors) |
| User's restored-number allowlist | Medium | Reveals which numbers user trusts |
| User's SIM country + carrier | Low | Partially revealed by OS already |
| Emergency-number bypass table | Critical | Tampering = user dies |

## Adversaries

| Adversary | Capability | Motivation |
|-----------|-----------|------------|
| Remote scammer | Cheap VoIP spoofing, burst dialers | Financial fraud (OreOre, Wangiri, phishing) |
| Compromised app on same device | Sibling app privileges, content provider probes | Data exfiltration, lateral movement |
| Network observer (ISP, public WiFi) | Passive TCP inspection | Traffic analysis |
| Google Play infrastructure | Full APK access, can serve modified update | Supply-chain compromise |
| Malicious contributor / OSS hijack | PR merge authority | Insert telemetry, privacy regression |
| OS-level attacker (root, bootloader) | Full system read | State actor, forensic seizure |

## STRIDE enumeration

### S — Spoofing

| Threat | Mitigation | Status |
|--------|-----------|--------|
| Scammer uses withheld / restricted caller ID to bypass screening | Withheld-number layer (Layer 3) silences all calls where caller ID is empty, "anonymous", "restricted", "private", "unavailable", "unknown", or "withheld". User can temporarily override via 1-hour Pause tile (so hospital restricted-number callbacks ring). | Mitigated. Pause layer (Layer 2) intentionally precedes withheld layer so the user retains control. |
| Scammer spoofs JP caller ID | `DomesticSpoofDetector` catches structural violations (020 reserved prefix, wrong mobile/IP/freephone digit lengths, 8+ repeating digits, 00x intl-access); `WangiriTracker` catches callback pattern; STIR/SHAKEN `VERIFICATION_STATUS_FAILED` catches carrier-detectable spoofs (API 30+) | Partial. Structurally valid non-STIR/SHAKEN spoofs still ring. |
| Scammer spoofs 110/119 emergency | Emergency numbers hard-allow, so spoofed 110 calls ring | Accepted risk. Silencing 110 to stop 0.001% scammer would kill real users. |
| Attacker impersonates Orange update | Play Store signed updates only; F-Droid build is reproducible | Mitigated by platform |
| Malicious replacement of `business_directory.csv` | CSV bundled in signed APK, not fetched at runtime | Mitigated. A compromised APK is a supply-chain issue, not a runtime one. |
| Wangiri premium-number callback scam | `CallStateObserver` detects short-ring (< 6s); `WangiriTracker` caches the candidate; `decide()` silences callback within 6h | Mitigated for the 6h window. Delayed callbacks outside the window ring. |

### T — Tampering

| Threat | Mitigation | Status |
|--------|-----------|--------|
| Sibling app writes to our SharedPreferences | Prefs are `MODE_PRIVATE`; storage is sandboxed per UID | Mitigated by OS on unrooted devices |
| Rooted attacker modifies spam cache | Out of scope — if an attacker has root, Orange is not the product's weakest link | Accepted |
| Rooted/forensic attacker reads spam cache to learn the user's blocklist | Cache stores salted SHA-256 hashes, not plaintext numbers; the per-install salt is itself encrypted with a non-exportable AndroidKeyStore AES-GCM key (KeyDroid arXiv:2507.07927). A `/data` image yields only ciphertext salt + salted hashes — recovering the plaintext numbers requires the on-device hardware key AND a per-device brute force of low-entropy numbers. | Hardened (defense-in-depth); not a guarantee against a determined on-device attacker |
| Tampered APK injects telemetry | Google Play signing + reproducible F-Droid build | Mitigated |
| PR injects network I/O | CI privacy-guard gate (`tools/check_no_network.sh`) runs on every push/PR and fails if any of: `java.net`, `okhttp`, `retrofit`, `volley`, `ktor`, `firebase`, `crashlytics`, `sentry`, `amplitude`, `INTERNET permission` appear in `app/src/main/`. Verified PASS on current codebase. | **Mitigated** |

### R — Repudiation

Not a strong axis for this product. Orange doesn't make any claims that
need to be attributable. If an incoming call wasn't silenced, Android's
own call log is the source of truth — Orange doesn't log anything extra.

### I — Information disclosure

| Threat | Mitigation | Status |
|--------|-----------|--------|
| Contacts leak to server | `READ_CONTACTS` not requested | Eliminated by permission model |
| Call history leaks to server | `INTERNET` not requested | Eliminated by permission model |
| Spam cache included in Google Drive Auto Backup | `allowBackup=false` + `data_extraction_rules.xml` exclude all | Mitigated |
| Crash logs leak state to logcat | R8 strips `Log.*` in release; no exception messages contain numbers | Mitigated |
| Widget preview captured in Recent Apps screenshot | Widget shows only a cumulative integer | Low severity; no individual numbers displayed |
| SharedPreferences visible to adb on debuggable builds | Release build sets `debuggable=false` | Mitigated in release |

### D — Denial of service

| Threat | Mitigation | Status |
|--------|-----------|--------|
| Scam burst generates 50 notifications | `NotificationRateLimiter` caps 5 per 5-minute window | Mitigated |
| Spam cache grows unbounded | `SpamCache` evicts at `MAX_ENTRIES = 10_000` | Mitigated |
| Wangiri tracker grows unbounded | Bounded at 64 entries, 6-hour window | Mitigated |
| Decision path exceeds Android's screening deadline | All lookups O(1) against snapshot state; no disk I/O in hot path | Mitigated |
| Repeated role revocation by OS update | Onboarding idempotent — relaunch re-requests role | Mitigated |
| DND mode prevents emergency calls (user misconfiguration) | DND_HONOR layer (Layer 14) silences unknown domestic calls only when DND active; emergency numbers always bypass via Layer 1 hardcoded list. If user enables DND and sets no Pause, known contacts still ring (Layer 4/5). User regains control via Pause tile. | Mitigated. User is responsible for DND configuration; product always allows emergencies. |

### E — Elevation of privilege

| Threat | Mitigation | Status |
|--------|-----------|--------|
| `BIND_SCREENING_SERVICE` misused by sibling app | Android requires this permission and verifies caller identity | Mitigated by OS |
| Our `RestoreReceiver` invoked by external attacker | Receiver has `android:exported="false"` | Mitigated |
| Widget or tile used as attack vector | Both are receiver-only, no inputs beyond user tap | Low severity |

## Privacy claims and how they are enforced

| Claim | Enforcement at code level |
|-------|----------------------------|
| "No servers" | `INTERNET` missing from manifest — uncompilable to break |
| "No contact upload" | `READ_CONTACTS` missing from manifest |
| "No backup sync" | `allowBackup=false` AND `data_extraction_rules` exclude all |
| "No crash reporter" | No Firebase, Crashlytics, Sentry, or any equivalent in dependencies |
| "No analytics" | Same as above; `dependencies {}` audit |
| "No in-app purchases" | No `com.android.billingclient` dependency |

All of the above can be verified by a reviewer grepping the unpacked APK.

## New component analysis (added 2026-05)

### PoliceStationDirectory (47-entry bundled police HQ directory)
| Axis | Threat | Mitigation |
|------|--------|-----------|
| S | Attacker submits PR with wrong police number → user trusts spoofed call | Every entry must cite a prefectural police official page. CI could not enforce this (human review required). |
| T | Modified APK replaces directory with scammer numbers | Signed APK + reproducible build prevents runtime modification. |
| I | Directory reveals which police HQs the app can identify | Directory is public data; no private information leakage. |

### FamilyCallback (pre-set family phone numbers)
| Axis | Threat | Mitigation |
|------|--------|-----------|
| T | Sibling app writes to SharedPreferences to change family number to scam number | MODE_PRIVATE + Android sandboxing; impossible on non-rooted device. |
| I | Family number stored in plaintext in SharedPreferences | Excluded from backup (data_extraction_rules.xml). Uninstall deletes. Rooted device is out of scope. |
| D | Scammer asks victim to change family number to scammer's number during call | Out of scope for technical defense; mitigated by Onboarding education. |

### WarningNotifier (police impersonation + outbound warning notifications)
| Axis | Threat | Mitigation |
|------|--------|-----------|
| D | Notification spam from repeated police-HQ-spoofed calls | Police warnings are NOT gated by NotificationRateLimiter (which only guards SILENCE notifications via TrustNotifier). Natural bound: only 47 known HQ numbers can trigger police warnings. Repeated calls from the same HQ number reuse the same notification ID (number.hashCode() + constant), so mgr.notify() updates in-place rather than creating new notifications. Net maximum: 47 concurrent police-warning notifications. |
| S | Scammer spoofs a number NOT in the directory → no warning shown | Accepted risk. Directory covers 47 prefectural HQs; individual police stations (~1,200) are a future expansion. |

### OutboundGuard (24h flagged-number tracker)
| Axis | Threat | Mitigation |
|------|--------|-----------|
| D | Tracker grows unbounded | Bounded at MAX_ENTRIES=64, 24h TTL pruning. |
| I | Tracker reveals which numbers were recently blocked | Same SharedPreferences sandbox as spam cache; excluded from backup. |

### CaribbeanPremiumNANP (22 NANP area code blocklist)
| Axis | Threat | Mitigation |
|------|--------|-----------|
| S | Legitimate caller from Bahamas/Jamaica blocked | Outbound-known layer takes precedence; user who dialed +1-242 before will always ring. False positive risk is acknowledged in HONESTY_ADDENDUM. |

### AllowSuffixStore (4-digit suffix allow-list from history)
| Axis | Threat | Mitigation |
|------|--------|-----------|
| S | Scammer crafts a number ending in the same 4 digits as an allowed suffix → permanently bypasses screener | Known accepted trade-off. History stores only last 4 digits (privacy). Exact-number restore is available via per-block notification during the 7-day trust window. Documented in HONESTY_ADDENDUM §12. |
| T | Attacker with root overwrites the suffix list to add scam numbers | ROOT access is out of scope for this threat model. MODE_PRIVATE restricts to this package. |
| I | Suffix list reveals which callers the user has whitelisted | Last-4-digit suffixes are low-entropy but not full numbers. Excluded from backup. Uninstall wipes. |
| D | Overflow of MAX=100 entries evicts oldest legitimate suffixes | FIFO eviction at 100 entries. 100 distinct allowed suffixes is a realistic upper bound for an individual user's contacts. |

### BlockHistoryStore (50-entry masked block log)
| Axis | Threat | Mitigation |
|------|--------|-----------|
| I | Block history reveals which numbers were blocked (timing + reason) | Full numbers never stored; only last-4 digits + reason + timestamp. Excluded from backup. Uninstall wipes. |
| T | Tampered history shows false entries (social engineering — "look, your bank called") | Requires root. Out of scope. |
| D | History grows unbounded | Capped at 50 entries, 30-day TTL auto-prune on load. |

### RepeatCallerTracker (velocity heuristic)
| Axis | Threat | Mitigation |
|------|--------|-----------|
| D | Scammer dials once per hour from the same number, staying below the N_THRESHOLD=3 per 60-min window, avoiding the velocity block | Accepted. Lower thresholds would silence legitimate callers (elderly relative trying repeatedly). |
| D | Scammer floods with N+1 calls from rotating numbers | Out of scope for velocity tracking; handled by Wangiri+DomesticSpoof+SpamCache layers. |
| S | Number in RepeatCallerTracker mistakenly cleared when unrelated answered call has same number | clear() matches by full number, not suffix. Collision requires exact number match. |

## Explicitly accepted risks

1. **Spoofed emergency numbers ring.** A scammer presenting caller ID as 110
   reaches the user. Silencing real emergencies is a far worse outcome than
   letting one scammer through, so we accept this.
2. **Domestic spoofs using plausible JP numbers ring.** Our structural
   detector catches perhaps 30–50% of domestic spoofing. The rest requires
   either a server-side corpus (which we refuse to build) or carrier-level
   signaling (outside our control).
3. **Business-directory false positives ring.** If a scammer ever acquires
   a number that was legitimate when bundled, their call rings until the
   next app update. We audit the CSV before shipping but cannot close this
   gap completely offline.
4. **No remote revocation.** If a threat emerges after a user's last app
   update, we cannot push a fix without them updating. This is the same
   trust model as any offline app and is deliberate — remote revocation
   would require a server, which we refuse.

## External audit invitation

Orange's license is MIT and the code is short enough (~2200 LOC Kotlin)
that an interested reviewer can read it in under two hours. File issues
at the project's GitHub tracker; security-sensitive reports should follow
the process in `SECURITY.md`.

## Changelog

- 2026-04: initial publication. STRIDE enumeration covers the v1.0.0 surface.
- 2026-06: added STRIDE analysis for AllowSuffixStore, BlockHistoryStore,
  RepeatCallerTracker (all added in v1.1). AllowSuffix 4-digit spoofing attack
  explicitly acknowledged as accepted risk (also documented in HONESTY_ADDENDUM §12).
- 2026-05: added STRIDE analysis for PoliceStationDirectory,
  FamilyCallback, WarningNotifier, OutboundGuard, CaribbeanPremiumNANP.

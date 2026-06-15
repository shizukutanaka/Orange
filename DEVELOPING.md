# Developing Orange

A 5-minute setup for new contributors.

## Prerequisites

- **JDK 17** — Temurin or Liberica recommended. `java --version` should
  print `17.x.x`.
- **Android SDK** — install via Android Studio or `sdkmanager`. Required
  components: platform 35, build-tools 35.0.0, platform-tools.
- **Git** — any recent version.

You do **not** need Android Studio to build. Command line is sufficient
and is what CI uses.

## First-time clone

```bash
git clone https://github.com/<orange-repo>.git
cd orange
# Bootstrap the Gradle wrapper jar (we don't commit it):
curl -fsSL -o gradle/wrapper/gradle-wrapper.jar \
    https://github.com/gradle/gradle/raw/v8.10.2/gradle/wrapper/gradle-wrapper.jar
```

Optionally, configure `local.properties` so Gradle finds your Android SDK:

```properties
sdk.dir=/Users/you/Library/Android/sdk
```

## Common workflows

### Install git hooks (do this once after cloning)

```bash
git config core.hooksPath .githooks
```

`pre-commit` runs the privacy guard, the decision oracle, and the static
checks. `pre-push` additionally runs the JVM unit tests if `./gradlew` is
present. Hooks make the same gates CI enforces run before code leaves your
machine.

### Run the unit tests

```bash
./gradlew testReleaseUnitTest
```

Tests are pure Kotlin (no Robolectric, no Android dependencies). They
should complete in well under a minute. If a test takes longer than
that, something is wrong with your setup.

### Run the decision oracle

```bash
python3 tools/oracle_decision.py
```

The oracle is a Python mirror of the numbering-plan rules in
`DomesticSpoofDetector.kt`. It is a fast tripwire for behavioral
regressions when you don't have a JVM handy. **When you change a
digit-length rule in the Kotlin source, you MUST update the oracle and
its `CASES` table to match** — if they disagree, one of them is wrong.
This pairing is what would have caught the 0120-freephone regression
documented in `docs/adr/004`.

### Sync work tree to the outputs directory

```bash
bash tools/sync_output.sh
```

Mirrors the working tree into `/mnt/user-data/outputs/orange` with deletions
propagated (uses `rsync --delete`, or a full rebuild if rsync is absent). Use
this instead of ad-hoc `find | cp` loops: a partial hand-written sync once left
a stale source file in outputs while the docs referenced a fix that wasn't
actually shipped.

The script refuses to run if the destination is not an outputs directory, so a
swapped-argument accident (`sync_output.sh OUTPUT WORKTREE`) cannot wipe the
working tree.

**Restoring the work tree from outputs** (start of a fresh session): copy the
*contents* into the work dir, never the directory itself, or you get a nested
`orange/orange/`:

```bash
# correct — trailing /. copies contents
cp -r /mnt/user-data/outputs/orange/. /home/claude/orange/
# WRONG — creates /home/claude/orange/orange/
# cp -r /mnt/user-data/outputs/orange /home/claude/orange/
```

### Run the privacy guard

```bash
bash tools/check_no_network.sh app/src/main
```

This is what CI runs first on every PR. Run it locally before pushing —
catching a network introduction before review saves time.

### Run lint

```bash
./gradlew lintRelease
```

Lint is configured with `warningsAsErrors = true`. There is no "I'll fix
the warnings later" — fix them before opening the PR.

### Build a debug APK

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

The debug variant has `applicationIdSuffix = ".debug"` so you can keep
a release version installed at the same time.

### Build a release APK locally (unsigned)

```bash
./gradlew assembleRelease
ls -lh app/build/outputs/apk/release/
bash tools/check_apk_size.sh
```

The APK size guard enforces a hard ceiling of **1 MiB** (1,048,576 bytes). Target is
<200 KB; breach the ceiling and the check fails in CI. Keeping the APK small is
deliberate — it reduces install friction on low-storage devices and makes the
"no network, no third-party SDKs" promise auditable at a glance.

### Sign a release APK locally

```bash
export ORANGE_KEYSTORE_PATH=/path/to/release.jks
export ORANGE_KEYSTORE_PASSWORD=...
export ORANGE_KEY_ALIAS=...
export ORANGE_KEY_PASSWORD=...
./gradlew assembleRelease
```

The keystore path is read from the env var or from
`local.properties` (`orange.keystore.path=...`). Never commit the keystore
or its passphrases.

## Project layout

```
orange/
├── app/
│   ├── src/main/
│   │   ├── java/com/orange/apple/  ← Kotlin sources (≈3600 LOC, 30 files)
│   │   ├── res/                    ← Layouts, strings (en/ja/zh/ko), icons
│   │   ├── assets/business_directory.csv
│   │   └── AndroidManifest.xml
│   ├── src/test/                   ← Pure Kotlin unit tests (≈2300 LOC, 296 tests)
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── signing.gradle.kts
├── gradle/libs.versions.toml       ← Single source of truth for versions
├── tools/                          ← Privacy guard + APK size guard + comprehensive check
├── fastlane/metadata/              ← Play Store listing metadata
├── docs/play_data_safety.json      ← Play Console data safety declaration
└── *.md                            ← Documentation (14 files)
```

## Key source files

### Decision engine (pure Kotlin, zero Android dependencies)
| File | Role |
|------|------|
| `CallDecision.kt` | 16-point `decide()` engine + `BlockReason` / `WarnReason` / `CallContext` |
| `EmergencyWhitelist.kt` | Hard-coded emergency numbers that always ring |
| `DomesticSpoofDetector.kt` | MIC numbering plan structure validator |
| `ScamPrefixSeed.kt` | 8 elevated-risk country codes + `countryCodeOf()` |
| `CaribbeanPremiumNANP.kt` | 22 NANP premium-rate area codes |
| `PoliceStationDirectory.kt` | 47 prefectural police HQ numbers (exact match) |
| `PhoneNumbers.kt` | `normalize()` — single source of truth for number cleaning |

### Android adapter layer
| File | Role |
|------|------|
| `SilentBlockerService.kt` | `CallScreeningService` — binds engine to Android Telecom |
| `CallStateObserver.kt` | `PHONE_STATE` broadcast receiver — Wangiri + PostCall tracking |
| `EngineWarmup.kt` | `ContentProvider` initializer — pre-loads CSV at app start |
| `OnboardingActivity.kt` | Single-screen role-grant flow |
| `OrangeWidget.kt` | Home-screen widget (block count + role-loss glyph) |
| `PauseTile.kt` | Quick Settings tile — 1-hour pause |
| `FamilyCallbackTile.kt` | Quick Settings tile — one-tap dial to family preset |
| `RoleMonitor.kt` | Boot/package-replace role status checker |

### Infrastructure (state, notifications, heuristics)
| File | Role |
|------|------|
| `SpamCache.kt` | LRU spam cache (10,000 entries) |
| `WangiriTracker.kt` | Short-ring (≤15s) → callback-window detection |
| `RepeatCallerTracker.kt` | Same number calling N+ times in 60 min → block |
| `OutboundGuard.kt` | 24h LRU: warns on callback to recently-blocked number |
| `NotificationRateLimiter.kt` | 5 notifications / 5-minute window |
| `BusinessDirectoryBundle.kt` | Offline CSV loader (80+ verified business numbers, v1.1) |
| `FamilyCallback.kt` | Up to 3 pre-set family numbers + validation |
| `TrustNotifier.kt` | Per-block trust-window notifications (days 1–7) |
| `WarningNotifier.kt` | Police impersonation + outbound + high-risk-hour warnings |
| `PostCallAdvisor.kt` | Post-call #9110/188/0120 hotline notification (>30s unknown calls) |
| `WeeklyDigest.kt` | Weekly → monthly block-count digest notification |

## What to read first

1. `README.md` — product overview
2. `DESIGN_NOTES.md` — what was deliberately removed
3. `THREAT_MODEL.md` — what the product defends against
4. `CONTRIBUTING.md` — how PRs are evaluated

## Pre-PR checklist

```bash
bash tools/check_no_network.sh app/src/main \
  && ./gradlew testReleaseUnitTest \
  && ./gradlew lintRelease \
  && ./gradlew assembleRelease \
  && bash tools/check_apk_size.sh
```

If all five pass, your PR will sail through CI. If any fail, fix locally
first.

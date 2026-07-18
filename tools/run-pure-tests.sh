#!/usr/bin/env bash
# run-pure-tests.sh — actually execute Orange's Android-free unit tests without
# a full Android SDK or Gradle-resolved AGP.
#
# WHY THIS EXISTS
# ----------------
# The repo has no .github/workflows/ (it's .gitignore'd), and the static gate
# `check_comprehensive.sh` only *counts* @Test annotations — it never *runs*
# the tests. So until this script, the JVM unit tests had never actually been
# executed in any automated way. Running them for the first time (2026-07)
# surfaced real failures (see docs/FEATURE_AUDIT.md → "Test suite never executed").
#
# It works by reusing the Kotlin compiler and JUnit 4 jars that ship *inside*
# the Gradle distribution (no network, no Android SDK needed).
#
# SCOPE / LIMITATION
# ------------------
# This runs ONLY the subset of tests whose transitive dependencies are
# Android-free (the pure decision engine + its data directories). Tests that
# touch android.content.SharedPreferences (via FakePrefs) or other Android
# types still require a real `./gradlew testReleaseUnitTest` with the SDK —
# they cannot compile without android.jar. The Android-free subset below is
# where all the numbering-plan / layer-ordering / directory logic lives, so it
# catches the majority of decision-correctness regressions.
#
# USAGE:  bash tools/run-pure-tests.sh   (from the repo root)
set -euo pipefail

# Locate a Gradle distribution that bundles the Kotlin compiler + JUnit.
GRADLE_LIB="${GRADLE_LIB:-}"
if [[ -z "$GRADLE_LIB" ]]; then
  for cand in /opt/gradle-*/lib "$(dirname "$(command -v gradle 2>/dev/null || echo /nonexistent)")/../lib"; do
    if compgen -G "$cand/kotlin-compiler-embeddable-*.jar" >/dev/null 2>&1; then GRADLE_LIB="$cand"; break; fi
  done
fi
if [[ -z "$GRADLE_LIB" ]] || ! compgen -G "$GRADLE_LIB/kotlin-compiler-embeddable-*.jar" >/dev/null 2>&1; then
  echo "ERROR: could not find a Gradle lib dir with kotlin-compiler-embeddable-*.jar." >&2
  echo "Set GRADLE_LIB=/path/to/gradle/lib and re-run." >&2
  exit 2
fi

KCP=$(find "$GRADLE_LIB" -name '*.jar' | tr '\n' ':')
STDLIB=$(ls "$GRADLE_LIB"/kotlin-stdlib-*.jar | head -1)
JUNIT=$(ls "$GRADLE_LIB"/junit-*.jar | head -1)
HAMCREST=$(ls "$GRADLE_LIB"/hamcrest-core-*.jar | head -1)

SRC=app/src/main/java/com/orange/apple
T=app/src/test/java/com/orange/apple
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT

# Android-free main sources (no `import android.*`; verified 2026-07).
MAIN_SRCS=(
  "$SRC/CallDecision.kt" "$SRC/DomesticSpoofDetector.kt" "$SRC/ScamPrefixSeed.kt"
  "$SRC/CaribbeanPremiumNANP.kt" "$SRC/PhoneNumbers.kt" "$SRC/EmergencyWhitelist.kt"
  "$SRC/PoliceStationDirectory.kt" "$SRC/TaxAgencyDirectory.kt" "$SRC/ProtectionDataVersion.kt"
)

# CallDecision.kt re-exports one constant from the Android-dependent
# WangiriTracker (a test convenience). Provide just that constant so the
# Android-free sources compile. Value mirrors WangiriTracker.WANGIRI_WINDOW_MS;
# it carries no logic, so it cannot mask a bug in any class under test.
cat > "$OUT/shim.kt" <<'KOTLIN'
package com.orange.apple
internal object WangiriTracker { const val WANGIRI_WINDOW_MS = 6L * 60 * 60 * 1000 }
KOTLIN

# Tests whose only project dependencies are the Android-free set above.
TEST_SRCS=(
  "$T/DecisionPriorityTest.kt" "$T/HighRiskHourTest.kt" "$T/WhitelistAndSeedTest.kt"
  "$T/EmergencyWhitelistTest.kt" "$T/DomesticSpoofDetectorTest.kt" "$T/CaribbeanPremiumNANPTest.kt"
  "$T/PhoneNumbersTest.kt" "$T/PoliceStationDirectoryTest.kt" "$T/TaxAgencyDirectoryTest.kt"
  "$T/ProtectionDataVersionTest.kt"
)

echo "==> compiling ${#MAIN_SRCS[@]} main sources"
java -cp "$KCP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  "${MAIN_SRCS[@]}" "$OUT/shim.kt" -d "$OUT/main" -no-reflect -classpath "$STDLIB" 2>&1 \
  | grep -v 'JAVA_TOOL_OPTIONS\|unable to find' || true

echo "==> compiling ${#TEST_SRCS[@]} test sources"
# -Xfriend-paths grants the test module `internal` access to main, exactly as
# Gradle's test sourceset does.
java -cp "$KCP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  "${TEST_SRCS[@]}" -d "$OUT/test" -no-reflect \
  -classpath "$OUT/main:$STDLIB:$JUNIT:$HAMCREST" -Xfriend-paths="$OUT/main" 2>&1 \
  | grep -v 'JAVA_TOOL_OPTIONS\|unable to find' || true

echo "==> running tests"
CLASSES=$(cd "$OUT/test" && find . -name '*Test.class' | sed 's|^\./||;s|\.class$||;s|/|.|g' | tr '\n' ' ')
java -cp "$OUT/main:$OUT/test:$STDLIB:$JUNIT:$HAMCREST" org.junit.runner.JUnitCore $CLASSES 2>&1 \
  | grep -v 'JAVA_TOOL_OPTIONS'

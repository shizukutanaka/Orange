#!/usr/bin/env bash
# run-pure-tests.sh — actually execute Orange's JVM unit tests without a full
# Android SDK or Gradle-resolved AGP.
#
# WHY THIS EXISTS
# ----------------
# For the repo's whole history there was no CI: `.github/workflows/` sat in
# .gitignore from the initial commit (an accident — see .github/workflows/ci.yml),
# and the static gate `check_comprehensive.sh` only *counts* @Test annotations,
# it never *runs* the tests. So until this script, the JVM unit tests had never
# actually been executed in any automated way. Running them for the first time
# (2026-07) surfaced real issues (docs/FEATURE_AUDIT.md §1-6). CI now exists and
# invokes this script, but it stays useful standalone: it needs no Android SDK
# and no network, so a contributor can run the suite locally in ~10 seconds.
#
# It works by reusing the Kotlin compiler and JUnit 4 jars that ship *inside*
# the Gradle distribution (no network, no Android SDK needed), plus a tiny
# throwaway Android type shim generated below in a temp dir (never committed).
#
# SCOPE / LIMITATION
# ------------------
# Runs the tests whose transitive dependencies are either Android-free OR touch
# only android.content.SharedPreferences (+ androidx.core.content.edit,
# android.util.Base64, android.security.keystore.* for SaltVault), plus anything
# reachable through a CONSTANTS-ONLY stub. That is 24 of the repo's 33 test
# files: the whole decision engine (CallDecisionTest's 111 cases,
# EngineInvariantTest, DecisionPriorityTest, PhoneVariantsTest), the
# numbering-plan and directory logic, and the SharedPreferences-backed stores.
#
# The other 9 were each measured (2026-07), not guessed, and every one needs a
# stub that would carry BEHAVIOUR — which is where this script stops, because a
# stub with behaviour is a place for a false pass to hide:
#   ManualBlockTest              ManualBlock cascades into BusinessDirectoryBundle.load
#                                (reads assets) and SilentBlockerService.screenIncoming
#   FamilyCallbackTest           calls only the pure normalizeAndValidate, but compiling
#                                FamilyCallback.kt needs PendingIntent/TileService/Uri/Intent
#   PauseTileTest                TileService/Tile/Icon, and executes onClick
#   WarningNotifierRateLimitTest really invokes showOutboundWarning/showHighRiskHourWarning,
#                                so it needs the whole NotificationCompat.Builder chain
#   BusinessDirectoryBundleTest  Context + asset loading
#   CallStateObserverTest        Context + the real service
#   PostCallAdvisorTest          Context
#   TrustNotifierTest            Context + NotificationManager
#   WeeklyDigestTest             Context + NotificationManager
# These are covered by CI's android-build job (./gradlew testReleaseUnitTest on a
# real SDK). The boundary above is measured; re-deriving it is wasted effort —
# to move it, use an SDK, not a bigger shim.
#
# The Android shim is type-signatures only (the one behavioural stub, Base64,
# delegates to java.util.Base64; SaltVault's KeyStore.getInstance("AndroidKeyStore")
# throws on the JVM and it falls back to a plaintext salt before any keystore
# type is instantiated). No stub carries business logic, so none can mask a bug.
#
# EXPECTED: 435 tests run, 0 failures. (Two DomesticSpoofDetector tests spent a
# while intentionally failing as the visible signal of a design question; ITU-T
# E.164 settled it — the leading 0 is a trunk prefix, so the abstain is the
# contract being honoured — and the tests now assert the correct behaviour with
# the analysis inline. See docs/FEATURE_AUDIT.md §1-7.) ANY failure is a
# regression.
#
# USAGE:  bash tools/run-pure-tests.sh   (from the repo root)
set -euo pipefail

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
KC() { java -cp "$KCP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler "$@" 2>&1 | grep -v 'JAVA_TOOL_OPTIONS\|unable to find' || true; }

SRC=app/src/main/java/com/orange/apple
T=app/src/test/java/com/orange/apple
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT

# --- Throwaway Android type shim (temp only, NEVER committed) ---------------
mkdir -p "$OUT/shimjava/android/content"
cat > "$OUT/shimjava/android/content/SharedPreferences.java" <<'JAVA'
package android.content;
import java.util.Map; import java.util.Set;
// Java (not Kotlin) so Kotlin sees platform types, exactly like android.jar —
// lets both SpamCache's Set<String> calls and FakePrefs' MutableSet overrides bind.
public interface SharedPreferences {
    Map<String, ?> getAll();
    String getString(String key, String defValue);
    Set<String> getStringSet(String key, Set<String> defValues);
    int getInt(String key, int defValue);
    long getLong(String key, long defValue);
    float getFloat(String key, float defValue);
    boolean getBoolean(String key, boolean defValue);
    boolean contains(String key);
    Editor edit();
    void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener);
    void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener);
    interface Editor {
        Editor putString(String key, String value);
        Editor putStringSet(String key, Set<String> values);
        Editor putInt(String key, int value);
        Editor putLong(String key, long value);
        Editor putFloat(String key, float value);
        Editor putBoolean(String key, boolean value);
        Editor remove(String key);
        Editor clear();
        boolean commit();
        void apply();
    }
    interface OnSharedPreferenceChangeListener {}
}
JAVA
javac -d "$OUT/shimjava-out" "$OUT/shimjava/android/content/SharedPreferences.java" 2>&1 | grep -v 'JAVA_TOOL_OPTIONS' || true

mkdir -p "$OUT/shim"
cat > "$OUT/shim/base64.kt" <<'KOTLIN'
package android.util
object Base64 {
    const val NO_WRAP: Int = 2
    @JvmStatic fun encodeToString(input: ByteArray, flags: Int): String =
        java.util.Base64.getEncoder().withoutPadding().encodeToString(input)
    @JvmStatic fun decode(str: String, flags: Int): ByteArray =
        java.util.Base64.getDecoder().decode(str)
}
KOTLIN
cat > "$OUT/shim/keystore.kt" <<'KOTLIN'
package android.security.keystore
object KeyProperties {
    const val KEY_ALGORITHM_AES = "AES"; const val PURPOSE_ENCRYPT = 1
    const val PURPOSE_DECRYPT = 2; const val BLOCK_MODE_GCM = "GCM"
    const val ENCRYPTION_PADDING_NONE = "NoPadding"
}
class KeyGenParameterSpec private constructor() : java.security.spec.AlgorithmParameterSpec {
    class Builder(keystoreAlias: String, purposes: Int) {
        fun setBlockModes(vararg m: String): Builder = this
        fun setEncryptionPaddings(vararg p: String): Builder = this
        fun setKeySize(k: Int): Builder = this
        fun build(): KeyGenParameterSpec = KeyGenParameterSpec()
    }
}
KOTLIN
cat > "$OUT/shim/edit.kt" <<'KOTLIN'
package androidx.core.content
import android.content.SharedPreferences
inline fun SharedPreferences.edit(commit: Boolean = false, action: SharedPreferences.Editor.() -> Unit) {
    val e = edit(); e.action(); if (commit) e.commit() else e.apply()
}
KOTLIN
cat > "$OUT/shim/sbs.kt" <<'KOTLIN'
package com.orange.apple
// Constants-only stub of SilentBlockerService's companion (the real class extends
// CallScreeningService). Real values, verified against SilentBlockerService.kt.
internal object SilentBlockerService {
    const val PREFS = "orange_apple"; const val KEY_OUTBOUND = "outbound"
    const val KEY_SPAM = "spam"; const val KEY_COUNT = "count"
}
// Same treatment for RoleMonitor: the real object pulls in RoleManager,
// AppWidgetManager, BroadcastReceiver and ComponentName, but RoleMonitorTest
// only ever touches this one constant plus FakePrefs — the RoleManager
// interaction is explicitly out of scope there ("we deliberately avoid
// Robolectric"). Constants only, so it carries no behaviour to get wrong.
internal object RoleMonitor { const val KEY_ROLE_HELD = "role_held" }
KOTLIN

# --- Compile main (decision engine + SharedPreferences-backed stores) -------
MAIN_SRCS=(
  "$SRC/CallDecision.kt" "$SRC/DomesticSpoofDetector.kt" "$SRC/ScamPrefixSeed.kt"
  "$SRC/CaribbeanPremiumNANP.kt" "$SRC/PhoneNumbers.kt" "$SRC/EmergencyWhitelist.kt"
  "$SRC/PoliceStationDirectory.kt" "$SRC/TaxAgencyDirectory.kt" "$SRC/ProtectionDataVersion.kt"
  "$SRC/SpamCache.kt" "$SRC/SaltVault.kt" "$SRC/OutboundGuard.kt" "$SRC/WangiriTracker.kt"
  "$SRC/RepeatCallerTracker.kt" "$SRC/AllowSuffixStore.kt" "$SRC/NotificationRateLimiter.kt"
  "$SRC/BlockHistoryStore.kt"
)
echo "==> compiling ${#MAIN_SRCS[@]} main sources + shim"
KC "${MAIN_SRCS[@]}" "$OUT/shim/base64.kt" "$OUT/shim/keystore.kt" "$OUT/shim/edit.kt" "$OUT/shim/sbs.kt" \
   -d "$OUT/main" -no-reflect -classpath "$STDLIB:$OUT/shimjava-out"

# --- Compile tests ----------------------------------------------------------
TEST_SRCS=(
  "$T/DecisionPriorityTest.kt" "$T/HighRiskHourTest.kt" "$T/WhitelistAndSeedTest.kt"
  "$T/EmergencyWhitelistTest.kt" "$T/DomesticSpoofDetectorTest.kt" "$T/CaribbeanPremiumNANPTest.kt"
  "$T/PhoneNumbersTest.kt" "$T/PoliceStationDirectoryTest.kt" "$T/TaxAgencyDirectoryTest.kt"
  "$T/ProtectionDataVersionTest.kt"
  "$T/SpamCacheTest.kt" "$T/OutboundGuardTest.kt" "$T/WangiriTrackerTest.kt"
  "$T/AllowSuffixStoreTest.kt" "$T/NotificationRateLimiterTest.kt" "$T/BlockHistoryStoreTest.kt"
  "$T/RepeatCallerTrackerTest.kt"
  # Engine-level suites. CallDecisionTest alone is 111 tests over decide() —
  # the product's core — and had never been executed by anything.
  "$T/CallDecisionTest.kt" "$T/EngineInvariantTest.kt" "$T/ComponentTests.kt"
  "$T/WangiriCallbackWarningTest.kt" "$T/SaltVaultTest.kt" "$T/PhoneVariantsTest.kt"
  "$T/RoleMonitorTest.kt" "$T/PhoneVariantsTest.kt"
)
echo "==> compiling ${#TEST_SRCS[@]} test sources"
KC "${TEST_SRCS[@]}" -d "$OUT/test" -no-reflect \
   -classpath "$OUT/main:$STDLIB:$JUNIT:$HAMCREST:$OUT/shimjava-out" -Xfriend-paths="$OUT/main"

echo "==> running tests (expected steady state: 435 run / 0 failures)"
CLASSES=$(cd "$OUT/test" && find . -name '*Test.class' | sed 's|^\./||;s|\.class$||;s|/|.|g' | tr '\n' ' ')
set +e
RESULT=$(java -cp "$OUT/main:$OUT/test:$STDLIB:$JUNIT:$HAMCREST:$OUT/shimjava-out" org.junit.runner.JUnitCore $CLASSES 2>&1 | grep -v 'JAVA_TOOL_OPTIONS')
set -e
echo "$RESULT"

# Exit-code contract (so this can gate a push): succeed iff NO test fails.
# ALLOWED is the escape hatch for a future deliberately-failing signal test;
# it is empty now that the §1-7 design question is settled, and should stay
# empty absent an equally-well-documented reason.
ALLOWED=''
FAILED=$(printf '%s\n' "$RESULT" | grep -oE '^[0-9]+\) [a-zA-Z_0-9]+\(' | sed 's/^[0-9]*) //; s/($//' || true)
UNEXPECTED=""
for f in $FAILED; do
  case " $ALLOWED " in *" $f "*) : ;; *) UNEXPECTED="$UNEXPECTED $f" ;; esac
done
if [ -n "$UNEXPECTED" ]; then
  echo ""
  echo "REGRESSION: unexpected test failure(s):$UNEXPECTED" >&2
  echo "(No failures are tolerated; see the ALLOWED list in this script.)" >&2
  exit 1
fi
echo "==> OK (no unexpected failures)"

#!/usr/bin/env bash
# Orange comprehensive static analysis.
# Runs without Android SDK or Kotlin compiler. This is the CI fast-check
# that catches structural bugs before Gradle ever starts.
#
# Exit 0 = all green. Exit 1 = at least one hard failure.

set -uo pipefail
MAIN="app/src/main/java/com/orange/apple"
TEST="app/src/test/java/com/orange/apple"
RES="app/src/main/res"
FAIL=0

echo "=== 1/9. Privacy guard ==="
bash tools/check_no_network.sh app/src/main || FAIL=1

echo "=== 2/9. R.string coverage ==="
for str in $(grep -ohP 'R\.string\.\K\w+' "$MAIN"/*.kt 2>/dev/null | sort -u); do
    if ! grep -q "name=\"$str\"" "$RES/values/strings.xml"; then
        echo "FAIL: R.string.$str not declared"; FAIL=1
    fi
done

echo "=== 3/9. R.color coverage ==="
for col in $(grep -ohP '@color/\K\w+' "$RES/values/themes.xml" 2>/dev/null | sort -u); do
    if ! grep -q "name=\"$col\"" "$RES/values/colors.xml"; then
        echo "FAIL: @color/$col not declared"; FAIL=1
    fi
done

echo "=== 4/9. Manifest class definitions ==="
for cls in $(grep -oP 'android:name="\.\K[^"]+' app/src/main/AndroidManifest.xml); do
    if ! grep -rql "class $cls" "$MAIN/"*.kt 2>/dev/null; then
        echo "FAIL: Manifest .$cls has no class"; FAIL=1
    fi
done

echo "=== 5/9. Emergency coverage ==="
for n in 110 119 118 911 112 999 000; do
    if ! grep -q "\"$n\"" "$MAIN/EmergencyWhitelist.kt"; then
        echo "FAIL: Emergency $n missing"; FAIL=1
    fi
done

echo "=== 6/9. BlockReason + WarnReason test coverage ==="
for reason in SPAM_CACHE FOREIGN_ELEVATED FOREIGN_GENERIC DOMESTIC_SPOOF WANGIRI_CALLBACK CARRIER_VERIFICATION_FAILED WITHHELD_NUMBER PREMIUM_RATE_INTERNATIONAL DND_HONOR REPEAT_CALLER; do
    if ! grep -rq "BlockReason\.$reason" "$TEST/"*.kt 2>/dev/null; then
        echo "FAIL: BlockReason.$reason has no test assertion"; FAIL=1
    fi
done
for warn in POLICE_IMPERSONATION POLICE_IMPERSONATION_HIGH HIGH_RISK_HOUR_DOMESTIC; do
    if ! grep -rq "WarnReason\.$warn" "$TEST/"*.kt 2>/dev/null; then
        echo "FAIL: WarnReason.$warn has no test assertion"; FAIL=1
    fi
done

echo "=== 7/9. Cross-file class references ==="
for cls in EmergencyWhitelist DomesticSpoofDetector ScamPrefixSeed BusinessDirectoryBundle \
           SpamCache WangiriTracker NotificationRateLimiter PauseTile RoleMonitor TrustNotifier \
           SilentBlockerService OnboardingActivity OrangeWidget CallStateObserver WeeklyDigest \
           FamilyCallback FamilyCallbackTile PoliceStationDirectory EngineWarmup \
           CaribbeanPremiumNANP OutboundGuard WarningNotifier PhoneNumbers \
           RepeatCallerTracker PostCallAdvisor SaltVault \
           AllowSuffixStore BlockHistoryStore HistoryActivity SettingsActivity; do
    if ! grep -rq "object $cls\|class $cls" "$MAIN/"*.kt 2>/dev/null; then
        echo "FAIL: $cls referenced but not defined"; FAIL=1
    fi
done
# CallDecision.kt defines top-level functions, not a class. Verify the file exists.
if [ ! -f "$MAIN/CallDecision.kt" ]; then
    echo "FAIL: CallDecision.kt missing"; FAIL=1
fi

echo "=== 8/9. Decision oracle (behavioral tripwire) ==="
if command -v python3 >/dev/null 2>&1; then
    if ! python3 tools/oracle_decision.py; then
        echo "FAIL: decision oracle detected a behavioral regression"; FAIL=1
    fi
else
    echo "SKIP: python3 not available (oracle runs in CI)"
fi

echo "=== 9/9. Required documentation present ==="
for doc in README.md PRIVACY_MANIFESTO.md HONESTY_ADDENDUM.md THREAT_MODEL.md \
           DESIGN_NOTES.md RESEARCH_BASIS.md CHANGELOG.md SECURITY.md \
           CONTRIBUTING.md DEVELOPING.md LICENSE \
           docs/adr/001-pause-before-withheld.md \
           docs/adr/002-020-only-not-02x.md \
           docs/adr/003-warn-payload-single-lookup.md \
           docs/adr/004-special-prefix-digit-lengths.md \
           docs/adr/005-no-manual-callscreening-start.md \
           docs/adr/006-hashed-spam-cache.md; do
    if [ ! -f "$doc" ]; then
        echo "FAIL: required document missing: $doc"; FAIL=1
    fi
done

echo ""
echo "=== SUMMARY ==="
echo "Main .kt:  $(find app/src/main -name '*.kt' | wc -l) files, $(find app/src/main -name '*.kt' | xargs wc -l | tail -1 | awk '{print $1}') LOC"
echo "Test .kt:  $(find app/src/test -name '*.kt' | wc -l) files, $(find app/src/test -name '*.kt' | xargs wc -l | tail -1 | awk '{print $1}') LOC"
echo "Tests:     $(grep -c '@Test' $TEST/*.kt 2>/dev/null | awk -F: '{s+=$2} END {print s}')"
echo "XML:       $(find $RES -name '*.xml' | wc -l)"
echo "Locales:   $(ls -d $RES/values* | wc -l)"
echo "All files: $(find . -type f ! -path '*/.git/*' | wc -l)"

echo ""
echo "NOTE: This is STATIC analysis only. It does NOT execute unit tests."
echo "      Behavioral regressions (e.g. wrong digit-length rules) are caught"
echo "      ONLY by 'gradlew testReleaseUnitTest' in a JVM environment."
echo "      Always run the JVM test suite before tagging a release (see RELEASE GATE)."

if [ "$FAIL" -eq 0 ]; then
    echo "RESULT: ALL CHECKS PASSED"
else
    echo "RESULT: FAILED"
fi
exit "$FAIL"

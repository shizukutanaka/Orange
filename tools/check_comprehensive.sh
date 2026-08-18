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

echo "=== 1/11. Privacy guard ==="
bash tools/check_no_network.sh app/src/main || FAIL=1

echo "=== 2/11. R.string coverage ==="
for str in $(grep -ohP 'R\.string\.\K\w+' "$MAIN"/*.kt 2>/dev/null | sort -u); do
    if ! grep -q "name=\"$str\"" "$RES/values/strings.xml"; then
        echo "FAIL: R.string.$str not declared"; FAIL=1
    fi
done

echo "=== 3/11. R.color coverage ==="
for col in $(grep -ohP '@color/\K\w+' "$RES/values/themes.xml" 2>/dev/null | sort -u); do
    if ! grep -q "name=\"$col\"" "$RES/values/colors.xml"; then
        echo "FAIL: @color/$col not declared"; FAIL=1
    fi
done

echo "=== 4/11. Manifest class definitions ==="
for cls in $(grep -oP 'android:name="\.\K[^"]+' app/src/main/AndroidManifest.xml); do
    if ! grep -rql "class $cls" "$MAIN/"*.kt 2>/dev/null; then
        echo "FAIL: Manifest .$cls has no class"; FAIL=1
    fi
done

echo "=== 5/11. Emergency coverage ==="
for n in 110 119 118 911 112 999 000; do
    if ! grep -q "\"$n\"" "$MAIN/EmergencyWhitelist.kt"; then
        echo "FAIL: Emergency $n missing"; FAIL=1
    fi
done

echo "=== 6/11. BlockReason + WarnReason test coverage ==="
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

echo "=== 7/11. Cross-file class references ==="
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

echo "=== 8/11. Decision oracle (behavioral tripwire) ==="
if command -v python3 >/dev/null 2>&1; then
    if ! python3 tools/oracle_decision.py; then
        echo "FAIL: decision oracle detected a behavioral regression"; FAIL=1
    fi
else
    echo "SKIP: python3 not available (oracle runs in CI)"
fi

echo "=== 9/11. Business directory integrity ==="
CSV="app/src/main/assets/business_directory.csv"
if [ -f "$CSV" ]; then
    # Duplicate E.164 keys cause the later entry to silently overwrite the earlier
    # one in the HashMap. Every key must appear exactly once.
    dups=$(grep -v '^#' "$CSV" | grep -v '^[[:space:]]*$' | cut -d',' -f1 | sort | uniq -d)
    if [ -n "$dups" ]; then
        echo "FAIL: duplicate E.164 keys in $CSV:"
        echo "$dups"
        FAIL=1
    fi
    # Every non-comment, non-blank line must have exactly the format "<key>,<name>".
    bad=$(grep -v '^#' "$CSV" | grep -v '^[[:space:]]*$' | grep -v ',')
    if [ -n "$bad" ]; then
        echo "FAIL: malformed lines (no comma) in $CSV:"
        echo "$bad"
        FAIL=1
    fi
else
    echo "FAIL: $CSV missing"
    FAIL=1
fi

echo "=== 10/11. Locale key parity (en / ja / zh / ko) ==="
# Hard failure, deliberately. tools/check_static.sh used to check this as a
# WARN and that is exactly how zh/ko once shipped 21 keys short, silently
# falling back to English in the Settings, tax-warning and outbound-warning UI.
# A warning nobody reads is not a gate.
_base=$(grep -o 'name="[a-z_0-9]*"' app/src/main/res/values/strings.xml | sort -u)
for _loc in values-ja values-zh values-ko; do
    _f="app/src/main/res/$_loc/strings.xml"
    if [ ! -f "$_f" ]; then
        echo "FAIL: $_f missing"; FAIL=1; continue
    fi
    _cur=$(grep -o 'name="[a-z_0-9]*"' "$_f" | sort -u)
    if [ "$_base" != "$_cur" ]; then
        echo "FAIL: $_loc key set differs from values/"
        diff <(echo "$_base") <(echo "$_cur") | head -20
        FAIL=1
    fi
done

echo "=== 11/11. Required documentation present ==="
for doc in README.md PRIVACY_MANIFESTO.md HONESTY_ADDENDUM.md THREAT_MODEL.md \
           DESIGN_NOTES.md RESEARCH_BASIS.md CHANGELOG.md SECURITY.md \
           CONTRIBUTING.md DEVELOPING.md LICENSE \
           docs/adr/001-pause-before-withheld.md \
           docs/adr/002-020-only-not-02x.md \
           docs/adr/003-warn-payload-single-lookup.md \
           docs/adr/004-special-prefix-digit-lengths.md \
           docs/adr/005-no-manual-callscreening-start.md \
           docs/adr/006-hashed-spam-cache.md \
           docs/adr/007-fullwidth-normalization.md \
           docs/adr/008-masked-block-history.md \
           docs/adr/009-fix-foreign-generic-coverage.md \
           docs/adr/010-e164-mobile-layer15.md \
           docs/adr/011-police-before-stir-shaken.md \
           docs/adr/012-domestic-e164-variant-expansion.md; do
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

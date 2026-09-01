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

echo "=== 1/14. Privacy guard ==="
bash tools/check_no_network.sh app/src/main || FAIL=1

echo "=== 2/14. R.string coverage ==="
for str in $(grep -ohP 'R\.string\.\K\w+' "$MAIN"/*.kt 2>/dev/null | sort -u); do
    if ! grep -q "name=\"$str\"" "$RES/values/strings.xml"; then
        echo "FAIL: R.string.$str not declared"; FAIL=1
    fi
done

echo "=== 3/14. R.color coverage ==="
for col in $(grep -ohP '@color/\K\w+' "$RES/values/themes.xml" 2>/dev/null | sort -u); do
    if ! grep -q "name=\"$col\"" "$RES/values/colors.xml"; then
        echo "FAIL: @color/$col not declared"; FAIL=1
    fi
done

echo "=== 4/14. Manifest class definitions ==="
for cls in $(grep -oP 'android:name="\.\K[^"]+' app/src/main/AndroidManifest.xml); do
    if ! grep -rql "class $cls" "$MAIN/"*.kt 2>/dev/null; then
        echo "FAIL: Manifest .$cls has no class"; FAIL=1
    fi
done

echo "=== 5/14. Emergency coverage ==="
for n in 110 119 118 911 112 999 000; do
    if ! grep -q "\"$n\"" "$MAIN/EmergencyWhitelist.kt"; then
        echo "FAIL: Emergency $n missing"; FAIL=1
    fi
done

echo "=== 6/14. BlockReason + WarnReason test coverage ==="
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

echo "=== 7/14. Cross-file class references ==="
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

echo "=== 8/14. Decision oracle (behavioral tripwire) ==="
if command -v python3 >/dev/null 2>&1; then
    if ! python3 tools/oracle_decision.py; then
        echo "FAIL: decision oracle detected a behavioral regression"; FAIL=1
    fi
else
    echo "SKIP: python3 not available (oracle runs in CI)"
fi

echo "=== 9/14. Business directory integrity ==="
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

echo "=== 10/14. Locale key parity (en / ja / zh / ko) ==="
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

echo "=== 11/14. Doc/data count drift ==="
# This repo produces one defect class over and over: a count stated in prose
# drifts from the data it describes. Fixed by hand this session alone: police
# numbers 47->54, ADRs 11->12, test count 199->276->285, and the privacy
# manifesto whose own heading said 7 while README said 9 and the list held 10.
# Hand-counting is the bug. These assertions are the fix.
_drift() {  # name, actual, doc, claimed
    if [ "$2" != "$4" ]; then
        echo "FAIL: $1 — code/data says $2, $3 says $4"; FAIL=1
    fi
}
_adr_actual=$(ls docs/adr/*.md 2>/dev/null | wc -l | tr -d ' ')
_adr_doc=$(grep -oE 'Architecture Decision Records \([0-9]+\)' README.md | grep -oE '[0-9]+')
_drift "ADR count" "$_adr_actual" "README.md" "$_adr_doc"

_pol_actual=$(grep -cE '^\s+"\+?[0-9]+" to ' app/src/main/java/com/orange/apple/PoliceStationDirectory.kt)
_pol_doc=$(grep -oE '[0-9]+ numbers \(47 prefectural' README.md | grep -oE '^[0-9]+')
_drift "Police directory size" "$_pol_actual" "README.md" "$_pol_doc"

# NB: match entries with or without a trailing comma — the final element has
# none, and requiring one is how an earlier attempt at this check miscounted 20
# as 19 and nearly "corrected" a document that was right.
_cc_actual=$(sed -n '/val elevatedRiskCountryCodes/,/^    )/p' \
             app/src/main/java/com/orange/apple/ScamPrefixSeed.kt \
             | grep -cE '^\s+"[0-9]+"')
for _f in README.md SPECIFICATION.md; do
    _cc_doc=$(grep -oE '[0-9]+ country codes' "$_f" | grep -oE '^[0-9]+' | head -1)
    [ -n "$_cc_doc" ] && _drift "Elevated-risk country codes" "$_cc_actual" "$_f" "$_cc_doc"
done

_pm_actual=$(grep -cE '^[0-9]+\. \*\*' PRIVACY_MANIFESTO.md)
[ "$_pm_actual" -ne 10 ] && { echo "FAIL: PRIVACY_MANIFESTO has $_pm_actual items; README/CONTRIBUTING describe 10 (8 refusals + 2 bounded)"; FAIL=1; }

echo "=== 12/14. Version identity (APK / CHANGELOG / store metadata) ==="
# Version identity must agree across the APK, the changelog and both store
# metadata sets. It did not (2026-07): build.gradle.kts said 1.1.0/code 2 while
# CHANGELOG, README and RELEASING all said 1.6.0, and the F-Droid file still said
# 1.0.0 with a commit tag that never existed — so the "v1.6.0 release" would have
# shipped an APK calling itself 1.1.0.
_vn=$(grep -oE 'versionName = "[0-9.]+"' app/build.gradle.kts | grep -oE '[0-9.]+')
_vc=$(grep -oE 'versionCode = [0-9]+' app/build.gradle.kts | grep -oE '[0-9]+')
_cl=$(grep -oE '^## \[[0-9.]+\]' CHANGELOG.md | head -1 | grep -oE '[0-9.]+')
[ "$_vn" != "$_cl" ] && { echo "FAIL: versionName $_vn != newest CHANGELOG entry $_cl"; FAIL=1; }
_fd=$(grep -oE 'CurrentVersion: [0-9.]+' metadata/com.orange.apple.yml | grep -oE '[0-9.]+')
[ "$_fd" != "$_vn" ] && { echo "FAIL: F-Droid CurrentVersion $_fd != versionName $_vn"; FAIL=1; }
_fdc=$(grep -oE 'CurrentVersionCode: [0-9]+' metadata/com.orange.apple.yml | grep -oE '[0-9]+')
[ "$_fdc" != "$_vc" ] && { echo "FAIL: F-Droid CurrentVersionCode $_fdc != versionCode $_vc"; FAIL=1; }
# Play/F-Droid changelogs are named by versionCode; the current one must exist.
[ ! -f "fastlane/metadata/android/changelogs/$_vc.txt" ] && \
    { echo "FAIL: fastlane/metadata/android/changelogs/$_vc.txt missing for versionCode $_vc"; FAIL=1; }
# Unreplaced scaffold placeholders in shipped metadata.
if grep -q "OWNER" metadata/com.orange.apple.yml; then
    echo "FAIL: metadata/com.orange.apple.yml still contains the OWNER placeholder"; FAIL=1
fi

echo "=== 13/14. ProGuard keeps every manifest component ==="
# proguard-rules.pro states its own contract: "we make it explicit so a future
# package rename doesn't silently break screening or notifications". It listed
# 10 of 12 components (2026-07) — HistoryActivity and SettingsActivity were
# missing. R8 keeps them via manifest scanning so nothing was broken, but the
# guarantee the file claims to provide was incomplete. This asserts the claim.
grep -oE 'android:name="\.[A-Za-z]+"' app/src/main/AndroidManifest.xml \
    | sed 's/android:name="\.//; s/"//' | sort -u > /tmp/_mani_components
grep -oE '^-keep class com\.orange\.apple\.[A-Za-z]+' app/proguard-rules.pro \
    | sed 's/.*apple\.//' | sort -u > /tmp/_pg_keeps
_missing=$(comm -23 /tmp/_mani_components /tmp/_pg_keeps | tr '\n' ' ')
if [ -n "$(echo "$_missing" | tr -d ' ')" ]; then
    echo "FAIL: manifest components absent from proguard-rules.pro: $_missing"; FAIL=1
fi

echo "=== 14/14. Required documentation present ==="
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

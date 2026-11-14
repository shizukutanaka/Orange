#!/usr/bin/env bash
# Verifies the release APK is below our published size budget.
# Larger APK = more code = more privacy surface to audit. Below a strict
# ceiling, drift can't sneak in.

set -euo pipefail

APK_PATH="${1:-app/build/outputs/apk/release/app-release.apk}"
# Budget history / justification (2026-11-15): the original 1 MiB ceiling was
# set before CI had ever run this far, so it was never validated against the
# real app. The first full CI run measured the release APK at 1.3 MiB. That
# growth is legitimate: the Ring integration (PR #10) added prefix-match
# blocking rules, number lookup, JSON blocklist import/export and their
# strings in 4 locales. This budget exists to detect bloat, not to freeze the
# app at an unmeasured size, so it is set to measured size + ~15% headroom.
# If this needs raising again, record the new measurement and the reason here.
BUDGET_BYTES="${2:-1572864}"   # 1.5 MiB hard ceiling (measured 1.3 MiB, 2026-11-15 CI)

if [ ! -f "$APK_PATH" ]; then
    APK_PATH=$(find app/build/outputs/apk/release -name '*.apk' 2>/dev/null | head -1 || echo "")
fi

if [ -z "$APK_PATH" ] || [ ! -f "$APK_PATH" ]; then
    echo "::error::No release APK found. Run ./gradlew assembleRelease first."
    exit 1
fi

size=$(stat -c%s "$APK_PATH" 2>/dev/null || stat -f%z "$APK_PATH")
human=$(numfmt --to=iec-i --suffix=B "$size" 2>/dev/null || echo "${size} bytes")
budget_human=$(numfmt --to=iec-i --suffix=B "$BUDGET_BYTES" 2>/dev/null || echo "${BUDGET_BYTES} bytes")

echo "APK: $APK_PATH"
echo "Size: $human"
echo "Budget: $budget_human"

if [ "$size" -gt "$BUDGET_BYTES" ]; then
    echo "::error::APK exceeds budget ($human > $budget_human). Justify or shrink."
    exit 1
fi

echo "PASS: under budget."

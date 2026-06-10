#!/usr/bin/env bash
# Verifies the release APK is below our published size budget.
# Larger APK = more code = more privacy surface to audit. Below a strict
# ceiling, drift can't sneak in.

set -euo pipefail

APK_PATH="${1:-app/build/outputs/apk/release/app-release.apk}"
BUDGET_BYTES="${2:-1048576}"   # 1 MiB hard ceiling; target is <200 KB

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

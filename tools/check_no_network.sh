#!/usr/bin/env bash
#
# Privacy guard: fail the build if anyone introduces network code into the
# main source tree. The privacy manifesto promises Orange has no INTERNET
# permission and makes no network calls. This script is the automated
# enforcement of that promise — it must run in CI on every push.
#
# A reviewer can confirm this guard works by adding `URL("https://x")` to
# any .kt file in app/src/main/ and watching the next CI run fail.

set -euo pipefail

SRC_DIR="${1:-app/src/main}"
EXIT_CODE=0

# Patterns that indicate network use. Each is paired with a human-readable
# reason so a future contributor sees WHY the line is rejected, not just THAT.
declare -A FORBIDDEN=(
    ["java\\.net\\.URL"]="Direct java.net.URL usage — Orange has no network permission"
    ["java\\.net\\.HttpURLConnection"]="HttpURLConnection — Orange does not make HTTP calls"
    ["okhttp"]="OkHttp dependency — Orange has no HTTP client"
    ["retrofit"]="Retrofit dependency — Orange has no API client"
    ["volley"]="Volley dependency — Orange has no HTTP client"
    ["ktor"]="Ktor dependency — Orange has no HTTP client"
    ["firebase"]="Firebase SDK — Orange has no analytics or backend"
    ["crashlytics"]="Crashlytics SDK — Orange has no remote crash reporting"
    ["sentry"]="Sentry SDK — Orange has no remote error reporting"
    ["amplitude"]="Amplitude SDK — Orange has no analytics"
    ["mixpanel"]="Mixpanel SDK — Orange has no analytics"
    ["WorkManager"]="WorkManager — Orange has no background sync (would imply network)"
    ["WebView"]="WebView — Orange does not embed any web content"
)

for pattern in "${!FORBIDDEN[@]}"; do
    reason="${FORBIDDEN[$pattern]}"
    matches=$(grep -RInE "$pattern" "$SRC_DIR" \
              --include='*.kt' --include='*.java' --include='*.xml' \
              --include='*.gradle.kts' --include='*.gradle' || true)
    if [ -n "$matches" ]; then
        echo "::error::Privacy guard hit: $reason"
        echo "$matches"
        EXIT_CODE=1
    fi
done

# INTERNET permission must NEVER appear in any manifest under main src.
if grep -RInE 'android\.permission\.INTERNET' "$SRC_DIR" \
   --include='*.xml' >/dev/null 2>&1; then
    echo "::error::INTERNET permission was added to a manifest. The product's primary privacy claim forbids it."
    EXIT_CODE=1
fi

if [ "$EXIT_CODE" -eq 0 ]; then
    echo "Privacy guard passed: no network code found in $SRC_DIR"
fi

exit "$EXIT_CODE"

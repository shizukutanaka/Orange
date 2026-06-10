#!/usr/bin/env bash
set -uo pipefail
FAILED=0

MAIN="app/src/main/java/com/orange/apple"
RES="app/src/main/res"
MANIFEST="app/src/main/AndroidManifest.xml"

echo "=== 1. Privacy guard ==="
bash tools/check_no_network.sh app/src/main || FAILED=1

echo "=== 2. R.string coverage ==="
for str in $(grep -ohP 'R\.string\.\K\w+' "$MAIN"/*.kt 2>/dev/null | sort -u); do
    if ! grep -q "name=\"$str\"" "$RES/values/strings.xml"; then
        echo "FAIL: R.string.$str not declared"; FAILED=1
    fi
done

echo "=== 3. R.color coverage ==="
for col in $(grep -ohP '@color/\K\w+' "$RES/values/themes.xml" 2>/dev/null | sort -u); do
    if ! grep -q "name=\"$col\"" "$RES/values/colors.xml"; then
        echo "FAIL: @color/$col not declared"; FAILED=1
    fi
done

echo "=== 4. Manifest class definitions ==="
for cls in $(grep -oP 'android:name="\.\K[^"]+' "$MANIFEST"); do
    if ! grep -rql "class $cls" "$MAIN/"*.kt 2>/dev/null; then
        echo "FAIL: Manifest .$cls has no class definition"; FAILED=1
    fi
done

echo "=== 5. Emergency coverage ==="
for n in 110 119 118 911 112 999 000; do
    if ! grep -q "\"$n\"" "$MAIN/EmergencyWhitelist.kt"; then
        echo "FAIL: Emergency $n missing"; FAILED=1
    fi
done

echo "=== 6. Locale parity ==="
base=$(grep -oP 'name="\K[^"]+' "$RES/values/strings.xml" | sort)
for d in "$RES"/values-*/; do
    [ -f "$d/strings.xml" ] || continue
    locale=$(basename "$d")
    locale_keys=$(grep -oP 'name="\K[^"]+' "$d/strings.xml" | sort)
    for key in $base; do
        echo "$locale_keys" | grep -qx "$key" || echo "WARN: $locale missing key $key"
    done
done

if [ "$FAILED" -eq 0 ]; then echo "All static checks passed."; else echo "FAILED"; fi
exit "$FAILED"

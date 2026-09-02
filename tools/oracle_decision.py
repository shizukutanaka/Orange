#!/usr/bin/env python3
"""
Reference oracle for the Orange decision engine's number-classification rules.

WHAT THIS DOES AND DOES NOT DETECT  (read this before trusting a green run)
--------------------------------------------------------------------------
This script re-implements the numbering-plan rules in Python and checks them
against its own table of known-good / known-bad numbers. That is the whole
mechanism, and it has a consequence people keep assuming away:

    IT CANNOT DETECT A CHANGE IN THE KOTLIN ENGINE.

Measured, not argued (2026-07). Changing DomesticSpoofDetector's
repeating-digit threshold from 8 to 9 — a real behavioural regression —
produced:

    tools/oracle_decision.py    exit 0, "All 31 oracle cases passed"
    tools/run-pure-tests.sh     exit 1, naming the two tests that broke

So "oracle passed" means "the Python is still consistent with the Python". It
is a second opinion that a human must compare by hand, not a tripwire. The old
header claimed otherwise ("if the Python oracle and the Kotlin source disagree,
one of them is wrong — investigate") — true, but nothing here does the
comparing, so nobody was ever told to investigate.

The premise it was written under has also expired. It assumed local iteration
"WITHOUT a JVM (e.g. a container that only has Python)". tools/run-pure-tests.sh
now runs 435 real Kotlin tests in ~30s with no Android SDK and no network, so
the engine can be exercised directly almost anywhere this script can run.

Kept for now because it costs nothing and a divergence IS visible to a reader
who checks both. Whether to keep paying for a hand-synced duplicate is recorded
as an open question in docs/FEATURE_AUDIT.md §1-13 — deleting a check is a call
for a maintainer, not a cleanup.

Authoritative for behaviour: the Kotlin, exercised by tools/run-pure-tests.sh
and by `gradlew testReleaseUnitTest`.

USAGE
-----
    python3 tools/oracle_decision.py          # run all cases, exit 1 on failure

KEEP IN SYNC
------------
When you change a digit-length rule in DomesticSpoofDetector.kt, change it here
too, and add the new case to CASES below. The two must always agree.
"""
import sys

ELEVEN_DIGIT_PREFIXES = ("050", "060", "070", "080", "090", "0800")
TEN_DIGIT_PREFIXES = ("0120", "0570", "0990")


def to_domestic(n: str):
    """E.164 (+81…) or domestic (0…) → domestic string; None if not JP."""
    if n.startswith("+81"):
        rest = n[3:]
        return rest if rest.startswith("0") else "0" + rest
    if n.startswith("0") and n.isdigit():
        return n
    return None


def has_eight_repeating(s: str) -> bool:
    if len(s) < 8:
        return False
    run = 1
    for i in range(1, len(s)):
        if s[i] == s[i - 1]:
            run += 1
            if run >= 8:
                return True
        else:
            run = 1
    return False


def is_impossible_jp(n: str) -> bool:
    """Mirror of DomesticSpoofDetector.isImpossibleJpNumber."""
    d = to_domestic(n)
    if d is None:
        return False
    if d.startswith("020"):
        return True
    if any(d.startswith(p) for p in ELEVEN_DIGIT_PREFIXES) and len(d) != 11:
        return True
    if any(d.startswith(p) for p in TEN_DIGIT_PREFIXES) and len(d) != 10:
        return True
    if has_eight_repeating(d):
        return True
    if not (10 <= len(d) <= 11):
        return True
    if len(d) > 1 and d[1] == "0":
        return True
    # Geographic landlines (03/06/etc.) are always exactly 10 digits per MIC plan.
    # An 11-digit number with a non-eleven-digit-service prefix is a spoof.
    # Mirrors: val isElevenDigitService = ELEVEN_DIGIT_PREFIXES.any { d.startsWith(it) }
    #          if (!isElevenDigitService && d.length == 11) return true
    is_eleven_digit_service = any(d.startswith(p) for p in ELEVEN_DIGIT_PREFIXES)
    if not is_eleven_digit_service and len(d) == 11:
        return True
    return False


# (number, expected_is_impossible, description)
CASES = [
    # 020 = M2M/IoT or defunct pager — never a human voice caller
    ("02012345678", True, "020 M2M/pager — impossible human voice caller"),
    ("0201234567", True, "020 any length — blocked"),
    # geographic area codes 022-029 are VALID (the famous regression)
    ("0222211611", False, "022 Sendai police HQ — must NOT be spoof"),
    ("0236265211", False, "023 Yamagata"),
    ("0245221111", False, "024 Fukushima"),
    ("0293011621", False, "029 Mito"),
    # freephone / navi-dial / teledome lengths
    ("0120123456", False, "0120 freephone valid 10-digit"),
    ("01201234567", True, "0120 wrong 11-digit"),
    ("08001234567", False, "0800 freephone valid 11-digit"),
    ("0800123456", True, "0800 wrong 10-digit"),
    ("0570123456", False, "0570 navi-dial valid 10-digit"),
    ("05701234567", True, "0570 wrong 11-digit"),
    ("0990123456", False, "0990 teledome valid 10-digit"),
    # IP phone
    ("05012345678", False, "050 IP valid 11-digit"),
    ("0501234567", True, "050 too short 10-digit"),
    # mobiles
    ("09012345678", False, "090 mobile valid 11-digit"),
    ("08012345678", False, "080 mobile valid"),
    ("07012345678", False, "070 mobile valid"),
    ("06012345678", False, "060 mobile valid (2025 MIC allocation)"),
    ("0901234567", True, "090 too short 10-digit"),
    # repeating-digit robot artifact
    ("09011111111", True, "090 with 8 repeating 1s"),
    ("0311111111", True, "03 landline with 8 repeating 1s"),
    # landline
    ("0312345678", False, "03 Tokyo landline valid 10-digit"),
    # 11-digit geographic landlines are spoofs (MIC plan: geographic = exactly 10 digits)
    ("03123456789", True, "03 Tokyo 11-digit — spoof"),
    ("06123456789", True, "06 Osaka 11-digit — spoof"),
    ("0222123456789", True, "022 Sendai 13-digit — spoof"),
    # 00x intl access used domestically
    ("00123456789", True, "00x intl-access prefix domestic"),
    # +810… — carrier-mangled E.164 (domestic leading zero not stripped before +81 prefix)
    # to_domestic("+810335814321") → "0335814321" (valid Tokyo) → not impossible
    ("+810335814321", False, "+810… Tokyo landline — carrier kept leading zero, still valid"),
    # +8100… — double zero after +81 maps to domestic "00…" (intl-access prefix) → impossible
    ("+8100312345678", True, "+8100… intl-access domestic form — impossible"),
    # non-JP — out of scope, never flagged
    ("+12125551234", False, "US number — not JP, out of scope"),
    ("+447911123456", False, "UK number — not JP, out of scope"),
]


def main() -> int:
    failures = 0
    for number, expected, desc in CASES:
        actual = is_impossible_jp(number)
        if actual != expected:
            failures += 1
            print(f"FAIL: {desc} ({number}) → {actual}, expected {expected}")
    total = len(CASES)
    if failures:
        print(f"\n{failures}/{total} oracle cases FAILED")
        return 1
    # Deliberately not "all checks passed": this verifies the Python against the
    # Python. See the module docstring — it cannot see a Kotlin regression.
    print(f"All {total} oracle cases passed (Python rules self-check only —")
    print("  engine behaviour is verified by tools/run-pure-tests.sh)")
    return 0


if __name__ == "__main__":
    sys.exit(main())

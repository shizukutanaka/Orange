package com.orange.apple

/**
 * "Check a number" — the lookup half of Ring's spam blocker, ported in
 * the 2026-11 consolidation. Ring let the user type a number and see
 * what the engine thought of it before it ever called; Orange only
 * ever reacted to live calls. This is the pure core of that lookup:
 * parse and classify the number, then report which of the user's own
 * protections (prefix rules, spam cache, numbering-plan spoof check)
 * would apply to it.
 *
 * The parsing/classification semantics are Ring's
 * (app.ring.spamblocker.core.PhoneNumbers), adapted to sit on top of
 * Orange's PhoneNumbers.normalize for character cleanup:
 *
 *  - Accepts JP domestic (0…), +81…, 0081… / 01081… access-prefix
 *    forms, bare 81… international form, and other +CC numbers.
 *    Full-width digits are folded by normalize().
 *  - Validity is Ring's bar: a JP national number is ^0\d{9,10}$;
 *    a non-JP international number is 8–15 digits overall.
 *  - Classification is display-only. It never feeds decide(): the
 *    engine's own layers (DomesticSpoofDetector etc.) remain the sole
 *    decision-makers, so a coarse label here can never block a call.
 */
internal object NumberInspector {

    enum class Kind { TOLLFREE, IP_PHONE, MOBILE, NAVI_DIAL, FIXEDLINE, INTERNATIONAL, OTHER, INVALID }

    data class Result(
        /** The cleaned input (digits + optional leading '+'), null when empty. */
        val normalized: String?,
        val valid: Boolean,
        val kind: Kind,
        /** JP domestic form ("090…") when derivable, else null. */
        val nationalForm: String?,
        /** Grouped display of [nationalForm] (Ring's formatNational), else null. */
        val formatted: String?,
        /** The user's prefix rule this number matches, if any. */
        val matchedPrefixRule: PrefixRule?,
        /** Whether the number is in the user's spam cache (resolved by the caller). */
        val spamCached: Boolean,
        /** Whether the number cannot exist in the JP numbering plan. */
        val impossibleJp: Boolean,
    )

    private val JP_NATIONAL_REGEX = Regex("^0\\d{9,10}$")
    private val JP_FIXED_REGEX = Regex("^0\\d{9}$")

    /** Classify a JP national number (0…). Ported from Ring's classifyJP. */
    fun classifyJp(national: String): Kind {
        if (national.startsWith("0800") || national.startsWith("0120")) return Kind.TOLLFREE
        if (national.startsWith("050")) return Kind.IP_PHONE
        if (national.startsWith("070") || national.startsWith("080") ||
            national.startsWith("090") || national.startsWith("060")
        ) return Kind.MOBILE
        if (national.startsWith("0570")) return Kind.NAVI_DIAL
        if (JP_FIXED_REGEX.matches(national)) return Kind.FIXEDLINE
        return Kind.OTHER
    }

    /** Human-readable grouping for JP national numbers. Ported from Ring. */
    fun formatNational(national: String?): String {
        if (national == null) return ""
        val d = national
        if (d.length == 11) return d.substring(0, 3) + "-" + d.substring(3, 7) + "-" + d.substring(7)
        if (d.length == 10) {
            if (d.startsWith("0120") || d.startsWith("0570") || d.startsWith("0990")) {
                return d.substring(0, 4) + "-" + d.substring(4, 7) + "-" + d.substring(7)
            }
            if (d.startsWith("03") || d.startsWith("06")) {
                return d.substring(0, 2) + "-" + d.substring(2, 6) + "-" + d.substring(6)
            }
            return d.substring(0, 3) + "-" + d.substring(3, 6) + "-" + d.substring(6)
        }
        return d
    }

    fun inspect(
        raw: String,
        prefixRules: List<PrefixRule>,
        isSpamCached: Boolean,
        calleeCountryIso: String?,
    ): Result {
        val cleaned = PhoneNumbers.normalize(raw)
        if (cleaned.isEmpty() || cleaned == "+") {
            return Result(null, false, Kind.INVALID, null, null, null, false, false)
        }

        // The branching below mirrors Ring's normalize step for step:
        // access-prefix forms first, then the international branch
        // (where a +81 number of JP length is validated AS a JP number,
        // and only an out-of-range length falls through to generic
        // international), then the domestic branch.
        var digits = cleaned.removePrefix("+")
        var intl = cleaned.startsWith("+")
        if (!intl && (digits.startsWith("0081") || digits.startsWith("01081"))) {
            digits = "81" + if (digits.startsWith("01081")) digits.substring(5) else digits.substring(4)
            intl = true
        }

        if (intl || (digits.startsWith("81") && digits.length >= 12)) {
            if (digits.startsWith("81") && digits.length in 11..13) {
                val national = "0" + digits.substring(2)
                return if (JP_NATIONAL_REGEX.matches(national)) {
                    result(cleaned, national, classifyJp(national), prefixRules, isSpamCached)
                } else {
                    Result(cleaned, false, Kind.INVALID, null, null, null, false, false)
                }
            }
            // Non-JP international: Ring's bar was 8..15 digits total.
            return if (digits.length in 8..15) {
                Result(
                    normalized = cleaned,
                    valid = true,
                    kind = Kind.INTERNATIONAL,
                    nationalForm = null,
                    formatted = null,
                    matchedPrefixRule = matchPrefixRule(cleaned, calleeCountryIso, prefixRules),
                    spamCached = isSpamCached,
                    impossibleJp = false,
                )
            } else {
                Result(cleaned, false, Kind.INVALID, null, null, null, false, false)
            }
        }

        if (JP_NATIONAL_REGEX.matches(digits)) {
            return result(cleaned, digits, classifyJp(digits), prefixRules, isSpamCached)
        }
        return Result(cleaned, false, Kind.INVALID, null, null, null, false, false)
    }

    private fun result(
        cleaned: String,
        national: String,
        kind: Kind,
        prefixRules: List<PrefixRule>,
        isSpamCached: Boolean,
    ): Result {
        val e164 = "+81" + national.substring(1)
        return Result(
            normalized = cleaned,
            valid = true,
            kind = kind,
            nationalForm = national,
            formatted = formatNational(national),
            matchedPrefixRule = matchPrefixRule(e164, "JP", prefixRules),
            spamCached = isSpamCached,
            impossibleJp = DomesticSpoofDetector.isImpossibleJpNumber(national),
        )
    }
}

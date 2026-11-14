package com.orange.apple

/**
 * User-defined prefix block rules: "silence every call from a number that
 * starts with these digits".
 *
 * Ported from Ring's spam blocker (app.ring.spamblocker.core, block-list
 * schema v1) as part of the 2026-11 consolidation that folded Ring into
 * Orange. Ring is archived; this file is now the canonical home of the
 * rule model, the input canonicalization, and the matching semantics.
 * The semantics are Ring's, unchanged:
 *
 *  - A rule is a digit string plus a scope.
 *  - scope "national": the digits are matched against the caller's
 *    domestic form (0-led for JP), e.g. rule "0800" blocks every
 *    0800 number.
 *  - scope "e164": the digits are matched against the caller's E.164
 *    digits (country code included, no '+'), e.g. rule "8190" blocks
 *    every +81 90… number.
 *
 * What Orange adds around that core is placement, not semantics: in
 * decide() the rules sit at Layer 6b, BELOW the trust layers (family,
 * bundled businesses, numbers the user has dialled) and the exact-match
 * spam cache, and ABOVE every heuristic layer. A prefix is a range —
 * it must never outrank a specific number the user is known to trust,
 * and it must always outrank a guess.
 */

/** A prefix (leading-digits) block rule. scope is "national" or "e164". */
data class PrefixRule(val prefix: String, val scope: String) {
    companion object {
        const val SCOPE_NATIONAL = "national"
        const val SCOPE_E164 = "e164"
    }
}

/**
 * Canonicalize a user-entered prefix rule.
 * "0800" / "050-1234" -> national scope; "+8190" -> e164 scope.
 * Returns null when unusable. Ported from Ring's canonicalPrefixRule;
 * cleanup goes through Orange's PhoneNumbers.normalize, which folds
 * full-width digits and strips separators the same way Ring's did.
 */
fun canonicalPrefixRule(input: String?): PrefixRule? {
    if (input == null) return null
    val cleaned = PhoneNumbers.normalize(input)
    var scope = PrefixRule.SCOPE_NATIONAL
    var digits = cleaned
    if (cleaned.startsWith("+")) {
        scope = PrefixRule.SCOPE_E164
        digits = cleaned.substring(1)
    }
    if (!Regex("^\\d{3,15}$").matches(digits)) return null
    if (scope == PrefixRule.SCOPE_NATIONAL && !digits.startsWith("0")) return null
    if (scope == PrefixRule.SCOPE_E164 && digits.startsWith("0")) return null
    return PrefixRule(digits, scope)
}

/**
 * Returns the first rule in [rules] that matches [number], or null.
 *
 * [number] is the engine's normalized form (ASCII digits, optional
 * leading '+') and may be domestic ("090…") or E.164 ("+8190…") for
 * the same caller, so both match targets are derived from it:
 *
 *  - national target: the domestic form. For a "+81…" number that is
 *    "0" + the rest (a carrier-mangled "+81090…" keeps its zero, same
 *    convention as phoneVariants / PoliceStationDirectory.lookup).
 *  - e164 target: the digits after '+', or — for a domestic number —
 *    the home country's calling code (from [calleeCountryIso]) plus
 *    the number without its trunk zero, mirroring phoneVariants.
 *    Unknown home country -> no e164 target (conservative: the rule
 *    simply cannot match, never a false match).
 */
fun matchPrefixRule(number: String, calleeCountryIso: String?, rules: List<PrefixRule>): PrefixRule? {
    if (number.isEmpty() || rules.isEmpty()) return null
    val national = nationalFormOf(number)
    val e164 = e164DigitsOf(number, calleeCountryIso)
    for (rule in rules) {
        val target = if (rule.scope == PrefixRule.SCOPE_E164) e164 else national
        if (target != null && target.startsWith(rule.prefix)) return rule
    }
    return null
}

/** Domestic form of a normalized number, or null when it cannot be derived. */
internal fun nationalFormOf(number: String): String? = when {
    number.startsWith("0") -> number
    number.startsWith("+81") -> {
        val rest = number.substring(3)
        if (rest.startsWith("0")) rest else "0$rest"
    }
    else -> null
}

/** E.164 digits (no '+') of a normalized number, or null when underivable. */
internal fun e164DigitsOf(number: String, calleeCountryIso: String?): String? = when {
    number.startsWith("+") -> number.substring(1)
    number.startsWith("0") -> callingCodeOf(calleeCountryIso)?.let { it + number.substring(1) }
    else -> null
}

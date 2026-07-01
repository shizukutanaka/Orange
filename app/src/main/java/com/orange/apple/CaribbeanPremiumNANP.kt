package com.orange.apple

/**
 * Caribbean and Atlantic NANP premium-rate area codes.
 *
 * These are +1-XXX numbers that look like domestic US/Canada calls but
 * route to expensive international destinations. A callback to +1-242
 * (Bahamas) or +1-876 (Jamaica) can cost ¥180-500/min — identical to the
 * Wangiri premium-rate scam but using a +1 prefix that bypasses the
 * foreign-unsolicited layer (since +1 maps to "US" and many JP users
 * have US contacts).
 *
 * 2025 Tobila/GSMA data: these area codes appear in the top-20 Wangiri
 * callback destinations targeting JP users.
 *
 * Design: checked AFTER outbound-known (a user who has dialed +1-242
 * before trusts that number), but BEFORE the generic foreign layer.
 * Placed inside the premium-rate check in decide().
 */
internal object CaribbeanPremiumNANP {

    /**
     * NANP area codes with disproportionately high Wangiri/IRSF activity.
     * Source: GSMA IRSF fraud corridor list + Tobila 2025 monthly reports.
     */
    val areaCodesAtRisk: Set<String> = setOf(
        "242",  // Bahamas
        "246",  // Barbados
        "264",  // Anguilla
        "268",  // Antigua & Barbuda
        "284",  // British Virgin Islands
        "340",  // US Virgin Islands (lower risk but reported)
        "345",  // Cayman Islands
        "441",  // Bermuda
        "473",  // Grenada
        "649",  // Turks & Caicos
        "664",  // Montserrat
        "721",  // Sint Maarten
        "758",  // Saint Lucia
        "767",  // Dominica
        "784",  // Saint Vincent & Grenadines
        "787",  // Puerto Rico (lower risk)
        "939",  // Puerto Rico (overlay of 787)
        "809",  // Dominican Republic
        "829",  // Dominican Republic (overlay)
        "849",  // Dominican Republic (overlay)
        "868",  // Trinidad & Tobago
        "869",  // Saint Kitts & Nevis
        "876",  // Jamaica
    )

    /**
     * Given a +1-XXXXXXXXXX number, returns true if the area code is in
     * the premium-risk set. Returns false for non-+1 or non-10-digit-local
     * numbers.
     */
    fun isPremiumNANP(normalized: String): Boolean {
        if (!normalized.startsWith("+1")) return false
        val local = normalized.removePrefix("+1")
        // NANP local numbers are exactly 10 digits (3-digit area code + 7-digit number).
        // Reject if not exactly 10 to avoid matching partial or malformed numbers.
        if (local.length != 10) return false
        return local.substring(0, 3) in areaCodesAtRisk
    }
}

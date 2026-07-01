package com.orange.apple

/**
 * Bundled directory of tax-authority representative numbers.
 *
 * 還付金詐欺・税金未納詐欺 (tax-refund / unpaid-tax scam) is one of the largest
 * 特殊詐欺 categories in Japan (警察庁 統計): scammers spoof 国税庁 or local tax
 * office numbers, claim a refund or unpaid balance, and direct the victim to an
 * ATM or a fraudulent payment app. Exactly the same threat shape as ニセ警察詐欺
 * (PoliceStationDirectory) — a real government body whose caller ID is worth
 * spoofing precisely because it's trusted.
 *
 * 国税庁 (+81352533111) was previously listed in business_directory.csv, which
 * grants unconditional silent trust (rings with NO warning, bypasses STIR/SHAKEN
 * verification). That is the wrong treatment for a number scammers impersonate —
 * mirrors the same bug fixed for 警察庁 in PoliceStationDirectory. Moved here so
 * a call still rings (we never block a government agency) but always surfaces
 * the impersonation warning.
 *
 * Only numbers verified from the agency's own public page are listed here, same
 * accuracy bar as business_directory.csv and PoliceStationDirectory.
 */
internal object TaxAgencyDirectory {

    /**
     * Map of domestic number → display name. Keys are stored in domestic form
     * ("0352533111", not "+81352533111"). lookup() accepts both forms.
     */
    val entries: Map<String, String> = mapOf(
        "0352533111" to "国税庁",
    )

    /** Returns the tax-agency display name, or null if not a known number. */
    fun lookup(normalized: String): String? {
        entries[normalized]?.let { return it }
        if (normalized.startsWith("+81")) {
            val rest = normalized.removePrefix("+81")
            val domestic = if (rest.startsWith("0")) rest else "0$rest"
            entries[domestic]?.let { return it }
        }
        return null
    }
}

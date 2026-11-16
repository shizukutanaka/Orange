package com.orange.apple

/**
 * Aggregate view of every country pack's warn-but-ring tax-authority
 * directory.
 *
 * The data itself lives in the country packs (JP: JpCountryPack — the
 * National Tax Agency's representative number, verified from the agency's
 * own public page). This object is the merged view the rest of the app
 * has always consumed; with one pack registered it is exactly the JP
 * directory it was before the pack restructure (ADR 014).
 *
 * Why these numbers get their own directory at all: 還付金詐欺・税金未納
 * 詐欺 (tax-refund / unpaid-tax scam) is one of the largest 特殊詐欺
 * categories in Japan (警察庁 統計) — scammers spoof 国税庁 numbers
 * precisely because they are trusted. Same threat shape as ニセ警察詐欺:
 * a match rings, never blocks, and always surfaces the impersonation
 * warning instead of the business bundle's silent trust.
 */
internal object TaxAgencyDirectory {

    /** Merged tax directories of all registered packs, in pack order. */
    val entries: Map<String, String> = buildMap {
        for (pack in CountryPacks.all) putAll(pack.taxDirectory)
    }

    /** Searches every pack's tax directory, accepting domestic and E.164 forms. */
    fun lookup(normalized: String): String? {
        for (pack in CountryPacks.all) {
            pack.lookupTax(normalized)?.let { return it }
        }
        return null
    }
}

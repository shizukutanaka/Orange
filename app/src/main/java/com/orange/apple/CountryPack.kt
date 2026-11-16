package com.orange.apple

import java.io.BufferedReader

/**
 * Country pack: every piece of bundled, country-specific screening data
 * for one country, behind one contract — so the decision engine never
 * hardcodes a country again.
 *
 * Orange ships data in two tiers (see docs/adr/014-country-pack-structure.md):
 *
 *  1. ASSET packs (file-based): the business directory. Lives at
 *     assets/packs/<code>/business_directory.csv, registered in
 *     assets/packs/index.txt, loaded and merged by BusinessDirectoryBundle.
 *     Adding a country here is data-only: drop the file, add one index line.
 *
 *  2. COMPILED packs (this interface): the safety-critical data — emergency
 *     numbers, the warn-but-ring police/tax directories, the numbering-plan
 *     validity rules, and the elevated-risk caller codes. These ship as
 *     Kotlin constants because they must not be able to fail to load:
 *     a silenced 110 is a killed user, and a police-impersonation warning
 *     that silently disappears because an asset read failed is the exact
 *     failure this app exists to prevent. Compiled data is present at
 *     class-load, always — the same reason EmergencyWhitelist and the
 *     police directory were static maps long before packs existed.
 *
 * Adding a compiled pack = one new object implementing this interface
 * (data only, in the same shape as JpCountryPack) + one line in
 * [CountryPacks.all]. The engine picks it up from the callee's ISO code;
 * no layer in decide() names a country.
 *
 * Data in a pack is that country's OWN verified data (official sources
 * only), never a translation of another pack's rows: numbering plans,
 * emergency codes and impersonation targets differ per country, and a
 * wrong row is worse than a missing one.
 */
internal interface CountryPack {

    /** ISO 3166-1 alpha-2, uppercase ("JP"). Matched against CallContext.calleeCountryIso. */
    val iso: String

    /** ITU calling code, digits only, no '+' ("81"). */
    val callingCode: String

    /**
     * Emergency numbers of this country that must always ring: the domestic
     * short codes plus this country's international-format variants
     * (e.g. "110" and "+81110"). Roaming numbers owned by no single country
     * (911/112/999/000…) live in EmergencyWhitelist itself.
     */
    val emergencyNumbers: Set<String>

    /**
     * Warn-but-ring police directory: domestic-form number → display name.
     * Exact match only. These numbers are what impersonation scammers spoof,
     * so they must NEVER be silently trusted (see PoliceStationDirectory).
     */
    val policeDirectory: Map<String, String>

    /** Warn-but-ring tax-authority directory: domestic-form number → display name. */
    val taxDirectory: Map<String, String>

    /**
     * Caller country codes (ITU, digits, no '+') treated as elevated-risk
     * FOR USERS IN THIS COUNTRY (Layer 12). Risk is relative to the callee's
     * country — the corridors that target JP users are not the corridors
     * that target anyone else — so the set belongs to the callee's pack,
     * not to a global list.
     */
    val elevatedRiskCountryCodes: Set<String>

    /**
     * True when [normalized] cannot exist under this country's numbering
     * plan (Layer 8). Accepts domestic and this country's E.164 forms;
     * numbers of other countries return false (out of scope, never flagged).
     */
    fun isImpossibleDomesticNumber(normalized: String): Boolean

    /** Police lookup accepting domestic and this country's E.164 forms. */
    fun lookupPolice(normalized: String): String? = lookupIn(policeDirectory, normalized)

    /** Tax-authority lookup; same accepted forms as [lookupPolice]. */
    fun lookupTax(normalized: String): String? = lookupIn(taxDirectory, normalized)

    /**
     * Directory lookup shared by both warn directories: direct domestic
     * match, then this country's E.164 form converted to domestic. The
     * conversion mirrors the convention the JP directory always used:
     * "+81 3 3581 4321" → "0335814321", and a carrier-mangled form that
     * keeps the trunk zero ("+810335814321") is used as-is, never given a
     * second zero.
     */
    private fun lookupIn(directory: Map<String, String>, normalized: String): String? {
        directory[normalized]?.let { return it }
        if (normalized.startsWith("+$callingCode")) {
            val rest = normalized.removePrefix("+$callingCode")
            val domestic = if (rest.startsWith("0")) rest else "0$rest"
            directory[domestic]?.let { return it }
        }
        return null
    }
}

/** Registry of the compiled country packs. JP is the first pack (2026-11). */
internal object CountryPacks {

    /** All compiled packs, in lookup order. */
    val all: List<CountryPack> = listOf(JpCountryPack)

    /** The pack for a callee's ISO code, or null when Orange ships no pack for it. */
    fun forIso(iso: String?): CountryPack? = all.firstOrNull { it.iso == iso }
}

/**
 * Parser for assets/packs/index.txt, the asset-pack registry: one ISO code
 * per line, '#' comments and blank lines ignored, codes uppercased.
 * Pure (no Android types) so the format is unit-testable without a Context.
 */
internal object CountryPackIndex {

    fun parse(r: BufferedReader): List<String> =
        r.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { it.uppercase() }
            .toList()
}

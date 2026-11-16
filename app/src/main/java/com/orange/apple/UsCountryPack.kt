package com.orange.apple

/**
 * The US country pack — Orange's second pack (2026-11, ADR 015), proving
 * the ADR 014 split carries a second country with no engine changes:
 * registration is this object plus one line in [CountryPacks.all] and one
 * line in assets/packs/index.txt.
 *
 * Every row below is the United States' own verified data, from the
 * organisation's OWN official page only — accuracy > coverage, because a
 * mis-labelled number becomes a spear-phishing vector. Verified 2026-11:
 *
 *  - Police: FBI Headquarters, (202) 324-3000 (fbi.gov/contact-us/
 *    fbi-headquarters). The FBI itself warns that scammers spoof exactly
 *    this number in government-impersonation fraud — which is why it
 *    belongs in the warn-but-ring directory and NEVER in the business
 *    bundle. The US has no single national police number below the FBI;
 *    local departments are deliberately not enumerated (unverifiable at
 *    this pack's bar, and a wrong row is worse than a missing one).
 *  - Tax: IRS individual assistance, 1-800-829-1040 (irs.gov/help/
 *    telephone-assistance). IRS impersonation is the canonical US
 *    government-impersonation scam (DOJ prosecutions of the Ahmedabad
 *    call-centre networks, 2016–2020).
 *  - Emergency: 988 (Suicide & Crisis Lifeline, designated by the FCC in
 *    2020, live nationwide since 2022-07-16 — SAMHSA/FCC) and its legacy
 *    number 1-800-273-8255, which still routes to the same Lifeline.
 *    911 is deliberately absent: EmergencyWhitelist owns it as a roaming
 *    number (see the [CountryPack.emergencyNumbers] contract).
 *  - Numbering plan: [NanpSpoofDetector] (NANP rules per FCC/NANPA).
 *  - Elevated-risk corridors FOR US USERS: India (+91) — the DOJ has
 *    repeatedly prosecuted India-based call centres (Ahmedabad) whose
 *    scripts were IRS/USCIS impersonation aimed at US victims. +1 itself
 *    is NEVER elevated here: it is the home plan, and flagging it would
 *    mark every domestic call. The JP pack's corridors (e.g. +675) are
 *    not copied — risk is relative to the callee's country.
 *
 * The NANP has no trunk prefix, so the interface's default directory
 * conversion (which rebuilds a JP-style 0-prefixed domestic form) does
 * not apply; both lookups override it with NANP-correct normalization
 * via [NanpSpoofDetector.toNanpNational].
 */
internal object UsCountryPack : CountryPack {

    override val iso: String = "US"

    override val callingCode: String = "1"

    override val emergencyNumbers: Set<String> = setOf(
        "988",  // Suicide & Crisis Lifeline (FCC-designated, live since 2022)
        "8002738255", "+18002738255"  // Lifeline legacy number (1-800-273-TALK)
    )

    override val policeDirectory: Map<String, String> = mapOf(
        "2023243000" to "FBI Headquarters"
    )

    override val taxDirectory: Map<String, String> = mapOf(
        "8008291040" to "Internal Revenue Service (IRS)"
    )

    override val elevatedRiskCountryCodes: Set<String> = setOf(
        "91"  // India — DOJ-documented IRS/USCIS impersonation call centres targeting US victims
    )

    override fun isImpossibleDomesticNumber(normalized: String): Boolean =
        NanpSpoofDetector.isImpossibleUsNumber(normalized)

    override fun lookupPolice(normalized: String): String? =
        lookupNanp(policeDirectory, normalized)

    override fun lookupTax(normalized: String): String? =
        lookupNanp(taxDirectory, normalized)

    /**
     * NANP directory lookup: direct match first, then the 10-digit
     * national form (dropping a leading country code 1). No trunk digit
     * is ever inserted — the NANP has none.
     */
    private fun lookupNanp(directory: Map<String, String>, normalized: String): String? {
        directory[normalized]?.let { return it }
        val national = NanpSpoofDetector.toNanpNational(normalized) ?: return null
        return directory[national]
    }
}

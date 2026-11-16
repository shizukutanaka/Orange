package com.orange.apple

/**
 * Apple philosophy: some rules must be absolute.
 *
 * Competitors (Truecaller/Whoscall/Hiya) all allow emergency numbers through
 * implicitly via their "allow contacts" path, but none of them document a
 * hard-coded bypass that survives every other rule, every pause state,
 * every user toggle. A silenced 110 is a killed user.
 *
 * This list is checked FIRST in SilentBlockerService, before pause state,
 * before spam cache, before foreign-unsolicited. It cannot be disabled,
 * there is no setting to edit it, and the app UI never mentions it.
 * The correct design for a safety-critical feature is: the user never
 * knows it's there, and it never fails.
 *
 * Composition (ADR 014): each country pack contributes its own emergency
 * numbers (JpCountryPack: 110/119/118/189/171 and their +81 forms), and
 * this object adds the ROAMING numbers below — emergency codes owned by
 * no single pack that a user must be able to reach (and be reached by)
 * while travelling, whatever their home country is. The union is what
 * isEmergency() checks; it is identical to the flat list this object
 * held before the pack restructure.
 *
 * List source: ITU-T E.161 + national emergency directories, current as of
 * 2026-04. Expand only if a number actually falls through in the wild;
 * do not speculatively add.
 */
internal object EmergencyWhitelist {

    /** Emergency codes reachable when roaming, independent of home country. */
    private val roamingNumbers: Set<String> = setOf(
        "911",  // US/Canada
        "112",  // EU/GSM universal
        "999",  // UK/HK/IE
        "000",  // Australia

        // Extended-format variants the OS may deliver when roaming
        "+1911",                   // US 911 as dialed by some MVNO SIMs
        "+44999",                  // UK 999 as dialed by some MVNO SIMs
        "+61000",                  // AU 000 international form
        "+112"                     // EU 112 bare (no national prefix)
    )

    private val numbers: Set<String> = buildSet {
        addAll(roamingNumbers)
        for (pack in CountryPacks.all) addAll(pack.emergencyNumbers)
    }

    /**
     * Returns true when the number is an emergency line and must be allowed
     * through regardless of any other rule. Null/empty input is safe (returns false)
     * since the screener falls back to allow when the handle is absent.
     */
    fun isEmergency(normalized: String): Boolean =
        normalized in numbers
}

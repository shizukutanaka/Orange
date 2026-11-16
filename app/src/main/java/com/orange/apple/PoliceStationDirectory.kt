package com.orange.apple

/**
 * Aggregate view of every country pack's warn-but-ring police directory.
 *
 * The data itself lives in the country packs (JP: JpCountryPack — 47
 * prefectural police HQ + National Police Agency + verified major Tokyo
 * stations, sourced from each prefectural police HQ's official site +
 * 警視庁公式サイト, 2026-06). This object is the merged view the rest of
 * the app has always consumed; with one pack registered it is exactly the
 * JP directory it was before the pack restructure (ADR 014).
 *
 * Why these numbers get their own directory at all: 2025年のニセ警察詐欺
 * (9,642件, ¥831.9億円, 過去最悪) works by spoofing real police HQ numbers.
 * A match must therefore RING (we never block the police) but always
 * surface the impersonation warning — never the business bundle's silent
 * trust. The engine consults the callee's own pack (see
 * CallDecision.govAgencyImpersonationWarning); this aggregate exists for
 * whole-directory consumers (warmup, the business-bundle regression guard).
 */
internal object PoliceStationDirectory {

    /** Merged police directories of all registered packs, in pack order. */
    val entries: Map<String, String> = buildMap {
        for (pack in CountryPacks.all) putAll(pack.policeDirectory)
    }

    /**
     * Searches every pack's police directory, accepting domestic and
     * E.164 forms. Returns the police HQ name, or null if not a known
     * police number in any pack.
     */
    fun lookup(normalized: String): String? {
        for (pack in CountryPacks.all) {
            pack.lookupPolice(normalized)?.let { return it }
        }
        return null
    }
}

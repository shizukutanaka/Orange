package com.orange.apple

/**
 * NANP (North American Numbering Plan) impossible-number detector — the
 * US counterpart of [DomesticSpoofDetector], backing
 * [UsCountryPack.isImpossibleDomesticNumber] (Layer 8).
 *
 * The plan's shape is fixed by the FCC/NANPA: NPA-NXX-XXXX, where both the
 * area code (NPA) and the central-office code (NXX) begin with 2–9. The
 * N11 codes (211/311/411/511/611/711/811/911) are reserved as abbreviated
 * dialling codes, so they can never appear as an NPA or an NXX. Area code
 * 555 is unassigned, and NANPA reserves the line range 555-0100–555-0199
 * as fictitious, non-working numbers for entertainment/advertising use —
 * no subscriber can ever hold one, so a caller ID showing one is spoofed
 * by construction.
 *
 * Like the JP detector, this returns true only for numbers that are
 * *provably* impossible under the plan; anything outside its remit
 * abstains (false):
 *
 *  - Other countries' numbers (the leading '+' is not +1).
 *  - Short codes (911, 988, …) — emergency/service codes are not
 *    NPA-NXX-XXXX numbers and are handled by EmergencyWhitelist.
 *  - Malformed lengths.
 *  - Valid-but-premium assignments: toll-free NPAs (800/833/844/855/866/
 *    877/888) and 900 are real allocations and are NOT flagged, and the
 *    Caribbean/Atlantic premium NPAs are [CaribbeanPremiumNANP]'s layer,
 *    not this one's — those numbers exist; they are merely expensive.
 *
 * Sources: FCC NANP fact sheet (DOC-204337A2), NANPA "Abbreviated
 * Dialing / Special Use Codes" and "555 Line Numbers" pages, verified
 * 2026-11 (see docs/adr/015-us-country-pack.md).
 */
internal object NanpSpoofDetector {

    /** Abbreviated dialling codes: never a valid NPA or NXX. */
    private val N11 = setOf("211", "311", "411", "511", "611", "711", "811", "911")

    /** True when [number] is a NANP number that cannot exist under the plan. */
    fun isImpossibleUsNumber(number: String): Boolean {
        val national = toNanpNational(number) ?: return false
        val npa = national.substring(0, 3)
        val nxx = national.substring(3, 6)
        val line = national.substring(6, 10)
        // NPA and NXX first digits must be 2–9.
        if (npa[0] !in '2'..'9' || nxx[0] !in '2'..'9') return true
        // N11 codes are abbreviated dialling codes, never an NPA or an NXX.
        if (npa in N11 || nxx in N11) return true
        // 555 is not an assigned area code.
        if (npa == "555") return true
        // 555-0100–555-0199 is NANPA's reserved fictitious range.
        if (nxx == "555" && line in "0100".."0199") return true
        return false
    }

    /**
     * Normalize a NANP number to its 10-digit national form (NPA-NXX-XXXX,
     * digits only), or null when the input is not a NANP number this
     * detector has an opinion about. Accepts the E.164 form (+1…), the
     * 11-digit domestic form (1…) and the bare 10-digit form; display
     * punctuation is ignored. Everything else — other countries, short
     * codes, malformed lengths — returns null (abstain).
     */
    fun toNanpNational(number: String): String? {
        val trimmed = number.trim()
        val digits = trimmed.filter { it.isDigit() }
        val national = when {
            trimmed.startsWith("+") -> {
                if (!digits.startsWith("1")) return null
                digits.drop(1)
            }
            digits.length == 11 && digits.startsWith("1") -> digits.drop(1)
            else -> digits
        }
        return national.takeIf { it.length == 10 }
    }
}

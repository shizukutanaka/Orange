package com.orange.apple

/**
 * Japanese Numbering Plan spoof detector.
 * Only flags numbers that CANNOT exist in the JP plan.
 * Source: 総務省 電気通信番号計画 (MIC).
 *
 * Digit counts (domestic notation, leading 0 included):
 *   11 digits: 050 (IP), 060/070/080/090 (mobile), 0800 (freephone)
 *   10 digits: 0120 (freephone), 0570 (navi-dial), 0990 (teledome),
 *              0X (geographic landline, e.g. 03/022)
 */
internal object DomesticSpoofDetector {

    private val ELEVEN_DIGIT_PREFIXES = listOf("050", "060", "070", "080", "090", "0800")
    private val TEN_DIGIT_PREFIXES    = listOf("0120", "0570", "0990")

    fun isImpossibleJpNumber(normalized: String): Boolean {
        val d = toDomestic(normalized) ?: return false
        // 020 is allocated by MIC for M2M/IoT (020-XXXXXXXX, 11 digits) and
        // historically for pagers (now defunct). M2M numbers are not assigned
        // to humans and never place voice calls — an inbound voice call
        // claiming a 020 prefix is therefore either a defunct pager or a spoof.
        // We block it. (libphonenumber classifies 020 as PAGER/UAN; we treat
        // any 020 voice call as impossible-for-a-human-caller.)
        if (d.startsWith("020"))         return true
        if (violatesElevenDigitRule(d))  return true   // 050/06x-09x/0800 must be 11
        if (violatesTenDigitRule(d))     return true   // 0120/0570/0990 must be 10
        if (hasEightRepeatingDigits(d))  return true   // robot-dialer artifact
        if (d.length !in 10..11)         return true   // outside valid domestic range
        if (d.getOrNull(1) == '0')       return true   // 00x = intl access code
        return false
    }

    private fun toDomestic(n: String): String? {
        if (n.startsWith("+81")) {
            val rest = n.removePrefix("+81")
            return if (rest.startsWith("0")) rest else "0$rest"
        }
        return if (n.startsWith("0") && n.all { it.isDigit() }) n else null
    }

    private fun violatesElevenDigitRule(d: String): Boolean =
        ELEVEN_DIGIT_PREFIXES.any { d.startsWith(it) } && d.length != 11

    private fun violatesTenDigitRule(d: String): Boolean =
        TEN_DIGIT_PREFIXES.any { d.startsWith(it) } && d.length != 10

    internal fun hasEightRepeatingDigits(s: String): Boolean {
        if (s.length < 8) return false
        var run = 1
        for (i in 1 until s.length) {
            if (s[i] == s[i - 1]) { if (++run >= 8) return true } else run = 1
        }
        return false
    }
}

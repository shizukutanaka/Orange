package com.orange.apple

/**
 * Phone number normalization utility. Single source of truth.
 *
 * Previously duplicated in SilentBlockerService and CallStateObserver.
 * Carmack rule: if two files define the same function, one of them
 * will eventually drift. Extract, test once, import everywhere.
 */
internal object PhoneNumbers {

    /**
     * Strip everything except digits and leading '+' from a raw phone
     * number string. This is the canonical form used by the decision
     * engine, all caches, and all directory lookups.
     *
     * Examples:
     *   "+81 (3) 3581-4321"  →  "+81335814321"
     *   "090-1234-5678"      →  "09012345678"
     *   ""                   →  ""
     */
    /**
     * Strip everything except digits and a leading '+' from a raw phone number,
     * folding full-width characters to half-width first.
     *
     * Why full-width folding: some IMEs, carriers, and copy-paste sources
     * deliver numbers in full-width form (＋８１ / ０９０…, U+FF0B, U+FF10–FF19).
     * `Char.isDigit()` returns true for full-width digits, so without folding
     * they would survive normalization yet fail every half-width prefix test
     * (`startsWith("090")` etc.), silently misclassifying the call. We fold
     * first and keep only ASCII `[0-9+]` so the rest of the engine only ever
     * sees ASCII. See ADR 007.
     */
    fun normalize(raw: String): String =
        buildString(raw.length) {
            for (ch in raw) {
                val c = foldFullWidth(ch)
                if (c in '0'..'9' || c == '+') append(c)
            }
        }

    /** Map full-width '＋' and '０'-'９' to ASCII; pass everything else through. */
    private fun foldFullWidth(ch: Char): Char = when (ch) {
        '\uFF0B' -> '+'
        in '\uFF10'..'\uFF19' -> ('0' + (ch.code - 0xFF10))
        else -> ch
    }
}

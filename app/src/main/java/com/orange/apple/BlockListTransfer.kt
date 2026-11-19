package com.orange.apple

/**
 * Block-list import/export in Ring's JSON schema v1 — the shared format
 * Ring's Web layer and Android layer used, kept alive in Orange after
 * the 2026-11 consolidation so a Ring user's exported list still has
 * somewhere to land:
 *
 * {
 *   "version": 1,
 *   "allow": ["+81312345678"],
 *   "blockExact": ["+819012345678"],
 *   "prefixes": [{ "prefix": "0800", "scope": "national" }, { "prefix": "8190", "scope": "e164" }],
 *   "reports": [{ "number": "+815012345678", "category": "fraud", "note": "", "ts": 1762000000000 }]
 * }
 *
 * The port is deliberately ASYMMETRIC, and the asymmetry is Orange's
 * privacy design, not an omission:
 *
 *  - EXPORT carries prefix rules only. Blocked numbers cannot be
 *    exported: SpamCache stores them as salted hashes (ADR 006) and
 *    BlockHistoryStore stores them masked to the last 4 digits
 *    (ADR 008), so the full numbers no longer exist anywhere in
 *    Orange to export. allow/reports are empty for the same reason —
 *    Orange has no exact-number allow list and no report store.
 *
 *  - IMPORT accepts prefixes and blockExact. Imported exact numbers
 *    are plaintext on arrival and are hashed into SpamCache by the
 *    caller (SettingsActivity, via ManualBlock) — import is the one
 *    direction where the number legitimately exists. allow entries
 *    are counted and skipped: an imported bare number cannot
 *    faithfully populate any of Orange's allow paths (family slots,
 *    bundled businesses, outbound history, history suffix-allow), and
 *    silently widening one of them would be a false promise. reports
 *    are counted and skipped: in Ring a report was a warning, not a
 *    block, and Orange has no warning-only personal store — turning
 *    someone's warnings into blocks on import would change what
 *    their data means.
 *
 * Malformed entries are filtered out instead of failing the whole
 * import (Ring's behaviour). A document that starts with '{' but is not
 * JSON, or whose version is not 1, is rejected outright.
 *
 * Anything else is read as PLAIN TEXT (importText): a police notice, a
 * neighbour's LINE message or a family member's warning, copied as-is.
 * The phone numbers in it are pulled out and blocked. This is how a
 * user keeps the block list current without a server: the source of
 * fresh scam numbers is whatever the user already reads, and the
 * number never leaves the device.
 */
internal object BlockListTransfer {

    const val VERSION = 1

    /** What an import found. Counts make the skips visible, never silent. */
    data class ImportResult(
        val prefixRules: List<PrefixRule>,
        val blockExact: List<String>,
        val skippedAllow: Int,
        val skippedReports: Int,
        /** Police/tax numbers found in pasted text and deliberately not blocked. */
        val skippedProtected: Int = 0,
    )

    fun exportJson(prefixRules: List<PrefixRule>): String {
        val root = LinkedHashMap<String, Any?>()
        root["version"] = VERSION
        root["allow"] = emptyList<String>()
        root["blockExact"] = emptyList<String>()
        root["prefixes"] = prefixRules.map { rule ->
            linkedMapOf<String, Any?>("prefix" to rule.prefix, "scope" to rule.scope)
        }
        root["reports"] = emptyList<Any?>()
        return MiniJson.stringify(root, pretty = true)
    }

    /**
     * @throws IllegalArgumentException "invalid JSON" or
     *         "unsupported blocklist version"
     */
    fun importJson(json: String): ImportResult {
        val parsed = try {
            MiniJson.parse(json)
        } catch (e: Exception) {
            throw IllegalArgumentException("invalid JSON")
        }
        val obj = parsed as? Map<*, *>
            ?: throw IllegalArgumentException("unsupported blocklist version")
        val version = (obj["version"] as? Double)?.toInt()
        if (version != VERSION) throw IllegalArgumentException("unsupported blocklist version")

        return ImportResult(
            prefixRules = prefixList(obj["prefixes"]),
            blockExact = exactList(obj["blockExact"]),
            skippedAllow = (obj["allow"] as? List<*>)?.size ?: 0,
            skippedReports = (obj["reports"] as? List<*>)?.size ?: 0,
        )
    }

    /**
     * Schema v1 when [text] looks like a JSON object, otherwise the phone
     * numbers written anywhere in it.
     *
     * Text numbers are accepted only in a full-number shape: domestic
     * 0 + 9-10 digits (03-1234-5678, 090-1234-5678, 0120-123-456) or
     * '+' + 8-15 digits, with '-', spaces, dots, parentheses and their
     * full-width forms as separators. Dates, postal codes, prices and
     * short codes never reach that shape, so they are not picked up.
     *
     * Police and tax-office numbers are never blocked from text, and are
     * counted in skippedProtected instead: an anti-fraud notice nearly
     * always prints the police's own number, and decide() checks the
     * spam cache (Layer 6) before the warn-but-ring lane (Layer 9/9b), so
     * blocking it would silence a real officer's call.
     *
     * @throws IllegalArgumentException when the text holds neither a
     *         valid block list nor any phone number
     */
    fun importText(text: String): ImportResult {
        if (text.trimStart().startsWith("{")) return importJson(text)
        val numbers = LinkedHashSet<String>()
        val kept = HashSet<String>()
        for (match in TEXT_NUMBER.findAll(text)) {
            // A space-separated run can join two neighbours ("2026-11-19
            // 03-1234-5678"); when the whole run is not a number, its
            // space-separated parts are tried on their own.
            val whole = PhoneNumbers.normalize(match.value)
            val candidates = if (isFullNumber(whole)) listOf(whole)
            else match.value.split(' ', '\u3000').map(PhoneNumbers::normalize).filter(::isFullNumber)
            for (n in candidates) {
                if (EmergencyWhitelist.isEmergency(n)) continue
                if (CountryPacks.all.any { it.lookupPolice(n) != null || it.lookupTax(n) != null }) {
                    kept += n
                } else {
                    numbers += n
                }
            }
        }
        if (numbers.isEmpty() && kept.isEmpty()) {
            throw IllegalArgumentException("no phone numbers")
        }
        return ImportResult(emptyList(), numbers.toList(), 0, 0, kept.size)
    }

    private fun isFullNumber(n: String): Boolean =
        if (n.startsWith("+")) n.length - 1 in 8..15
        else n.startsWith("0") && !n.startsWith("00") && n.length in 10..11

    // A digit (or '+'), then digits with at most one separator between
    // any two of them, ending on a digit. Separators: - . space ( ) and
    // the full-width / Japanese dash forms copy-paste delivers.
    private val TEXT_NUMBER = Regex(
        "[+\\uFF0B]?[0-9\\uFF10-\\uFF19](?:[-. ()\\u2010-\\u2015\\u2212\\u30FC\\uFF08\\uFF09\\uFF0D\\u3000]?[0-9\\uFF10-\\uFF19])+"
    )

    private fun prefixList(value: Any?): List<PrefixRule> =
        (value as? List<*>)?.mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            val prefix = m["prefix"] as? String ?: return@mapNotNull null
            val scope = m["scope"] as? String ?: return@mapNotNull null
            if (scope != PrefixRule.SCOPE_NATIONAL && scope != PrefixRule.SCOPE_E164) {
                return@mapNotNull null
            }
            // Same validity bar as a hand-typed rule: 3-15 digits, and the
            // leading-zero convention of the scope must hold.
            if (!Regex("^\\d{3,15}$").matches(prefix)) return@mapNotNull null
            if (scope == PrefixRule.SCOPE_NATIONAL && !prefix.startsWith("0")) return@mapNotNull null
            if (scope == PrefixRule.SCOPE_E164 && prefix.startsWith("0")) return@mapNotNull null
            PrefixRule(prefix, scope)
        }?.distinct() ?: emptyList()

    private fun exactList(value: Any?): List<String> =
        (value as? List<*>)?.mapNotNull { item ->
            val raw = item as? String ?: return@mapNotNull null
            val cleaned = PhoneNumbers.normalize(raw)
            // Schema v1 defines blockExact as E.164. Anything else (short
            // codes, domestic forms, junk) is dropped here rather than
            // guessed at: the caller feeds this list to ManualBlock, whose
            // contract is full international numbers.
            val digits = cleaned.removePrefix("+")
            if (!cleaned.startsWith("+") || digits.length !in 8..15) return@mapNotNull null
            if (!digits.all { it.isDigit() }) return@mapNotNull null
            if (EmergencyWhitelist.isEmergency(cleaned)) return@mapNotNull null
            cleaned
        }?.distinct() ?: emptyList()
}

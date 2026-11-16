package com.orange.apple

/**
 * Country-code table (ITU-T E.164) plus the elevated-risk seed view.
 *
 * The country-code table below is global reference data: it maps an
 * international number to its country code, for any caller and any callee.
 *
 * The ELEVATED-RISK set is not global: risk is relative to the callee's
 * country, so the set itself lives in the callee's country pack
 * (JpCountryPack.elevatedRiskCountryCodes — the corridors disproportionately
 * linked to special-fraud (特殊詐欺) calls reaching Japanese users), and
 * decide() reads it from the pack. [elevatedRiskCountryCodes] below remains
 * as a view of the JP pack's set for existing callers.
 *
 * Source rationale for the JP set (updated 2026-07):
 *  - 警察庁 SOS47 特殊詐欺対策: 国際発信を偽装した詐欺の増加。令和8年(2026)から
 *    「ニセ警察詐欺」を独立手口に再分類 — 被害額の約7割(985億円超)を占める。
 *  - NPA/総務省「みんとめ」: 特殊詐欺に利用された電話番号の約75.5%が国際電話番号
 *    (+1/+44 が突出)。都内ニセ警察詐欺は前年比38.8%減で、防犯アプリ利用倍増が要因と
 *    警視庁が分析 (時事 2026-07-06) — 本アプローチ(未知の国際着信を静音)の外部エビデンス。
 *  - トビラシステムズ/NTT公開資料の傾向: +675 (パプアニューギニア経由偽装),
 *    +7 (ロシア/カザフ経由), +44 (英経由), +39 (伊経由), +86 (中) がJP着信で
 *    異常に高い比率で詐欺関連。
 *  - 認知件数 令和7年 27,758件, 被害額 1,414.2億円 (日本国内, 過去最悪)。
 *
 * Philosophy: this is a SEED, not a blocklist. A +1 call from a real US number
 * the user dialed outbound is always allowed (outbound-known beats seed).
 *
 * NOTE on +1 (deliberately NOT in elevatedRiskCountryCodes): although +1 carries
 * a high RAW volume of scam calls to JP, it is also the single highest-volume
 * LEGITIMATE international corridor to JP (family abroad, US business). An
 * unsolicited +1 call to a JP phone is still silenced — by the generic
 * foreign-unsolicited rule (Layer 13), not the elevated rule (Layer 12). The
 * only practical difference is the history label (FOREIGN_GENERIC vs
 * FOREIGN_ELEVATED); promoting +1 to elevated would overstate risk on the
 * corridor most likely to carry a legitimate call, with no change to whether
 * it rings. The seed's job is only to tighten the foreign-unsolicited default
 * so the user feels protected on day 0 without waiting for their first block.
 *
 * We do NOT ship an individual-number blocklist because:
 *  1. Such lists go stale immediately (scammers rotate numbers hourly)
 *  2. Shipping one invites the maintenance burden that killed the MVP promise
 *  3. Legit foreign users calling JP would see false positives
 *
 * The seed only tightens the foreign-unsolicited rule's default behavior.
 */
internal object ScamPrefixSeed {

    /**
     * The JP pack's elevated-risk country codes, exposed here for existing
     * callers. The data lives in JpCountryPack (risk is a property of the
     * callee's country, so each pack carries its own set); decide() reads
     * the set from the callee's pack directly.
     */
    val elevatedRiskCountryCodes: Set<String>
        get() = JpCountryPack.elevatedRiskCountryCodes

    /**
     * Given a normalized E.164-ish number ("+81…"/"+1…" etc.), returns the
     * country code portion or null if not international.
     *
     * Naive implementation: tries 1-, 2-, 3-digit prefixes in that order and
     * returns the first match. This is fine because ITU country codes are
     * prefix-free (no code is a prefix of another).
     */
    fun countryCodeOf(intlNumber: String): String? {
        if (!intlNumber.startsWith("+")) return null
        val digits = intlNumber.removePrefix("+")
        for (len in 1..3) {
            val candidate = digits.take(len)
            if (candidate in allCodes) return candidate
        }
        return null
    }

    // Minimal ITU-T E.164 country-code tables. Enough to avoid misclassifying
    // common cases. Not exhaustive — the screener's foreign-unsolicited rule
    // already handles unknown prefixes conservatively (allow).
    private val oneDigit = setOf("1", "7")
    private val twoDigit = setOf(
        "20","27","30","31","32","33","34","36","39",
        "40","41","43","44","45","46","47","48","49",
        "51","52","53","54","55","56","57","58",
        "60","61","62","63","64","65","66",
        "81","82","84","86","90","91","92","93","94","95","98"
    )
    private val threeDigit = setOf(
        "212","213","216","218",
        "220","221","222","223","224","225","226","227","228","229",
        "230","231","232","233","234","235","236","237","238","239",
        "240","241","242","243","244","245","246","247","248","249",
        "250","251","252","253","254","255","256","257","258","260",
        "261","262","263","264","265","266","267","268","269",
        "350","351","352","353","354","355","356","357","358","359",
        "370","371","372","373","374","375","376","377","378","380",
        "381","382","385","386","387","389","420","421","423",
        "500","501","502","503","504","505","506","507","508","509",
        "590","591","592","593","594","595","596","597","598","599",
        "670","672","673","674","675","676","677","678","679","680",
        "681","682","683","685","686","687","688","689","690","691",
        "692","850","852","853","855","856","880","886","960","961",
        "962","963","964","965","966","967","968","970","971","972",
        "973","974","975","976","977","992","993","994","995","996","998"
    )

    private val allCodes: Set<String> = oneDigit + twoDigit + threeDigit
}

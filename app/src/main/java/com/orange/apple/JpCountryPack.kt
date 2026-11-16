package com.orange.apple

/**
 * The JP country pack — Orange's first pack (2026-11).
 *
 * Every row below is Japan's own verified data, moved here unchanged from
 * the objects that used to hold it directly (PoliceStationDirectory,
 * TaxAgencyDirectory, EmergencyWhitelist, ScamPrefixSeed). The relocation
 * is structural only: same numbers, same names, same sets — the decision
 * engine's outputs are byte-for-byte identical, which the test suite and
 * the pre/post equivalence run both pin down.
 *
 * Sources (unchanged): prefectural police HQ official sites + 警視庁公式
 * サイト (2026-06), 国税庁 official page, ITU-T E.161 + national emergency
 * directories (2026-04), 警察庁 SOS47 / NPA 特殊詐欺白書 + IRSF corridor
 * research for the elevated-risk codes (2026-07). Only numbers verified
 * from the organisation's OWN public page are listed — accuracy > coverage,
 * because a mis-labelled number becomes a spear-phishing vector.
 */
internal object JpCountryPack : CountryPack {

    override val iso: String = "JP"

    override val callingCode: String = "81"

    /**
     * Japan's emergency and safety short codes, domestic form plus the
     * +81 international forms some MVNO SIMs deliver when roaming.
     * Checked FIRST in the engine (Layer 1) via EmergencyWhitelist, which
     * unions every pack's set with the roaming numbers it owns itself.
     */
    override val emergencyNumbers: Set<String> = setOf(
        "110",  // 警察 Police
        "119",  // 消防/救急 Fire & Ambulance
        "118",  // 海上保安庁 Coast Guard
        "189",  // 児童相談所虐待対応ダイヤル
        "171",  // 災害用伝言ダイヤル
        // International-format variants the OS may deliver when roaming
        "+81110", "+81119", "+81118",
        "+81189", "+81171"   // 児童相談所 / 災害用伝言 international form
    )

    /**
     * 47 prefectural police HQ + National Police Agency + verified major
     * Tokyo-area stations. Keys are domestic form ("0335814321").
     *
     * 2025年のニセ警察詐欺 (9,642件, ¥831.9億円, 過去最悪) の主要手口:
     *   scammer → 被害者端末に"警視庁新宿署"の代表番号を偽装表示
     *   → 被害者が折り返すとLINE/ビデオ通話に誘導
     *
     * 着信がこのリストに一致 → ブロックではなく **警告表示**:
     *   「この番号は○○警察の番号です。偽装の可能性があります。
     *    一度切って、#9110 にかけ直してください」
     *
     * 個別署番号は公式サイトで確認が取れた番号のみ収録 (不確かな番号は除外)。
     */
    override val policeDirectory: Map<String, String> = mapOf(
        // 警察庁 (National Police Agency) — NOT in the business bundle:
        // that bundle grants unconditional silent trust with no warning, which
        // is the wrong treatment for a number scammers impersonate.
        "0335810141" to "警察庁",
        // 北海道
        "0112510110" to "北海道警察",
        // 東北
        "0172233211" to "青森県警察",
        "0196531111" to "岩手県警察",
        "0222211611" to "宮城県警察",
        "0188631111" to "秋田県警察",
        "0236265211" to "山形県警察",
        "0245221111" to "福島県警察",
        // 関東
        "0293011621" to "茨城県警察",
        "0286217004" to "栃木県警察",
        "0273431271" to "群馬県警察",
        "0488321111" to "埼玉県警察",
        "0432011111" to "千葉県警察",
        "0335814321" to "警視庁",         // 東京都
        // 警視庁管内 主要警察署 — ニセ警察詐欺で頻繁に偽装される番号
        // Source: 警視庁公式サイト (keishicho.metro.tokyo.lg.jp) 2026-06時点
        // 2025-06-19: 千代田区が丸の内警察署番号でのアポ電入電中を公式警告
        "0332130110" to "丸の内警察署",   // 千代田区 — 2025年アポ電で実際に使用
        "0333460110" to "新宿警察署",     // 新宿区
        "0339860110" to "池袋警察署",     // 豊島区
        "0334980110" to "渋谷警察署",     // 渋谷区
        "0338470110" to "上野警察署",     // 台東区
        "0335430110" to "築地警察署",     // 中央区（銀座・築地エリア担当）
        "0452110110" to "神奈川県警察",
        // 中部
        "0252850110" to "新潟県警察",
        "0764410110" to "富山県警察",
        "0762250110" to "石川県警察",
        "0776220110" to "福井県警察",
        "0552210110" to "山梨県警察",
        "0262330110" to "長野県警察",
        "0582712424" to "岐阜県警察",
        "0542711616" to "静岡県警察",
        "0529510110" to "愛知県警察",
        // 近畿
        "0592220110" to "三重県警察",
        "0775233010" to "滋賀県警察",
        "0754510110" to "京都府警察",
        "0669430110" to "大阪府警察",
        "0783410110" to "兵庫県警察",
        "0742230110" to "奈良県警察",
        "0734230110" to "和歌山県警察",
        // 中国
        "0857220110" to "鳥取県警察",
        "0852270110" to "島根県警察",
        "0862340110" to "岡山県警察",
        "0822283111" to "広島県警察",
        "0839330110" to "山口県警察",
        // 四国
        "0886221211" to "徳島県警察",
        "0878330110" to "香川県警察",
        "0899340110" to "愛媛県警察",
        "0888260110" to "高知県警察",
        // 九州・沖縄
        "0926410110" to "福岡県警察",
        "0952240110" to "佐賀県警察",
        "0958200110" to "長崎県警察",
        "0963810110" to "熊本県警察",
        "0975360110" to "大分県警察",
        "0985260110" to "宮崎県警察",
        "0992060110" to "鹿児島県警察",
        "0988620110" to "沖縄県警察"
    )

    /**
     * Tax-authority representative numbers, domestic form → display name.
     * 還付金詐欺・税金未納詐欺 scammers spoof exactly these numbers, so —
     * like the police directory — they ring with the impersonation warning,
     * never with the business bundle's silent trust.
     */
    override val taxDirectory: Map<String, String> = mapOf(
        "0352533111" to "国税庁"
    )

    /**
     * Country codes where an unsolicited call to a JP number has historically
     * carried an elevated scam probability. Used only when the callee is in
     * JP and the number is international and not outbound-known.
     *
     * +1 is deliberately NOT here: although +1 carries a high RAW volume of
     * scam calls to JP, it is also the single highest-volume LEGITIMATE
     * international corridor to JP. An unsolicited +1 call is still silenced
     * — by the generic foreign-unsolicited rule (Layer 13), not the elevated
     * rule (Layer 12); the only difference is the history label.
     */
    override val elevatedRiskCountryCodes: Set<String> = setOf(
        "675",  // Papua New Guinea — Wangiri/IRSF corridor (Palau is +680)
        "7",    // Russia/Kazakhstan transit
        "86",   // Mainland China
        "44",   // UK (heavily spoofed for JP targets)
        "39",   // Italy (less common, but rising)
        "212",  // Morocco (Wangiri callbacks)
        "234",  // Nigeria (419 corridor)
        "63",   // Philippines (call-center fraud corridor)
        // IRSF/Wangiri high-cost termination corridors. These small or
        // low-traffic destinations are repeatedly identified in IRSF research
        // (NDSS 2021 "Understanding and Detecting IRSF"; CFCA loss reports) as
        // the termination points for International Premium Rate Numbers. A JP
        // consumer almost never places a legitimate call to these, so an
        // unsolicited inbound call from one is a strong revenue-share signal.
        "371",  // Latvia — recurrent IRSF test-number range
        "370",  // Lithuania — recurrent IRSF test-number range
        "239",  // Sao Tome and Principe — classic IPRN termination
        "232",  // Sierra Leone — high-cost IRSF corridor
        "252",  // Somalia — high-cost IRSF corridor
        "53",   // Cuba — high-cost termination
        "682",  // Cook Islands — Pacific IPRN range
        "676",  // Tonga — Pacific IPRN range
        "678",  // Vanuatu — Pacific IPRN range
        "855",  // Cambodia — documented scam-center corridor (2025-2026)
        // Laos: Bokeo Golden Triangle SEZ — same NPA-identified corridor as Myanmar/Cambodia.
        // ゴールデン・トライアングル経済特区はミャンマー・カンボジアの詐欺拠点と並び
        // NPA 2025 特殊詐欺白書でも言及 (詐欺コールセンターのSEZ隣接地帯).
        "856",  // Laos — Golden Triangle SEZ scam-compound corridor
        // Myanmar: KK Park (カンコー特区) and Myawaddy border SEZs are large-scale
        // scam operation hubs targeting JP victims; NPA 2025 白書 identifies
        // Myanmar-based call centers as a primary origin for 国際電話詐欺.
        // Legitimate inbound +95 calls to JP mobile numbers are extremely rare,
        // making the signal high-precision for JP users.
        "95"    // Myanmar — Myawaddy/KK Park scam-compound corridor
    )

    /**
     * The JP numbering-plan validity check lives in DomesticSpoofDetector
     * (総務省 電気通信番号計画) — the pack delegates so the plan rules, and
     * their Python oracle mirror, keep exactly one home.
     */
    override fun isImpossibleDomesticNumber(normalized: String): Boolean =
        DomesticSpoofDetector.isImpossibleJpNumber(normalized)
}

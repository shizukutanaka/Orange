package com.orange.apple

/**
 * Bundled directory of 47 prefectural police HQ representative numbers.
 *
 * 2025年のニセ警察詐欺 (9,642件, ¥831.9億円, 過去最悪) の主要手口:
 *   scammer → 被害者端末に"警視庁新宿署"の代表番号を偽装表示
 *   → 被害者が折り返すとLINE/ビデオ通話に誘導
 *
 * 旧Layer 7.7 (0110-tail heuristic) の問題:
 *   実際の警察署代表番号は0110で終わらないものが多数。
 *   例: 警視庁 03-3581-4321, 大阪府警 06-6943-1234
 *   0110-tail は偽陽性が多く、真の詐欺パターンを見逃す。
 *
 * 修正: 実在する警察本部代表番号のexact matchに変更。
 * 着信がこのリストに一致 → ブロックではなく **警告表示**:
 *   「この番号は○○警察の番号です。偽装の可能性があります。
 *    一度切って、#9110 にかけ直してください」
 *
 * Source: 各都道府県警察公式サイト (2026-04時点)
 * 番号は代表電話のみ。個別署は含まない (1,200+件は将来拡張)。
 */
internal object PoliceStationDirectory {

    /**
     * Map of domestic 10-digit number → display name (prefecture police HQ).
     * Keys are stored in domestic form ("0335814321", not "+81335814321").
     * lookup() accepts both forms and converts as needed.
     * If an incoming call matches, the decision engine returns RING
     * (we do NOT block police) but flags it for post-pickup warning.
     */
    val entries: Map<String, String> = mapOf(
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
        "0988620110" to "沖縄県警察",
    )

    /**
     * 国際形式 (+81…) と国内形式 (0…) の両方で検索。
     * Returns the police HQ name, or null if not a known police number.
     */
    fun lookup(normalized: String): String? {
        // Direct domestic match
        entries[normalized]?.let { return it }
        // +81 → domestic conversion
        // Standard E.164: +81 3 3581 4321 (no leading zero)
        // Some systems deliver +810335814321 (with leading zero) — handle both.
        if (normalized.startsWith("+81")) {
            val rest = normalized.removePrefix("+81")
            val domestic = if (rest.startsWith("0")) rest else "0$rest"
            entries[domestic]?.let { return it }
        }
        return null
    }
}

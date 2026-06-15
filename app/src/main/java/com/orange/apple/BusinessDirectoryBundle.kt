package com.orange.apple

import android.content.Context
import java.io.BufferedReader

/**
 * Apple philosophy: the real feature Whoscall/Truecaller deliver is not the
 * blocking — it's the moment a ringing phone shows "Yamato Transport" instead
 * of "090-XXXX-XXXX". That moment is worth paying for, and every competitor
 * charges for it either in money or in your contact list.
 *
 * Orange delivers that moment for $0 and zero contact-list surrender by
 * bundling a small, audited, public-business directory at install time.
 *
 * This is NOT a caller-name overlay on the incoming-call screen — Android
 * does not permit third-party apps to mutate that UI. This IS:
 *
 *  - A lookup used by SilentBlockerService to decide whether an unknown
 *    caller is actually a known legitimate business (Layer 6), which
 *    AUTO-WHITELISTS the call so it rings through without being screened.
 *  - A trusted-caller check in PostCallAdvisor — business-bundle numbers
 *    do not trigger the post-call #9110 advisory (they're not scam suspects).
 *
 * The bundle ships as assets/business_directory.csv with format:
 *     E164-or-shortcode,ShortName
 *     +81352535111,総務省
 *     188,消費者ホットライン
 *     ...
 *
 * v1.1 (2026-06): ~80 entries covering central ministries, carriers, major banks,
 * credit cards, logistics, transit, and official consultation hotlines. All numbers
 * sourced from each organisation's OWN public page — no third-party aggregators.
 * Source: 総務省 soumu.go.jp/main_content/000612566.pdf (公開) + 各機関公式サイト.
 *
 * Update policy: only numbers from official public sources; never mobile numbers;
 * never third-party listing sites. Accuracy > coverage (a mis-labelled number
 * becomes a spear-phishing vector — an attacker could spoof a trusted name).
 */
internal object BusinessDirectoryBundle {

    private const val ASSET = "business_directory.csv"
    private var cache: Map<String, String>? = null

    /** Loads directory on first call; returns empty map if no asset present. */
    @Synchronized
    fun load(ctx: Context): Map<String, String> {
        cache?.let { return it }
        val result = mutableMapOf<String, String>()
        try {
            ctx.assets.open(ASSET).bufferedReader().use { r ->
                parseInto(r, result)
            }
            // Cache only on success. A transient open failure (e.g., asset extractor
            // not yet complete on first launch) must not permanently empty the directory
            // for the process lifetime — leave cache=null so the next call retries.
            cache = result
        } catch (_: Exception) {
            // Asset not present or unreadable. Degrade gracefully: callers are still
            // screened by the three-layer engine; don't set cache so we retry next call.
        }
        return result
    }

    internal fun parseInto(r: BufferedReader, dst: MutableMap<String, String>) {
        r.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .forEach { line ->
                val comma = line.indexOf(',')
                if (comma > 0) {
                    val num = line.substring(0, comma).trim()
                    val name = line.substring(comma + 1).trim()
                    if (num.isNotEmpty() && name.isNotEmpty()) dst[num] = name
                }
            }
    }

    /** Returns display name if bundled, else null. Never hits the network. */
    fun lookup(ctx: Context, normalized: String): String? =
        load(ctx)[normalized]
}

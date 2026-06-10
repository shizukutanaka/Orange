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
 *    caller is actually a known legitimate business, which AUTO-WHITELISTS
 *    the call so it rings through the foreign-unsolicited rule.
 *  - A source of the display string on the Wear OS complication and the
 *    home-screen widget when showing the most-recent-blocked number.
 *  - Future: a TrustNotifier enrichment ("Silenced: SMBC Card Center").
 *
 * The bundle ships as assets/business_directory.csv with format:
 *     E164,ShortName
 *     +81357577001,三井住友カード
 *     +81570039192,ヤマト運輸
 *     ...
 *
 * We DO NOT ship with a bundled file preloaded in this commit because that
 * would violate the "no speculative features" rule. The loader below is the
 * scaffolding; a curated CSV will follow once we identify a permissive
 * public source (candidates: iタウンページ public business listings under
 * their terms, JPX-listed company IR phone numbers, 公共機関 public
 * contacts from ministry sites). Licensing review required before shipping.
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
        } catch (_: Exception) {
            // No asset shipped yet — that's fine. Empty directory degrades
            // gracefully (callers are still screened by the three-rule engine).
        }
        cache = result
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

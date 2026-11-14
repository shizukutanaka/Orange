package com.orange.apple

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Stores the user's prefix block rules (see PrefixRules.kt) in
 * SharedPreferences, in the same style as AllowSuffixStore: a single
 * ordered, space-separated string rather than a StringSet, so list
 * order in Settings is the order the user added the rules and eviction
 * is deterministic.
 *
 * Encoding: one token per rule, "n" + digits for national scope,
 * "e" + digits for e164 scope ("n0800", "e8190"). Digits and the two
 * scope letters are the only characters a canonical rule can contain,
 * so the encoding needs no escaping.
 *
 * Unlike AllowSuffixStore, add() does NOT silently evict at the cap:
 * an evicted suffix-allow costs a re-tap, but an evicted block rule
 * silently un-blocks a whole range the user believes is blocked.
 * At the cap, add() returns false and Settings says so.
 */
internal object PrefixBlockStore {

    private const val KEY = "prefix_rules"
    private const val MAX = 200

    /** Adds [rule] unless already present. Returns false if the store is full. */
    @Synchronized
    fun add(prefs: SharedPreferences, rule: PrefixRule): Boolean {
        val ordered = loadOrdered(prefs).toMutableList()
        if (rule in ordered) return true
        if (ordered.size >= MAX) return false
        ordered.add(rule)
        save(prefs, ordered)
        return true
    }

    /** Removes [rule]. No-op if absent. */
    @Synchronized
    fun remove(prefs: SharedPreferences, rule: PrefixRule) {
        val ordered = loadOrdered(prefs).toMutableList()
        if (ordered.remove(rule)) save(prefs, ordered)
    }

    /** All rules, newest first, for display in Settings. */
    @Synchronized
    fun list(prefs: SharedPreferences): List<PrefixRule> = loadOrdered(prefs).asReversed()

    /** All rules in insertion order — the order the engine consults them. */
    @Synchronized
    fun listForEngine(prefs: SharedPreferences): List<PrefixRule> = loadOrdered(prefs)

    private fun loadOrdered(prefs: SharedPreferences): List<PrefixRule> {
        val raw = prefs.getString(KEY, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split(' ').mapNotNull { token ->
            if (token.length < 4) return@mapNotNull null
            val scope = when (token[0]) {
                'n' -> PrefixRule.SCOPE_NATIONAL
                'e' -> PrefixRule.SCOPE_E164
                else -> return@mapNotNull null
            }
            val digits = token.substring(1)
            if (!digits.all { it.isDigit() }) return@mapNotNull null
            PrefixRule(digits, scope)
        }
    }

    private fun save(prefs: SharedPreferences, ordered: List<PrefixRule>) {
        val raw = ordered.joinToString(" ") { rule ->
            (if (rule.scope == PrefixRule.SCOPE_E164) "e" else "n") + rule.prefix
        }
        prefs.edit { putString(KEY, raw) }
    }
}

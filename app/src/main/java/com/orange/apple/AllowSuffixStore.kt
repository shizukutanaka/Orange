package com.orange.apple

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Stores "allow by suffix" entries created when the user taps Allow in
 * HistoryActivity. Because history entries store only masked numbers
 * (last 4 digits), we can't do exact SpamCache removal. Instead we store
 * the 4-digit suffix here, and SilentBlockerService checks this before
 * consulting SpamCache.
 *
 * Trade-off accepted: a 4-digit suffix allow is less precise than an
 * exact number. In practice, the user taps Allow because a specific caller
 * was wrongly blocked; the suffix match is a best-effort unblock that covers
 * the common case without storing the full number.
 *
 * Max 100 entries, ordered by insertion time (newest last). When the list
 * overflows, the OLDEST entry is evicted. Stored as a space-separated string
 * rather than a StringSet — StringSet has no defined iteration order, so
 * toList().takeLast(MAX) on a HashSet-backed set would discard entries
 * arbitrarily (including the newly-added one the user just requested).
 */
internal object AllowSuffixStore {

    private const val KEY = "allow_suffix"
    private const val MAX = 100
    private const val SUFFIX_DIGITS = 4  // must match BlockHistoryStore's mask length

    @Synchronized
    fun allow(prefs: SharedPreferences, maskedNumber: String) {
        val suffix = maskedNumber.filter { it.isDigit() }.takeLast(SUFFIX_DIGITS)
        if (suffix.length < SUFFIX_DIGITS) return
        val ordered = loadOrdered(prefs).toMutableList()
        if (suffix !in ordered) {
            ordered.add(suffix)
            if (ordered.size > MAX) ordered.removeAt(0)   // evict oldest
        }
        save(prefs, ordered)
    }

    /**
     * Remove a previously-allowed suffix. Called from HistoryActivity when
     * a user taps "Block" on an entry they had previously allowed.
     * No-op if the suffix is not in the list.
     */
    @Synchronized
    fun revoke(prefs: SharedPreferences, maskedNumber: String) {
        val suffix = maskedNumber.filter { it.isDigit() }.takeLast(SUFFIX_DIGITS)
        if (suffix.length < SUFFIX_DIGITS) return
        val ordered = loadOrdered(prefs).toMutableList()
        if (ordered.remove(suffix)) save(prefs, ordered)
    }

    @Synchronized
    fun isAllowed(prefs: SharedPreferences, number: String): Boolean {
        if (number.length < SUFFIX_DIGITS) return false
        val suffix = number.filter { it.isDigit() }.takeLast(SUFFIX_DIGITS)
        if (suffix.length < SUFFIX_DIGITS) return false
        return suffix in loadOrdered(prefs)
    }

    private fun loadOrdered(prefs: SharedPreferences): List<String> {
        val raw = prefs.getString(KEY, "") ?: ""
        return if (raw.isBlank()) emptyList()
        else raw.split(' ').filter { it.length == SUFFIX_DIGITS && it.all { c -> c.isDigit() } }
    }

    private fun save(prefs: SharedPreferences, ordered: List<String>) {
        prefs.edit { putString(KEY, ordered.joinToString(" ")) }
    }
}

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

    @Synchronized
    fun allow(prefs: SharedPreferences, maskedNumber: String) {
        val suffix = maskedNumber.takeLast(4).filter { it.isDigit() }
        if (suffix.length < 4) return
        val ordered = loadOrdered(prefs).toMutableList()
        if (suffix !in ordered) {
            ordered.add(suffix)
            if (ordered.size > MAX) ordered.removeAt(0)   // evict oldest
        }
        save(prefs, ordered)
    }

    @Synchronized
    fun isAllowed(prefs: SharedPreferences, number: String): Boolean {
        if (number.length < 4) return false
        val suffix = number.filter { it.isDigit() }.takeLast(4)
        if (suffix.length < 4) return false
        return suffix in loadOrdered(prefs)
    }

    private fun loadOrdered(prefs: SharedPreferences): List<String> {
        val raw = prefs.getString(KEY, "") ?: ""
        return if (raw.isBlank()) emptyList()
        else raw.split(' ').filter { it.length == 4 && it.all { c -> c.isDigit() } }
    }

    private fun save(prefs: SharedPreferences, ordered: List<String>) {
        prefs.edit { putString(KEY, ordered.joinToString(" ")) }
    }
}

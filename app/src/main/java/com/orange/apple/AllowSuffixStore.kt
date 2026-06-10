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
 * Max 100 entries. Suffixes are stored plaintext (4 digits = not sensitive).
 */
internal object AllowSuffixStore {

    private const val KEY = "allow_suffix"
    private const val MAX = 100

    @Synchronized
    fun allow(prefs: SharedPreferences, maskedNumber: String) {
        val suffix = maskedNumber.takeLast(4).filter { it.isDigit() }
        if (suffix.length < 4) return
        val set = load(prefs).toMutableSet()
        set.add(suffix)
        if (set.size > MAX) {
            val trimmed = set.toList().takeLast(MAX).toSet()
            save(prefs, trimmed)
        } else {
            save(prefs, set)
        }
    }

    @Synchronized
    fun isAllowed(prefs: SharedPreferences, number: String): Boolean {
        if (number.length < 4) return false
        val suffix = number.takeLast(4)
        return suffix in load(prefs)
    }

    private fun load(prefs: SharedPreferences): Set<String> =
        prefs.getStringSet(KEY, emptySet()) ?: emptySet()

    private fun save(prefs: SharedPreferences, set: Set<String>) {
        prefs.edit { putStringSet(KEY, set) }
    }
}

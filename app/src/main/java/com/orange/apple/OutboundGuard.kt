package com.orange.apple

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Tracks recently blocked/warned numbers so the decision engine can
 * warn when the user dials back one of those numbers.
 *
 * Audit L3: "A common scam-recovery loss occurs when the elderly victim,
 * in panic, calls back the very number that just defrauded them."
 *
 * When SilentBlockerService blocks or warns about a number, it records
 * the number here. When the same service sees an OUTGOING call to that
 * number (via onScreenCall DIRECTION_OUTGOING on API 29+), it surfaces
 * a high-priority notification: "この番号は先ほど警告された番号です。
 * 本当に発信しますか？"
 *
 * The call is NOT blocked (we never block outgoing calls — that would
 * violate the user's agency). Only a notification is shown.
 *
 * 24-hour window, bounded at 64 entries (same philosophy as WangiriTracker).
 */
internal object OutboundGuard {

    const val WINDOW_MS = 24L * 60 * 60 * 1000
    const val MAX_ENTRIES = 64
    private const val KEY = "outbound_guard"

    /** Record a number that was blocked or warned.
     * Silently ignores empty strings (withheld calls have number=""). */
    @Synchronized
    fun record(prefs: SharedPreferences, number: String, nowMs: Long) {
        if (number.isEmpty()) return   // defensive: never store empty key
        val current = snapshot(prefs, nowMs).toMutableMap()
        current[number] = nowMs
        if (current.size > MAX_ENTRIES) {
            val trimmed = current.entries
                .sortedByDescending { it.value }
                .take(MAX_ENTRIES)
                .associate { it.toPair() }
            save(prefs, trimmed)
        } else {
            save(prefs, current)
        }
    }

    /** Check if a number was recently blocked/warned. */
    @Synchronized
    fun wasRecentlyFlagged(prefs: SharedPreferences, number: String, nowMs: Long): Boolean =
        snapshot(prefs, nowMs).containsKey(number)

    /** Remove a number from the guard (called when user restores a false positive). */
    @Synchronized
    fun forget(prefs: SharedPreferences, number: String, nowMs: Long) {
        if (number.isEmpty()) return
        val current = snapshot(prefs, nowMs).toMutableMap()
        if (current.remove(number) != null) save(prefs, current)
    }

    private fun snapshot(prefs: SharedPreferences, nowMs: Long): Map<String, Long> {
        val raw = prefs.getString(KEY, "") ?: ""
        if (raw.isEmpty()) return emptyMap()
        val out = HashMap<String, Long>()
        raw.split(' ').forEach { entry ->
            val colon = entry.indexOf(':')
            if (colon <= 0) return@forEach
            val num = entry.substring(0, colon)
            val ts = entry.substring(colon + 1).toLongOrNull() ?: return@forEach
            if (nowMs >= ts && nowMs - ts < WINDOW_MS) out[num] = ts
        }
        return out
    }

    private fun save(prefs: SharedPreferences, map: Map<String, Long>) {
        prefs.edit {
            putString(KEY, map.entries.joinToString(" ") { "${it.key}:${it.value}" })
        }
    }
}

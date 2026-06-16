package com.orange.apple

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Repeat-caller velocity heuristic.
 *
 * A legitimate caller who gets no answer redials once, maybe twice.
 * An automated dialer or scam-flood operation calls the same number
 * many times in quick succession.
 *
 * Rule: if the SAME number rings N_THRESHOLD+ times within WINDOW_MS,
 * the (N+1)th call is silenced as REPEAT_CALLER.
 *
 * This is a generalization of the Wangiri tracker for ALL calls,
 * not just short-ring patterns. The Wangiri tracker still handles
 * the callback-direction scam; this handles the inbound-flood pattern.
 *
 * Thresholds are intentionally conservative:
 *   N_THRESHOLD = 3  (block on 4th call within the window)
 *   WINDOW_MS   = 60 minutes
 *
 * A family member who genuinely can't get through in an emergency
 * should call a different household number or use a messaging app after
 * 3 attempts — this is the implicit social contract Orange enforces.
 */
internal object RepeatCallerTracker {

    const val WINDOW_MS = 60L * 60 * 1000   // 60 minutes
    const val N_THRESHOLD = 3               // 4th call in the window → SILENCE
    private const val MAX_ENTRIES = 64
    private const val KEY = "repeat_caller"

    /**
     * Remove all entries for [number] — call when the user answers an
     * incoming call, signalling voluntary acceptance. Prevents the
     * repeat-caller rule from firing on a legitimate return call within
     * the same 60-minute window.
     */
    @Synchronized
    fun clear(prefs: SharedPreferences, number: String) {
        if (number.isEmpty()) return
        // Parse raw storage without time-window filtering so entries recorded
        // with synthetic timestamps (tests) or outside the current window are
        // still removed. snapshot() filters by nowMs and would silently no-op
        // if the entries are "old" from the clock's perspective.
        val raw = prefs.getString(KEY, "") ?: ""
        if (raw.isEmpty()) return
        val entries = raw.split('|').filter { entry ->
            // Drop malformed entries (no ':') consistent with snapshot()'s parts.size < 2 guard.
            entry.contains(':') && entry.substringBefore(':') != number
        }
        prefs.edit { putString(KEY, entries.joinToString("|")) }
    }

    /**
     * Record an incoming ring attempt from [number] at [nowMs].
     * Call BEFORE the decision engine so the count is current.
     */
    @Synchronized
    fun record(prefs: SharedPreferences, number: String, nowMs: Long) {
        if (number.isEmpty()) return
        val map = snapshot(prefs, nowMs).toMutableMap()
        val existing = map[number] ?: emptyList()
        val updated = (existing + nowMs).takeLast(N_THRESHOLD + 2)
        map[number] = updated
        if (map.size > MAX_ENTRIES) {
            val trimmed = map.entries
                .sortedByDescending { it.value.maxOrNull() ?: 0L }
                .take(MAX_ENTRIES)
                .associate { it.toPair() }
            save(prefs, trimmed)
        } else {
            save(prefs, map)
        }
    }

    /**
     * Returns true if [number] has rung N_THRESHOLD+ times within WINDOW_MS.
     */
    @Synchronized
    fun isRepeatOffender(prefs: SharedPreferences, number: String, nowMs: Long): Boolean {
        if (number.isEmpty()) return false
        val calls = snapshot(prefs, nowMs)[number] ?: return false
        return calls.size > N_THRESHOLD
    }

    private fun snapshot(prefs: SharedPreferences, nowMs: Long): Map<String, List<Long>> {
        val raw = prefs.getString(KEY, "") ?: ""
        if (raw.isEmpty()) return emptyMap()
        val out = HashMap<String, MutableList<Long>>()
        raw.split('|').forEach { entry ->
            val parts = entry.split(':')
            if (parts.size < 2) return@forEach
            val num = parts[0]
            val times = parts.drop(1)
                .mapNotNull { it.toLongOrNull() }
                .filter { nowMs >= it && nowMs - it < WINDOW_MS }
            if (times.isNotEmpty()) out[num] = times.toMutableList()
        }
        return out
    }

    private fun save(prefs: SharedPreferences, map: Map<String, List<Long>>) {
        val raw = map.entries.joinToString("|") { (num, times) ->
            "$num:" + times.joinToString(":")
        }
        prefs.edit { putString(KEY, raw) }
    }
}

package com.orange.apple

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Tracks short-ring incoming calls so the decision engine can identify
 * the second leg of a Wangiri (ワン切り) pattern.
 *
 * Wangiri = the phone rings once or twice, the user doesn't answer in time,
 * and the curiosity of the missed call drives the user to call BACK — at
 * which point the scammer's international premium number bills the user
 * per minute. The block pattern is obvious in retrospect: the same number
 * that short-rings you is the one you shouldn't be returning calls to.
 *
 * Orange observes short-ring candidates via Android's telephony state
 * (TelephonyManager CALL_STATE_RINGING → CALL_STATE_IDLE in under 6 seconds
 * with no answer). When that pattern fires, the number goes into the
 * tracker. On the NEXT call FROM that same number, the decision engine
 * treats it as a callback and silences it.
 *
 * Bounded at 64 entries (at most ~64 pending wangiri candidates is already
 * an unusually noisy day; storing more is noise). Entries older than
 * WANGIRI_WINDOW_MS (6 hours) are pruned on read.
 * Short-ring threshold: SHORT_RING_THRESHOLD_MS = 15 s (covers Wangiri 2.0).
 *
 * Carmack rule: this is another cache that grows without a bound unless
 * we put one here. 64 entries is the bound.
 */
internal object WangiriTracker {

    const val MAX_ENTRIES = 64
    /**
     * Short-ring threshold.
     *
     * v1 (6s): catches classic 1-ring Wangiri (phone rings, scammer hangs up).
     * v2 (15s): also catches "Wangiri 2.0" — a brief connect (3-7s) with an
     * automated recording ("料金未納が…") before disconnect. The 15-second
     * window covers both patterns without false-positiving on legitimate
     * short calls (wrong number, "I'll call back in 5 minutes").
     *
     * Tobila/Whoscall 2025: Wangiri 2.0 is the dominant callback-scam variant
     * in the JP market.
     */
    const val SHORT_RING_THRESHOLD_MS = 15_000L
    // How long a short-ring candidate stays eligible to block a callback.
    // Defined here (not in CallDecision) so WangiriTracker is self-contained.
    const val WANGIRI_WINDOW_MS = 6L * 60 * 60 * 1000

    private const val KEY = "wangiri_candidates"  // serialized "num1:ts1 num2:ts2 ..."

    fun snapshot(prefs: SharedPreferences, nowMs: Long): Map<String, Long> {
        val raw = prefs.getString(KEY, "") ?: ""
        if (raw.isEmpty()) return emptyMap()
        val out = HashMap<String, Long>()
        raw.split(' ').forEach { entry ->
            val colon = entry.indexOf(':')
            if (colon <= 0) return@forEach
            val num = entry.substring(0, colon)
            val ts = entry.substring(colon + 1).toLongOrNull() ?: return@forEach
            if (nowMs >= ts && nowMs - ts < WANGIRI_WINDOW_MS) out[num] = ts
        }
        return out
    }

    /** Record a short-ring candidate. Prunes expired entries as a side effect. */
    fun record(prefs: SharedPreferences, number: String, nowMs: Long) {
        if (number.isEmpty()) return
        val current = snapshot(prefs, nowMs).toMutableMap()
        current[number] = nowMs
        // Keep newest MAX_ENTRIES only.
        val trimmed = current.entries
            .sortedByDescending { it.value }
            .take(MAX_ENTRIES)
            .associate { it.toPair() }
        prefs.edit {
            putString(KEY, trimmed.entries.joinToString(" ") { "${it.key}:${it.value}" })
        }
    }

    /** Remove after a Wangiri callback has been handled (so we don't block twice). */
    fun forget(prefs: SharedPreferences, number: String) {
        // Route through snapshot() so expired entries are pruned at the same time.
        // The previous raw-string approach left stale entries accumulating between
        // record() calls, which can cause the stored KEY string to grow unboundedly
        // (no cap applies to forget()-only paths that never trigger record()).
        val nowMs = System.currentTimeMillis()
        val current = snapshot(prefs, nowMs).toMutableMap()
        if (current.remove(number) != null) {
            prefs.edit {
                putString(KEY, current.entries.joinToString(" ") { "${it.key}:${it.value}" })
            }
        }
    }
}

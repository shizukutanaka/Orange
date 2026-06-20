package com.orange.apple

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Debounces trust-window notifications so a scam-dialer burst doesn't
 * produce 50 notifications in 30 seconds.
 *
 * Observed failure mode (competitor reviews, not our own): scammers using
 * VoIP spoofing tools sometimes dial a target number repeatedly in rapid
 * succession when a block is detected on their end. A naive call-screener
 * notifies per block, creating notification storms that drive users to
 * uninstall the app — "it's scarier than the scam."
 *
 * Policy: within a 5-minute window, at most ONE notification per distinct
 * number, and at most FIVE notifications across all numbers. Additional
 * blocks in the window increment the counter silently — the user still
 * sees them on the widget and in the system call log, just not as push
 * notifications.
 *
 * Rams #5 (unobtrusive): the app respects the user enough to not scream
 * when the phone is under attack.
 */
internal object NotificationRateLimiter {

    const val WINDOW_MS = 5L * 60 * 1000
    const val MAX_NOTIFS_PER_WINDOW = 5

    private const val KEY_WINDOW_START = "nrl_window_start"
    private const val KEY_WINDOW_COUNT = "nrl_window_count"
    private const val KEY_SEEN_NUMBERS = "nrl_seen_numbers"  // space-sep

    /**
     * @return true if a notification SHOULD be shown for this number right now.
     *         Side-effect: records the decision in the rolling window.
     *         Synchronized to prevent races: multiple threads checking the window
     *         simultaneously must not create stale counts (thread A and B both
     *         reading count=2, both incrementing to 3, losing one increment).
     */
    @Synchronized
    fun shouldNotify(prefs: SharedPreferences, number: String, nowMs: Long): Boolean {
        // Withheld (非通知) calls arrive with number = "". An empty string survives
        // set.add() and set.contains() but is dropped by filter { isNotBlank() }
        // when the seen-set is deserialized from its space-separated string — the
        // dedup for withheld calls silently fails and they notify 5×/window.
        // Use a non-blank sentinel that can never appear in a normalized number.
        val key = number.ifEmpty { "#" }
        val windowStart = prefs.getLong(KEY_WINDOW_START, 0L)
        // If nowMs < windowStart, the system clock jumped backward. Treat as expired window.
        val inWindow = nowMs >= windowStart && nowMs - windowStart < WINDOW_MS

        val (count, seen) = if (inWindow) {
            prefs.getInt(KEY_WINDOW_COUNT, 0) to
                (prefs.getString(KEY_SEEN_NUMBERS, "") ?: "")
                    .split(' ').filter { it.isNotBlank() }.toMutableSet()
        } else {
            0 to mutableSetOf()
        }

        // Already notified for this number in this window: no state change needed.
        // (seen is only non-empty when inWindow=true, so all three keys are unchanged.)
        if (key in seen) return false

        // Cap reached: no state change needed.
        if (count >= MAX_NOTIFS_PER_WINDOW) return false

        // Fire and record.
        seen.add(key)
        prefs.edit {
            putLong(KEY_WINDOW_START, if (inWindow) windowStart else nowMs)
            putInt(KEY_WINDOW_COUNT, count + 1)
            putString(KEY_SEEN_NUMBERS, seen.joinToString(" "))
        }
        return true
    }
}

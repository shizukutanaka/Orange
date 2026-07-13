package com.orange.apple

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for PauseTile.isPaused() — the single source of truth for whether
 * "Pause means every call rings" is currently in effect (decide()'s Layer 2
 * KDoc). This session found a real bug (RepeatCallerTracker silencing calls
 * while paused, see FEATURE_AUDIT.md) caused by a caller failing to respect
 * this contract; isPaused() itself had no direct test coverage anywhere in
 * the suite despite being the function every one of those call sites reads.
 *
 * isPaused() reads System.currentTimeMillis() internally rather than taking
 * an injected clock, so these tests compute KEY_PAUSED_UNTIL relative to the
 * real clock at call time — acceptable here since the assertion happens
 * immediately after, well within any plausible test-execution jitter.
 */
class PauseTileTest {

    @Test fun not_paused_when_never_set() {
        val p = FakePrefs()
        assertFalse(PauseTile.isPaused(p))
    }

    @Test fun paused_when_until_is_in_the_near_future() {
        val p = FakePrefs()
        p.edit().putLong(PauseTile.KEY_PAUSED_UNTIL, System.currentTimeMillis() + 30 * 60 * 1000L).apply()
        assertTrue(PauseTile.isPaused(p))
    }

    @Test fun not_paused_once_the_window_has_expired() {
        val p = FakePrefs()
        p.edit().putLong(PauseTile.KEY_PAUSED_UNTIL, System.currentTimeMillis() - 1000L).apply()
        assertFalse(PauseTile.isPaused(p))
    }

    @Test fun not_paused_when_until_is_exactly_now() {
        // pausedUntil > now is a strict inequality — "until now" means the
        // window has already closed, not that this instant is still covered.
        val now = System.currentTimeMillis()
        val p = FakePrefs()
        p.edit().putLong(PauseTile.KEY_PAUSED_UNTIL, now).apply()
        assertFalse(PauseTile.isPaused(p))
    }

    // --- MAX_PAUSE_MS backward-clock-jump guard ---
    // PauseTile.onClick() only ever writes now + 1 hour. A pausedUntil more
    // than MAX_PAUSE_MS (2h) in the future is impossible to produce through
    // normal use — it can only arise if the system clock was set backward
    // after the pause was recorded. Without this guard, such a clock jump
    // would create a pause that outlives the intended 1-hour window, in the
    // worst case indefinitely (if the clock never catches back up).

    @Test fun paused_when_within_the_max_pause_cap() {
        val p = FakePrefs()
        // Just inside the 2h cap.
        val until = System.currentTimeMillis() + (2L * 60 * 60 * 1000) - 1000L
        p.edit().putLong(PauseTile.KEY_PAUSED_UNTIL, until).apply()
        assertTrue(PauseTile.isPaused(p))
    }

    @Test fun not_paused_when_until_exceeds_the_max_pause_cap() {
        // A pausedUntil more than 2h out implies a backward clock jump —
        // treat as expired rather than honoring an unbounded pause.
        val p = FakePrefs()
        val until = System.currentTimeMillis() + (2L * 60 * 60 * 1000) + 60_000L
        p.edit().putLong(PauseTile.KEY_PAUSED_UNTIL, until).apply()
        assertFalse("a pausedUntil beyond the 2h cap must not be honored", PauseTile.isPaused(p))
    }
}

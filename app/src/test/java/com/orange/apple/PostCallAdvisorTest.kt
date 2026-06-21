package com.orange.apple

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for PostCallAdvisor rate-limit key pruning logic.
 * Only the pure-prefs pruneStaleRateKeys() is testable without a real Context.
 */
class PostCallAdvisorTest {

    private lateinit var prefs: FakePrefs

    @Before fun setUp() {
        prefs = FakePrefs()
    }

    @Test fun `prune removes keys older than 24h window`() {
        val old = 1_000_000L
        val now = old + PostCallAdvisor.WINDOW_MS + 1L
        prefs.edit().putLong("postcall_last_09012345678", old).apply()
        PostCallAdvisor.pruneStaleRateKeys(prefs, now)
        assertFalse("stale key must be pruned",
            prefs.contains("postcall_last_09012345678"))
    }

    @Test fun `prune keeps keys still within 24h window`() {
        val recent = 1_000_000L
        val now = recent + PostCallAdvisor.WINDOW_MS - 1L
        prefs.edit().putLong("postcall_last_09012345678", recent).apply()
        PostCallAdvisor.pruneStaleRateKeys(prefs, now)
        assertTrue("fresh key must survive prune",
            prefs.contains("postcall_last_09012345678"))
    }

    @Test fun `prune removes only postcall_last_ prefixed keys`() {
        val old = 1_000_000L
        val now = old + PostCallAdvisor.WINDOW_MS + 1L
        prefs.edit()
            .putLong("postcall_last_09012345678", old)
            .putLong("other_prefs_key", old)
            .apply()
        PostCallAdvisor.pruneStaleRateKeys(prefs, now)
        assertFalse("stale postcall key must be pruned",
            prefs.contains("postcall_last_09012345678"))
        assertTrue("unrelated key must survive",
            prefs.contains("other_prefs_key"))
    }

    @Test fun `prune removes multiple stale keys at once`() {
        val old = 1_000_000L
        val now = old + PostCallAdvisor.WINDOW_MS + 1L
        prefs.edit()
            .putLong("postcall_last_+819012345678", old)
            .putLong("postcall_last_+81801234567", old)
            .putLong("postcall_last_+81701234567", old)
            .apply()
        PostCallAdvisor.pruneStaleRateKeys(prefs, now)
        assertFalse(prefs.contains("postcall_last_+819012345678"))
        assertFalse(prefs.contains("postcall_last_+81801234567"))
        assertFalse(prefs.contains("postcall_last_+81701234567"))
    }

    @Test fun `prune on empty prefs is a no-op`() {
        PostCallAdvisor.pruneStaleRateKeys(prefs, 1_000_000L)
        // just verify no exception
        assertTrue(prefs.all.isEmpty())
    }

    @Test fun `prune respects backward clock jump guard`() {
        // If stored timestamp > now (clock jumped backward), do NOT prune —
        // the difference (now - ts) would be negative and unsigned overflow gives
        // a huge value exceeding WINDOW_MS. Verify the guard works.
        val future = 5_000_000L
        val now = 1_000_000L  // now < future → backward jump scenario
        prefs.edit().putLong("postcall_last_09012345678", future).apply()
        PostCallAdvisor.pruneStaleRateKeys(prefs, now)
        // (now - future) = -4_000_000L < 0 and < WINDOW_MS (positive), so NOT pruned
        assertTrue("future-timestamp key must survive backward-jump prune",
            prefs.contains("postcall_last_09012345678"))
    }
}

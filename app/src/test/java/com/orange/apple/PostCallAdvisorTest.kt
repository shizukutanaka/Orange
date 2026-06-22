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
        // If stored timestamp > now (clock jumped backward), do NOT prune:
        // now - ts is negative as a signed Long, and the guard (now >= ts) must
        // reject such entries before the subtraction. A missing guard would let
        // Long underflow produce a huge positive value exceeding WINDOW_MS and
        // incorrectly delete the rate-limit key.
        val future = 5_000_000L
        val now = 1_000_000L  // now < future → backward jump scenario
        prefs.edit().putLong("postcall_last_09012345678", future).apply()
        PostCallAdvisor.pruneStaleRateKeys(prefs, now)
        assertTrue("future-timestamp key must survive backward-jump prune",
            prefs.contains("postcall_last_09012345678"))
    }

    @Test fun `prune does not prune key at exactly window boundary`() {
        // At exactly now - ts == WINDOW_MS, the key is just expiring. The condition
        // is `>= WINDOW_MS` so it IS pruned at the boundary (caller can see advisor again).
        val base = 1_000_000L
        val now = base + PostCallAdvisor.WINDOW_MS
        prefs.edit().putLong("postcall_last_09012345678", base).apply()
        PostCallAdvisor.pruneStaleRateKeys(prefs, now)
        assertFalse("key at exact boundary is expired and must be pruned",
            prefs.contains("postcall_last_09012345678"))
    }
}

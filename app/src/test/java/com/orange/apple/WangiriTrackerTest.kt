package com.orange.apple

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WangiriTrackerTest {

    @Test fun empty_snapshot_is_empty_map() {
        val p = FakePrefs()
        assertTrue(WangiriTracker.snapshot(p, 1000L).isEmpty())
    }

    @Test fun recorded_short_ring_appears_in_snapshot() {
        val p = FakePrefs()
        WangiriTracker.record(p, "+675100", 5000L)
        val snap = WangiriTracker.snapshot(p, 6000L)
        assertEquals(5000L, snap["+675100"])
    }

    @Test fun expired_entries_pruned_on_read() {
        val p = FakePrefs()
        val recordedAt = 1000L
        WangiriTracker.record(p, "+999", recordedAt)
        // Read after the 6h window has elapsed.
        val now = recordedAt + WANGIRI_WINDOW_MS + 1
        assertNull(WangiriTracker.snapshot(p, now)["+999"])
    }

    @Test fun entry_at_exact_window_boundary_is_pruned() {
        val p = FakePrefs()
        val recordedAt = 1000L
        WangiriTracker.record(p, "+999", recordedAt)
        // Exactly WANGIRI_WINDOW_MS later — boundary check is "now - ts < window",
        // so equality means it's already gone.
        val now = recordedAt + WANGIRI_WINDOW_MS
        assertNull(WangiriTracker.snapshot(p, now)["+999"])
    }

    @Test fun entry_one_ms_inside_window_survives() {
        val p = FakePrefs()
        val recordedAt = 1000L
        WangiriTracker.record(p, "+999", recordedAt)
        val now = recordedAt + WANGIRI_WINDOW_MS - 1
        assertEquals(recordedAt, WangiriTracker.snapshot(p, now)["+999"])
    }

    @Test fun more_than_64_entries_keeps_only_newest() {
        val p = FakePrefs()
        // Record 70 entries with monotonically increasing timestamps.
        for (i in 0 until 70) {
            WangiriTracker.record(p, "+$i", 1000L + i.toLong())
        }
        val snap = WangiriTracker.snapshot(p, 1100L)
        assertEquals(WangiriTracker.MAX_ENTRIES, snap.size)
        // Oldest 6 should be evicted (70 - 64 = 6).
        for (i in 0 until 6) {
            assertFalse("expected +$i to be evicted", "+$i" in snap)
        }
        // Newest 64 should remain.
        for (i in 6 until 70) {
            assertTrue("expected +$i to survive", "+$i" in snap)
        }
    }

    @Test fun forget_removes_entry() {
        val p = FakePrefs()
        WangiriTracker.record(p, "+111", 5000L)
        WangiriTracker.record(p, "+222", 6000L)
        WangiriTracker.forget(p, "+111")
        val snap = WangiriTracker.snapshot(p, 6500L)
        assertFalse("+111" in snap)
        assertTrue("+222" in snap)
    }

    @Test fun forget_nonexistent_entry_is_safe() {
        val p = FakePrefs()
        WangiriTracker.forget(p, "+nope")
        // No exception thrown; snapshot remains empty.
        assertTrue(WangiriTracker.snapshot(p, 1000L).isEmpty())
    }

    @Test fun re_record_updates_timestamp() {
        val p = FakePrefs()
        WangiriTracker.record(p, "+111", 1000L)
        WangiriTracker.record(p, "+111", 2000L)
        assertEquals(2000L, WangiriTracker.snapshot(p, 3000L)["+111"])
    }
}

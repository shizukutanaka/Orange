package com.orange.apple

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class WangiriTrackerTest {

    private lateinit var prefs: FakePrefs

    @Before fun setUp() { prefs = FakePrefs() }

    private val t0 = 1_700_000_000_000L
    private val number = "+819012345678"

    @Test fun `recorded number appears in snapshot`() {
        WangiriTracker.record(prefs, number, t0)
        val snap = WangiriTracker.snapshot(prefs, t0 + 1_000)
        assertEquals(t0, snap[number])
    }

    @Test fun `entry expires after 6 hours`() {
        WangiriTracker.record(prefs, number, t0)
        val after = t0 + WANGIRI_WINDOW_MS + 1
        val snap = WangiriTracker.snapshot(prefs, after)
        assertNull(snap[number])
    }

    @Test fun `entry still present just before expiry`() {
        WangiriTracker.record(prefs, number, t0)
        val justBefore = t0 + WANGIRI_WINDOW_MS - 1
        val snap = WangiriTracker.snapshot(prefs, justBefore)
        assertNotNull(snap[number])
    }

    @Test fun `empty number is not recorded`() {
        WangiriTracker.record(prefs, "", t0)
        val snap = WangiriTracker.snapshot(prefs, t0)
        assertFalse(snap.containsKey(""))
    }

    @Test fun `forget removes entry`() {
        WangiriTracker.record(prefs, number, t0)
        WangiriTracker.forget(prefs, number)
        val snap = WangiriTracker.snapshot(prefs, t0 + 1_000)
        assertNull(snap[number])
    }

    @Test fun `forget prunes expired entries as side effect`() {
        // Record two entries at t0. Advance to just past the window so that 'expired'
        // is stale, but call forget() with the same nowMs so snapshot() inside forget()
        // sees both entries (neither is expired yet at t0+1). The forgotten number is
        // removed; the other is written back. Then snapshot at nowPastWindow drops it
        // because the window has elapsed.
        val other = "+819099999999"
        WangiriTracker.record(prefs, other, t0)
        WangiriTracker.record(prefs, number, t0)
        // forget() with t0+1 (inside the window) so snapshot inside forget() sees both.
        WangiriTracker.forget(prefs, number, t0 + 1)
        // snapshot at t0 + window + 1 — 'other' entry is now expired.
        val nowPastWindow = t0 + WANGIRI_WINDOW_MS + 1
        val snap = WangiriTracker.snapshot(prefs, nowPastWindow)
        assertNull("forgotten number must be gone", snap[number])
        assertNull("other entry must have expired", snap[other])
        // Verify 'other' still exists in the raw store but expired (not lost by forget()).
        val snapAtT0 = WangiriTracker.snapshot(prefs, t0 + 2)
        assertNotNull("other must still be in store immediately after forget()", snapAtT0[other])
    }

    @Test fun `max entries bound respected`() {
        repeat(WangiriTracker.MAX_ENTRIES + 10) { i ->
            WangiriTracker.record(prefs, "+8190${i.toString().padStart(8, '0')}", t0 + i)
        }
        val snap = WangiriTracker.snapshot(prefs, t0 + 100)
        assertTrue("snapshot should be bounded", snap.size <= WangiriTracker.MAX_ENTRIES)
    }
}

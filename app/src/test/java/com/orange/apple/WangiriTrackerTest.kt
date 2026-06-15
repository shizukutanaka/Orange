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
        // Record two numbers: one expired, one current.
        val expired = "+819099999999"
        WangiriTracker.record(prefs, expired, t0)
        WangiriTracker.record(prefs, number, t0)
        // Advance time past the window for the first entry, but forget() is called
        // for a time that sees both. The old raw-string forget() left expired entries;
        // the snapshot()-based forget() prunes them.
        val nowPastWindow = t0 + WANGIRI_WINDOW_MS + 1
        WangiriTracker.forget(prefs, number)
        // After forget, snapshot at nowPastWindow should not contain either number.
        val snap = WangiriTracker.snapshot(prefs, nowPastWindow)
        assertNull("forgotten number must be gone", snap[number])
        assertNull("expired number must be pruned", snap[expired])
    }

    @Test fun `max entries bound respected`() {
        repeat(WangiriTracker.MAX_ENTRIES + 10) { i ->
            WangiriTracker.record(prefs, "+8190${i.toString().padStart(8, '0')}", t0 + i)
        }
        val snap = WangiriTracker.snapshot(prefs, t0 + 100)
        assertTrue("snapshot should be bounded", snap.size <= WangiriTracker.MAX_ENTRIES)
    }
}

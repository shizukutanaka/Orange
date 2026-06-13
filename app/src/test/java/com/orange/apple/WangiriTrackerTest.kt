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

    @Test fun `max entries bound respected`() {
        repeat(WangiriTracker.MAX_ENTRIES + 10) { i ->
            WangiriTracker.record(prefs, "+8190${i.toString().padStart(8, '0')}", t0 + i)
        }
        val snap = WangiriTracker.snapshot(prefs, t0 + 100)
        assertTrue("snapshot should be bounded", snap.size <= WangiriTracker.MAX_ENTRIES)
    }
}

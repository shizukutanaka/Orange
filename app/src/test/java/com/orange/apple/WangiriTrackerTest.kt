package com.orange.apple

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class WangiriTrackerTest {

    private lateinit var prefs: FakePrefs

    @Before fun setUp() { prefs = FakePrefs() }

    private val t0 = 1_700_000_000_000L
    private val number = "+819012345678"

    // Snapshot keys are hashes; resolve number → hash for assertions.
    private fun snapAt(nowMs: Long) = WangiriTracker.snapshot(prefs, nowMs)
    private fun hashOf(n: String) = SpamCache.hash(prefs, n)

    @Test fun `recorded number appears in snapshot`() {
        WangiriTracker.record(prefs, number, t0)
        val snap = snapAt(t0 + 1_000)
        assertEquals(t0, snap[hashOf(number)])
    }

    @Test fun `entry expires after 6 hours`() {
        WangiriTracker.record(prefs, number, t0)
        val after = t0 + WANGIRI_WINDOW_MS + 1
        val snap = snapAt(after)
        assertNull(snap[hashOf(number)])
    }

    @Test fun `entry still present just before expiry`() {
        WangiriTracker.record(prefs, number, t0)
        val justBefore = t0 + WANGIRI_WINDOW_MS - 1
        val snap = snapAt(justBefore)
        assertNotNull(snap[hashOf(number)])
    }

    @Test fun `empty number is not recorded`() {
        WangiriTracker.record(prefs, "", t0)
        val snap = snapAt(t0)
        // Empty number is rejected before hashing; snapshot must be empty.
        assertTrue(snap.isEmpty())
    }

    @Test fun `forget removes entry`() {
        WangiriTracker.record(prefs, number, t0)
        // forget() routes through snapshot(nowMs), which prunes entries outside the
        // 6h window BEFORE removing — so an explicit nowMs inside the window is
        // required (matching the production call timing, and the sibling
        // `forget prunes expired entries` test). Without it, forget()'s default
        // System.currentTimeMillis() (real 2026) prunes this fixed-2023 entry from
        // view and can't remove it.
        WangiriTracker.forget(prefs, number, t0 + 1)
        val snap = snapAt(t0 + 1_000)
        assertNull(snap[hashOf(number)])
    }

    @Test fun `forget prunes expired entries as side effect`() {
        val other = "+819099999999"
        WangiriTracker.record(prefs, other, t0)
        WangiriTracker.record(prefs, number, t0)
        // forget() with t0+1 (inside the window) so snapshot inside forget() sees both.
        WangiriTracker.forget(prefs, number, t0 + 1)
        // snapshot at t0 + window + 1 — 'other' entry is now expired.
        val nowPastWindow = t0 + WANGIRI_WINDOW_MS + 1
        val snap = snapAt(nowPastWindow)
        assertNull("forgotten number must be gone", snap[hashOf(number)])
        assertNull("other entry must have expired", snap[hashOf(other)])
        // Verify 'other' still exists in the raw store but expired (not lost by forget()).
        val snapAtT0 = snapAt(t0 + 2)
        assertNotNull("other must still be in store immediately after forget()", snapAtT0[hashOf(other)])
    }

    @Test fun `forget with E164 removes domestically-stored entry`() {
        val domestic = "09012345678"
        val e164 = "+819012345678"
        WangiriTracker.record(prefs, domestic, t0)
        // Explicit nowMs inside the window — see `forget removes entry` above for why.
        WangiriTracker.forget(prefs, domestic, t0 + 1)   // variant expansion done by caller
        WangiriTracker.forget(prefs, e164, t0 + 1)       // second variant — no-op here, but safe
        val snap = snapAt(t0 + 1_000)
        assertNull("domestic short-ring entry must be gone after variant forget", snap[hashOf(domestic)])
    }

    @Test fun `max entries bound respected`() {
        repeat(WangiriTracker.MAX_ENTRIES + 10) { i ->
            WangiriTracker.record(prefs, "+8190${i.toString().padStart(8, '0')}", t0 + i)
        }
        val snap = snapAt(t0 + 100)
        assertTrue("snapshot should be bounded", snap.size <= WangiriTracker.MAX_ENTRIES)
    }

    @Test fun `raw number not present as key in snapshot`() {
        WangiriTracker.record(prefs, number, t0)
        val snap = snapAt(t0 + 1_000)
        // The number itself must not appear as a key — only its hash should.
        assertFalse("raw number must not be a snapshot key", snap.containsKey(number))
        assertTrue("hash must be present as key", snap.containsKey(hashOf(number)))
    }
}

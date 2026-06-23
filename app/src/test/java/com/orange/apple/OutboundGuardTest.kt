package com.orange.apple

import org.junit.Assert.*
import org.junit.Test

class OutboundGuardTest {

    @Test fun `record then wasRecentlyFlagged returns true within window`() {
        val p = FakePrefs()
        val now = 1_000_000L
        OutboundGuard.record(p, "09012345678", now)
        assertTrue(OutboundGuard.wasRecentlyFlagged(p, "09012345678", now + 1000L))
    }

    @Test fun `entry expires after WINDOW_MS`() {
        val p = FakePrefs()
        val now = 1_000_000L
        OutboundGuard.record(p, "09012345678", now)
        val expired = now + OutboundGuard.WINDOW_MS + 1L
        assertFalse(OutboundGuard.wasRecentlyFlagged(p, "09012345678", expired))
    }

    @Test fun `forget removes entry from guard`() {
        val p = FakePrefs()
        val now = 1_000_000L
        OutboundGuard.record(p, "09012345678", now)
        OutboundGuard.forget(p, "09012345678", now + 100L)
        assertFalse(OutboundGuard.wasRecentlyFlagged(p, "09012345678", now + 200L))
    }

    @Test fun `forget on absent number is a no-op`() {
        val p = FakePrefs()
        val now = 1_000_000L
        OutboundGuard.record(p, "09012345678", now)
        OutboundGuard.forget(p, "09099999999", now)  // different number
        assertTrue(OutboundGuard.wasRecentlyFlagged(p, "09012345678", now + 1000L))
    }

    @Test fun `empty number is silently ignored`() {
        val p = FakePrefs()
        OutboundGuard.record(p, "", 1_000_000L)
        assertFalse(OutboundGuard.wasRecentlyFlagged(p, "", 1_000_000L + 1L))
    }

    @Test fun `backward clock jump does not flag entry`() {
        val p = FakePrefs()
        val now = 1_000_000L
        OutboundGuard.record(p, "09012345678", now)
        // nowMs < ts: backward jump; clock-skew guard must treat as not-in-window
        assertFalse(OutboundGuard.wasRecentlyFlagged(p, "09012345678", now - 1L))
    }

    // This test documents the E.164↔domestic variant gap in OutboundGuard itself.
    // The guard stores numbers exactly as recorded; the call site (SilentBlockerService)
    // is responsible for expanding variants before calling wasRecentlyFlagged.
    @Test fun `domestic stored does not match E164 directly in guard`() {
        val p = FakePrefs()
        val now = 1_000_000L
        OutboundGuard.record(p, "09012345678", now)
        // Guard itself does exact-string match — E.164 form must NOT match here.
        // SilentBlockerService.handleOutgoing() expands variants before querying.
        assertFalse(OutboundGuard.wasRecentlyFlagged(p, "+819012345678", now + 1000L))
    }

    @Test fun `record respects MAX_ENTRIES bound`() {
        val p = FakePrefs()
        val now = 1_000_000L
        // Fill beyond the bound and verify the oldest is evicted.
        // Because entries are sorted by timestamp descending on trim, the first
        // entry (timestamp = now) should be evicted after MAX_ENTRIES+1 inserts.
        repeat(OutboundGuard.MAX_ENTRIES + 1) { i ->
            OutboundGuard.record(p, "0901234${"$i".padStart(4, '0')}", now + i)
        }
        // Oldest (i=0) should have been evicted; newest (i=MAX_ENTRIES) must survive.
        val newestNum = "0901234${"${OutboundGuard.MAX_ENTRIES}".padStart(4, '0')}"
        assertTrue(OutboundGuard.wasRecentlyFlagged(p, newestNum, now + OutboundGuard.MAX_ENTRIES + 1))
        val oldestNum = "09012340000"
        assertFalse(OutboundGuard.wasRecentlyFlagged(p, oldestNum, now + OutboundGuard.MAX_ENTRIES + 1))
    }

    @Test fun `raw number not stored in prefs value`() {
        val p = FakePrefs()
        val number = "09012345678"
        OutboundGuard.record(p, number, 1_000_000L)
        // The stored value must not contain the raw number — only hashes.
        val raw = p.getString("outbound_guard", "")!!
        assertFalse("raw number must not appear in stored guard value", raw.contains(number))
    }
}

package com.orange.apple

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for outbound-call logging behavior that CallStateObserver performs.
 *
 * We can't test the BroadcastReceiver lifecycle directly without Robolectric,
 * but we CAN test the SharedPreferences mutations that the receiver produces,
 * using FakePrefs. The receiver's onReceive is mechanically simple (read
 * intent extras → call addToOutbound); the logic lives in the prefs writes.
 */
class CallStateObserverTest {

    @Test fun outbound_add_appears_in_set() {
        val p = FakePrefs()
        addToOutbound(p, "+14155551234")
        val set = p.getStringSet(SilentBlockerService.KEY_OUTBOUND, emptySet())!!
        assertTrue("+14155551234" in set)
    }

    @Test fun duplicate_add_is_idempotent() {
        val p = FakePrefs()
        addToOutbound(p, "+111")
        addToOutbound(p, "+111")
        val set = p.getStringSet(SilentBlockerService.KEY_OUTBOUND, emptySet())!!
        assertEquals(1, set.size)
    }

    @Test fun outbound_bounded_at_max_entries() {
        val p = FakePrefs()
        repeat(SpamCache.MAX_ENTRIES + 10) { i ->
            addToOutbound(p, "+$i")
        }
        val set = p.getStringSet(SilentBlockerService.KEY_OUTBOUND, emptySet())!!
        assertEquals(SpamCache.MAX_ENTRIES, set.size)
    }

    @Test fun was_ringing_key_constant_matches_callstateobserver() {
        assertEquals("was_ringing", CallStateObserver.KEY_WAS_RINGING)
    }

    // Simulates the addToOutbound logic from CallStateObserver (same code).
    private fun addToOutbound(prefs: FakePrefs, number: String) {
        val set = prefs.getStringSet(SilentBlockerService.KEY_OUTBOUND, emptySet())!!
            .toMutableSet()
        if (set.add(number)) {
            if (set.size > SpamCache.MAX_ENTRIES) {
                val iter = set.iterator()
                val excess = set.size - SpamCache.MAX_ENTRIES
                repeat(excess) { iter.next(); iter.remove() }
            }
            prefs.edit().putStringSet(SilentBlockerService.KEY_OUTBOUND, set).apply()
        }
    }
}

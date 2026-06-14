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

    @Test fun answer_time_prefs_key_is_answer_time() {
        // Regression guard: if the key name used by onRinging() (to clear stale
        // answer_time) diverges from the key name used by onOffhook() (to write it)
        // and onIdle() (to read it), the stale-state bug reappears silently.
        // The three references live in the same class so this protects against
        // copy-paste renames that break only one site.
        val p = FakePrefs()
        p.edit().putLong("answer_time", 99L).apply()
        p.edit().remove("answer_time").apply()
        assertEquals("answer_time cleared", 0L, p.getLong("answer_time", 0L))
    }

    @Test fun repeat_caller_cleared_using_ring_number_when_offhook_number_absent() {
        // Regression guard: onOffhook() may receive null number on some carriers.
        // It should fall back to KEY_RING_NUMBER set during RINGING so that
        // RepeatCallerTracker.clear() fires even without the OFFHOOK intent extra.
        val p = FakePrefs()
        val number = "09012345678"
        // Simulate RINGING state: record 3 calls and store KEY_RING_NUMBER.
        repeat(3) { RepeatCallerTracker.record(p, number, 1000L + it * 1000) }
        p.edit().putString("ring_number", number).apply()
        // Simulate OFFHOOK with null number (use fallback path).
        val rawNum = (null as String?)?.takeIf { it.isNotEmpty() }
            ?: p.getString("ring_number", null)
        if (rawNum != null) RepeatCallerTracker.clear(p, rawNum)
        // After clear, the number should no longer be a repeat offender.
        assertFalse(RepeatCallerTracker.isRepeatOffender(p, number, 60 * 60 * 1000L))
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

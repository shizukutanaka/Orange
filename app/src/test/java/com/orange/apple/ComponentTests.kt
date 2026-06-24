package com.orange.apple

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RepeatCallerTracker component tests.
 *
 * PoliceStationDirectoryTest and CaribbeanPremiumNANPTest were originally
 * in this file but moved to their own files for coverage breadth. The
 * duplicate class names caused a Kotlin compilation error; only
 * RepeatCallerTrackerComponentTest remains here (its companion
 * RepeatCallerTrackerTest.kt covers additional hash/serialization cases).
 */
class RepeatCallerTrackerComponentTest {

    @Test fun first_two_calls_not_flagged() {
        val p = FakePrefs()
        RepeatCallerTracker.record(p, "+111", 1000L)
        RepeatCallerTracker.record(p, "+111", 2000L)
        assertFalse(RepeatCallerTracker.isRepeatOffender(p, "+111", 3000L))
    }

    @Test fun fourth_call_triggers_flag() {
        // N_THRESHOLD = 3: block fires on the (N_THRESHOLD+1)th = 4th call.
        // calls.size > N_THRESHOLD → 4 > 3 → true.
        val p = FakePrefs()
        RepeatCallerTracker.record(p, "+111", 1000L)
        RepeatCallerTracker.record(p, "+111", 2000L)
        RepeatCallerTracker.record(p, "+111", 3000L)
        assertFalse("3rd call must NOT trigger flag yet",
            RepeatCallerTracker.isRepeatOffender(p, "+111", 3500L))
        RepeatCallerTracker.record(p, "+111", 4000L)
        assertTrue("4th call must trigger flag",
            RepeatCallerTracker.isRepeatOffender(p, "+111", 5000L))
    }

    @Test fun different_numbers_independent() {
        val p = FakePrefs()
        repeat(3) { RepeatCallerTracker.record(p, "+111", it * 1000L) }
        assertFalse(RepeatCallerTracker.isRepeatOffender(p, "+222", 5000L))
    }

    @Test fun expired_calls_not_counted() {
        val p = FakePrefs()
        val old = 1000L
        val now = old + RepeatCallerTracker.WINDOW_MS + 1
        RepeatCallerTracker.record(p, "+111", old)
        RepeatCallerTracker.record(p, "+111", old + 100L)
        RepeatCallerTracker.record(p, "+111", old + 200L)
        // All entries are beyond the window — should not flag
        assertFalse(RepeatCallerTracker.isRepeatOffender(p, "+111", now))
    }

    @Test fun empty_string_not_recorded() {
        val p = FakePrefs()
        RepeatCallerTracker.record(p, "", 1000L)
        assertFalse(RepeatCallerTracker.isRepeatOffender(p, "", 2000L))
    }

    @Test fun clear_drops_malformed_entries() {
        // Entries without ':' are malformed; clear() must not retain them.
        val p = FakePrefs()
        // Inject a malformed entry directly into prefs
        p.edit().putString("repeat_caller", "malformed_no_colon|+111:1000").apply()
        RepeatCallerTracker.clear(p, "+111")
        val raw = p.getString("repeat_caller", "") ?: ""
        assertFalse("Malformed entry must be dropped by clear()", raw.contains("malformed_no_colon"))
    }

    @Test fun clear_removes_entries() {
        val p = FakePrefs()
        // Record N_THRESHOLD + 1 calls to actually trigger offender status.
        // N_THRESHOLD calls alone only reach calls.size == N_THRESHOLD, which
        // satisfies calls.size > N_THRESHOLD → false (not flagged yet).
        repeat(RepeatCallerTracker.N_THRESHOLD + 1) {
            RepeatCallerTracker.record(p, "+111", it * 1000L)
        }
        val checkTime = (RepeatCallerTracker.N_THRESHOLD + 1) * 1000L + 1
        // Was flagged
        assertTrue(RepeatCallerTracker.isRepeatOffender(p, "+111", checkTime))
        // User answers → clear
        RepeatCallerTracker.clear(p, "+111")
        assertFalse(RepeatCallerTracker.isRepeatOffender(p, "+111", checkTime + 1))
    }
}

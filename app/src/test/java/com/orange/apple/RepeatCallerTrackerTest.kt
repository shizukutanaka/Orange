package com.orange.apple

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RepeatCallerTrackerTest {

    private lateinit var prefs: FakePrefs

    @Before fun setUp() { prefs = FakePrefs() }

    private val t0 = 1_700_000_000_000L
    private val number = "09012345678"

    @Test fun `first call is not a repeat offender`() {
        RepeatCallerTracker.record(prefs, number, t0)
        assertFalse(RepeatCallerTracker.isRepeatOffender(prefs, number, t0))
    }

    @Test fun `second call is not a repeat offender`() {
        RepeatCallerTracker.record(prefs, number, t0)
        RepeatCallerTracker.record(prefs, number, t0 + 1_000)
        assertFalse(RepeatCallerTracker.isRepeatOffender(prefs, number, t0 + 2_000))
    }

    @Test fun `third call is not a repeat offender`() {
        repeat(3) { i -> RepeatCallerTracker.record(prefs, number, t0 + i * 1_000L) }
        assertFalse(RepeatCallerTracker.isRepeatOffender(prefs, number, t0 + 4_000))
    }

    @Test fun `fourth call triggers repeat offender - block on 4th`() {
        repeat(4) { i -> RepeatCallerTracker.record(prefs, number, t0 + i * 1_000L) }
        assertTrue(RepeatCallerTracker.isRepeatOffender(prefs, number, t0 + 5_000))
    }

    @Test fun `entries outside window are excluded`() {
        val old = t0 - RepeatCallerTracker.WINDOW_MS - 1
        RepeatCallerTracker.record(prefs, number, old)
        RepeatCallerTracker.record(prefs, number, old)
        RepeatCallerTracker.record(prefs, number, old)
        RepeatCallerTracker.record(prefs, number, old)
        assertFalse(RepeatCallerTracker.isRepeatOffender(prefs, number, t0))
    }

    @Test fun `clear resets counter`() {
        repeat(4) { i -> RepeatCallerTracker.record(prefs, number, t0 + i * 1_000L) }
        assertTrue(RepeatCallerTracker.isRepeatOffender(prefs, number, t0 + 5_000))
        RepeatCallerTracker.clear(prefs, number)
        assertFalse(RepeatCallerTracker.isRepeatOffender(prefs, number, t0 + 6_000))
    }

    @Test fun `empty number is ignored`() {
        RepeatCallerTracker.record(prefs, "", t0)
        assertFalse(RepeatCallerTracker.isRepeatOffender(prefs, "", t0))
    }

    @Test fun `different numbers tracked independently`() {
        val other = "08087654321"
        repeat(4) { i -> RepeatCallerTracker.record(prefs, number, t0 + i * 1_000L) }
        assertFalse(RepeatCallerTracker.isRepeatOffender(prefs, other, t0 + 5_000))
    }
}

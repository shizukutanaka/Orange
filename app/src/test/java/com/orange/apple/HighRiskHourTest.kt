package com.orange.apple

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for isHighRiskHour() — the アポ電 peak-hour gate.
 *
 * All times are expressed as epoch milliseconds.  The reference anchor is
 * 2024-01-08 (Monday) 00:00 UTC = 09:00 JST.
 *
 * JST = UTC+9, so:
 *   JST hour H  =  UTC timestamp (Mon 00:00 UTC) + (H - 9) * 3600_000
 */
class HighRiskHourTest {

    // 2024-01-08 (Mon) 00:00 UTC = 09:00 JST
    private val MON_09_00_JST = 1_704_672_000_000L

    private fun jstHour(baseMs: Long, hourJst: Int): Long =
        baseMs + (hourJst - 9).toLong() * 3_600_000L

    // --- Weekday peak windows ---

    @Test fun weekday_09_is_peak() {
        assertTrue(isHighRiskHour(jstHour(MON_09_00_JST, 9)))
    }

    @Test fun weekday_10_is_peak() {
        assertTrue(isHighRiskHour(jstHour(MON_09_00_JST, 10)))
    }

    @Test fun weekday_12_is_peak() {
        assertTrue(isHighRiskHour(jstHour(MON_09_00_JST, 12)))
    }

    @Test fun weekday_13_is_peak() {
        assertTrue(isHighRiskHour(jstHour(MON_09_00_JST, 13)))
    }

    @Test fun weekday_16_is_peak() {
        assertTrue(isHighRiskHour(jstHour(MON_09_00_JST, 16)))
    }

    // --- Weekday off-peak boundaries ---

    @Test fun weekday_08_is_off_peak() {
        assertFalse(isHighRiskHour(jstHour(MON_09_00_JST, 8)))
    }

    @Test fun weekday_17_is_off_peak() {
        assertFalse(isHighRiskHour(jstHour(MON_09_00_JST, 17)))
    }

    @Test fun weekday_00_is_off_peak() {
        assertFalse(isHighRiskHour(jstHour(MON_09_00_JST, 0)))
    }

    @Test fun weekday_23_is_off_peak() {
        assertFalse(isHighRiskHour(jstHour(MON_09_00_JST, 23)))
    }

    // --- No lunch gap: 12:xx and 13:xx are both peak ---

    @Test fun no_lunch_gap_12_59() {
        val twelve59 = jstHour(MON_09_00_JST, 12) + 59 * 60_000L
        assertTrue(isHighRiskHour(twelve59))
    }

    @Test fun no_lunch_gap_13_00() {
        assertTrue(isHighRiskHour(jstHour(MON_09_00_JST, 13)))
    }

    // --- Weekends are never peak ---

    @Test fun saturday_10_is_off_peak() {
        // Sat = Mon + 5 days
        val sat10 = MON_09_00_JST + 5 * 86_400_000L + (10 - 9) * 3_600_000L
        assertFalse(isHighRiskHour(sat10))
    }

    @Test fun sunday_10_is_off_peak() {
        val sun10 = MON_09_00_JST + 6 * 86_400_000L + (10 - 9) * 3_600_000L
        assertFalse(isHighRiskHour(sun10))
    }

    // --- JST is used regardless of system timezone ---
    // The function hard-codes Asia/Tokyo.  A device in UTC+0 getting a call at
    // 09:00 local time on a Monday would be 00:00 UTC = 09:00 JST — same epoch,
    // same result.  This test asserts on the raw epoch value, confirming the
    // implementation doesn't bleed through to the default JVM timezone.

    @Test fun epoch_anchor_is_jst_not_utc() {
        // MON_09_00_JST is exactly 09:00 JST, which is a peak hour.
        // If the function used UTC instead, hour() == 0 → off-peak.
        assertTrue(isHighRiskHour(MON_09_00_JST))
    }
}

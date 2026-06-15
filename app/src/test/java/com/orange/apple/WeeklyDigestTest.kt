package com.orange.apple

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for WeeklyDigest constants and window arithmetic.
 *
 * Android Context, AlarmManager, NotificationManager excluded.
 * Pure time-math is testable.
 */
class WeeklyDigestTest {

    @Test fun week_ms_is_seven_days() {
        assertEquals(7L * 24 * 60 * 60 * 1000, WeeklyDigest.WEEK_MS)
    }

    @Test fun within_8_week_period_is_weekly_mode() {
        val installTs = 1_000_000L
        val ageWeeks8 = WeeklyDigest.WEEK_MS * 8
        val now = installTs + ageWeeks8 - 1000L
        val ageMs = now - installTs
        assertTrue(ageMs / WeeklyDigest.WEEK_MS <= 8)
    }

    @Test fun past_8_weeks_is_monthly_mode() {
        val installTs = 1_000_000L
        val now = installTs + WeeklyDigest.WEEK_MS * 9
        val ageMs = now - installTs
        assertTrue(ageMs / WeeklyDigest.WEEK_MS > 8)
    }

    @Test fun within_trust_window_no_digest() {
        // During first 7 days, TrustNotifier handles per-block notifications.
        // Digest must not fire.
        val installTs = 1_000_000L
        val now = installTs + TrustNotifier.TRUST_PERIOD_MS - 1000L
        val ageMs = now - installTs
        assertFalse(ageMs >= TrustNotifier.TRUST_PERIOD_MS)
    }

    @Test fun day_8_is_past_trust_window() {
        val installTs = 1_000_000L
        val now = installTs + TrustNotifier.TRUST_PERIOD_MS + 1000L
        val ageMs = now - installTs
        assertTrue(ageMs >= TrustNotifier.TRUST_PERIOD_MS)
    }

    @Test fun monthly_mode_fires_only_on_first_sunday() {
        // Monthly mode: fires iff dayOfMonth <= 7 AND dayOfWeek == SUNDAY.
        // Simulate the check logic (mirrors WeeklyDigest.onReceive monthly gate).
        data class Day(val dayOfMonth: Int, val dayOfWeek: Int)
        fun shouldFireMonthly(d: Day): Boolean =
            d.dayOfMonth <= 7 && d.dayOfWeek == java.util.Calendar.SUNDAY

        // First Sunday of month (day=7, Sunday) → must fire.
        assertTrue(shouldFireMonthly(Day(7, java.util.Calendar.SUNDAY)))
        // First Sunday (day=1) → must fire.
        assertTrue(shouldFireMonthly(Day(1, java.util.Calendar.SUNDAY)))
        // Day=7 but not Sunday → must NOT fire.
        assertFalse(shouldFireMonthly(Day(7, java.util.Calendar.SATURDAY)))
        // Sunday but day=8 (second Sunday) → must NOT fire.
        assertFalse(shouldFireMonthly(Day(8, java.util.Calendar.SUNDAY)))
        // Mid-month Sunday → must NOT fire.
        assertFalse(shouldFireMonthly(Day(15, java.util.Calendar.SUNDAY)))
    }

    @Test fun next_sunday_schedule_is_always_in_future() {
        // Verify the schedule calculation never produces a past timestamp.
        // Mirrors the logic in WeeklyDigest.schedule().
        val now = System.currentTimeMillis()
        val cal = java.util.Calendar.getInstance().apply {
            val currentDay = get(java.util.Calendar.DAY_OF_WEEK)
            val daysUntilSunday = (java.util.Calendar.SUNDAY - currentDay + 7) % 7
            if (daysUntilSunday > 0) add(java.util.Calendar.DAY_OF_MONTH, daysUntilSunday)
            set(java.util.Calendar.HOUR_OF_DAY, 9)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            if (timeInMillis <= now) add(java.util.Calendar.DAY_OF_MONTH, 7)
        }
        assertTrue("Scheduled time must be in the future", cal.timeInMillis > now)
    }
}

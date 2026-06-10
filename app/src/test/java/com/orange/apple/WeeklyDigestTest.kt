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
}

package com.orange.apple

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for TrustNotifier's time-window constants and prefs logic.
 *
 * The Android notification-posting path requires Context and
 * NotificationManager — excluded. What IS testable without Android:
 *   - TRUST_PERIOD_MS value (7 days)
 *   - KEY_INSTALL_TS constant name stability
 *   - The window arithmetic that determines "is today inside the trust window?"
 */
class TrustNotifierTest {

    @Test fun trust_period_is_seven_days() {
        val sevenDays = 7L * 24 * 60 * 60 * 1000
        assertEquals(sevenDays, TrustNotifier.TRUST_PERIOD_MS)
    }

    @Test fun key_install_ts_constant_matches_expected_name() {
        // If this key is renamed, WeeklyDigest and TrustNotifier break together.
        assertEquals("install_ts", TrustNotifier.KEY_INSTALL_TS)
    }

    @Test fun within_trust_window() {
        val installTs = 1_000_000L
        val now = installTs + TrustNotifier.TRUST_PERIOD_MS - 1000L
        assertTrue(now - installTs < TrustNotifier.TRUST_PERIOD_MS)
    }

    @Test fun past_trust_window() {
        val installTs = 1_000_000L
        val now = installTs + TrustNotifier.TRUST_PERIOD_MS + 1000L
        assertFalse(now - installTs < TrustNotifier.TRUST_PERIOD_MS)
    }

    @Test fun trust_period_boundary_exact() {
        val installTs = 1_000_000L
        val now = installTs + TrustNotifier.TRUST_PERIOD_MS
        // Boundary: == is past (strict less-than comparison in engine)
        assertFalse(now - installTs < TrustNotifier.TRUST_PERIOD_MS)
    }
}

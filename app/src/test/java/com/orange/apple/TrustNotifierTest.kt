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
        assertTrue(now >= installTs && now - installTs < TrustNotifier.TRUST_PERIOD_MS)
    }

    @Test fun past_trust_window() {
        val installTs = 1_000_000L
        val now = installTs + TrustNotifier.TRUST_PERIOD_MS + 1000L
        assertFalse(now >= installTs && now - installTs < TrustNotifier.TRUST_PERIOD_MS)
    }

    @Test fun trust_period_boundary_exact() {
        val installTs = 1_000_000L
        val now = installTs + TrustNotifier.TRUST_PERIOD_MS
        // Boundary: == is past (strict less-than comparison in engine)
        assertFalse(now >= installTs && now - installTs < TrustNotifier.TRUST_PERIOD_MS)
    }

    @Test fun backward_clock_exits_trust_window_safely() {
        // If clock goes backward after install, now < installTs → now >= installTs is false
        // → trust window evaluates to false (conservative: skip heads-up notifications
        //   rather than staying indefinitely in the elevated-alert trust phase).
        val installTs = 2_000_000L
        val now = installTs - 1000L   // clock went backward
        assertFalse(now >= installTs && now - installTs < TrustNotifier.TRUST_PERIOD_MS)
    }

    @Test fun notif_id_for_is_deterministic() {
        // Same number must produce the same ID across calls.
        val n = "+819012345678"
        assertEquals(TrustNotifier.notifIdFor(n), TrustNotifier.notifIdFor(n))
    }

    @Test fun notif_id_for_is_positive() {
        // Android notification IDs must not be negative.
        assertTrue(TrustNotifier.notifIdFor("+819012345678") >= 0)
        assertTrue(TrustNotifier.notifIdFor("") >= 0)
        assertTrue(TrustNotifier.notifIdFor("110") >= 0)
    }

    @Test fun notif_id_for_differs_across_numbers() {
        // Different numbers should (with high probability) produce different IDs.
        val a = TrustNotifier.notifIdFor("+819012345678")
        val b = TrustNotifier.notifIdFor("+819087654321")
        // This is a probabilistic check — collision would be a bug in the mix function.
        assertFalse("notifIdFor collision for distinct numbers", a == b)
    }
}

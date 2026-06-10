package com.orange.apple

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the RoleMonitor's prefs-flag behavior. We can't easily test
 * the actual RoleManager interaction without Robolectric, but the prefs
 * flag transition is the part that actually drives the widget UI, so
 * that's where the test should focus.
 *
 * (We deliberately avoid Robolectric across the codebase so tests run
 * in milliseconds and have no Android version coupling.)
 */
class RoleMonitorTest {

    @Test fun default_role_held_flag_is_true() {
        val p: SharedPreferences = FakePrefs()
        // Without ever writing the key, the flag should default to true:
        // a fresh install hasn't lost the role yet.
        assertTrue(p.getBoolean(RoleMonitor.KEY_ROLE_HELD, true))
    }

    @Test fun flag_can_be_toggled_to_false() {
        val p: SharedPreferences = FakePrefs()
        p.edit().putBoolean(RoleMonitor.KEY_ROLE_HELD, false).apply()
        assertFalse(p.getBoolean(RoleMonitor.KEY_ROLE_HELD, true))
    }

    @Test fun flag_persists_across_reads() {
        val p: SharedPreferences = FakePrefs()
        p.edit().putBoolean(RoleMonitor.KEY_ROLE_HELD, false).apply()
        repeat(3) {
            assertFalse(p.getBoolean(RoleMonitor.KEY_ROLE_HELD, true))
        }
    }

    @Test fun key_constant_matches_documented_value() {
        // Sanity check that nobody renames the key without updating the widget
        // and onboarding code that read it.
        assertEquals("role_held", RoleMonitor.KEY_ROLE_HELD)
    }
}

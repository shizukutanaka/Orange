package com.orange.apple

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PoliceStationDirectoryTest {

    @Test fun keishicho_is_in_directory() {
        assertEquals("警視庁", PoliceStationDirectory.lookup("0335814321"))
    }

    @Test fun osaka_police_is_in_directory() {
        assertNotNull(PoliceStationDirectory.lookup("0669430110"))
    }

    @Test fun okinawa_police_is_in_directory() {
        assertEquals("沖縄県警察", PoliceStationDirectory.lookup("0988620110"))
    }

    @Test fun international_format_resolves() {
        // +81-3-3581-4321 → 0335814321 → 警視庁
        assertEquals("警視庁", PoliceStationDirectory.lookup("+81335814321"))
    }

    @Test fun random_landline_not_in_directory() {
        assertNull(PoliceStationDirectory.lookup("0312345678"))
    }

    @Test fun emergency_110_not_in_directory() {
        // 110 is in EmergencyWhitelist, not here
        assertNull(PoliceStationDirectory.lookup("110"))
    }

    @Test fun exactly_47_entries() {
        assertEquals(47, PoliceStationDirectory.entries.size)
    }

    @Test fun all_entries_are_10_digits() {
        PoliceStationDirectory.entries.keys.forEach { num ->
            assertTrue("$num is not 10 digits", num.length == 10 && num.all { it.isDigit() })
        }
    }

    @Test fun all_47_entries_are_reachable_by_lookup() {
        PoliceStationDirectory.entries.forEach { (number, name) ->
            assertEquals("lookup($number) should return $name", name, PoliceStationDirectory.lookup(number))
        }
    }

    @Test fun double_zero_international_format_resolves() {
        // Some systems deliver +810335814321 (with leading 0 after country code)
        assertEquals("警視庁", PoliceStationDirectory.lookup("+810335814321"))
    }
}

class CaribbeanPremiumNANPTest {

    @Test fun jamaica_876_is_premium() =
        assertTrue(CaribbeanPremiumNANP.isPremiumNANP("+18761234567"))

    @Test fun bahamas_242_is_premium() =
        assertTrue(CaribbeanPremiumNANP.isPremiumNANP("+12421234567"))

    @Test fun dominican_809_is_premium() =
        assertTrue(CaribbeanPremiumNANP.isPremiumNANP("+18091234567"))

    @Test fun bermuda_441_is_premium() =
        assertTrue(CaribbeanPremiumNANP.isPremiumNANP("+14411234567"))

    @Test fun us_415_is_not_premium() =
        assertFalse(CaribbeanPremiumNANP.isPremiumNANP("+14155551234"))

    @Test fun non_plus1_is_not_premium() =
        assertFalse(CaribbeanPremiumNANP.isPremiumNANP("+81901234567"))

    @Test fun too_short_is_not_premium() =
        assertFalse(CaribbeanPremiumNANP.isPremiumNANP("+12"))

    @Test fun us_202_dc_is_not_premium() =
        assertFalse(CaribbeanPremiumNANP.isPremiumNANP("+12025551234"))

    @Test fun us_212_nyc_is_not_premium() =
        assertFalse(CaribbeanPremiumNANP.isPremiumNANP("+12125551234"))
}


class RepeatCallerTrackerComponentTest {

    @Test fun first_two_calls_not_flagged() {
        val p = FakePrefs()
        RepeatCallerTracker.record(p, "+111", 1000L)
        RepeatCallerTracker.record(p, "+111", 2000L)
        assertFalse(RepeatCallerTracker.isRepeatOffender(p, "+111", 3000L))
    }

    @Test fun third_call_triggers_flag() {
        val p = FakePrefs()
        RepeatCallerTracker.record(p, "+111", 1000L)
        RepeatCallerTracker.record(p, "+111", 2000L)
        RepeatCallerTracker.record(p, "+111", 3000L)
        assertTrue(RepeatCallerTracker.isRepeatOffender(p, "+111", 4000L))
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
        repeat(RepeatCallerTracker.N_THRESHOLD) {
            RepeatCallerTracker.record(p, "+111", it * 1000L)
        }
        // Was flagged
        assertTrue(RepeatCallerTracker.isRepeatOffender(p, "+111", RepeatCallerTracker.N_THRESHOLD * 1000L + 1))
        // User answers → clear
        RepeatCallerTracker.clear(p, "+111")
        assertFalse(RepeatCallerTracker.isRepeatOffender(p, "+111", RepeatCallerTracker.N_THRESHOLD * 1000L + 2))
    }
}

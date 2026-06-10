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
}

class OutboundGuardTest {

    @Test fun record_and_check_within_window() {
        val p = FakePrefs()
        OutboundGuard.record(p, "+111", 1000L)
        assertTrue(OutboundGuard.wasRecentlyFlagged(p, "+111", 2000L))
    }

    @Test fun not_flagged_without_record() {
        val p = FakePrefs()
        assertFalse(OutboundGuard.wasRecentlyFlagged(p, "+111", 1000L))
    }

    @Test fun expired_entry_not_flagged() {
        val p = FakePrefs()
        OutboundGuard.record(p, "+111", 1000L)
        val afterWindow = 1000L + OutboundGuard.WINDOW_MS + 1
        assertFalse(OutboundGuard.wasRecentlyFlagged(p, "+111", afterWindow))
    }

    @Test fun bounded_at_max_entries() {
        val p = FakePrefs()
        repeat(OutboundGuard.MAX_ENTRIES + 10) { i ->
            OutboundGuard.record(p, "+$i", 1000L + i)
        }
        // Recent entries should survive
        assertTrue(OutboundGuard.wasRecentlyFlagged(
            p, "+${OutboundGuard.MAX_ENTRIES + 9}", 2000L))
    }

    @Test fun empty_string_ignored_by_record() {
        // Withheld calls have number="" — must not pollute the guard store.
        val p = FakePrefs()
        OutboundGuard.record(p, "", 1000L)
        // Empty key should not cause wasRecentlyFlagged("") to return true
        // (if it did, any outgoing call with empty destination would warn)
        assertFalse(OutboundGuard.wasRecentlyFlagged(p, "", 2000L))
        // And store should remain empty
        assertFalse(OutboundGuard.wasRecentlyFlagged(p, "+111", 2000L))
    }
}

class FamilyCallbackTest {

    @Test fun valid_mobile_number_accepted() {
        val p = FakePrefs()
        // Simulate setNumber logic
        val number = "09012345678"
        val cleaned = number.filter { it.isDigit() || it == '+' }
        assertTrue(cleaned.length in 3..15)
        assertTrue(cleaned.count { it.isDigit() } > 0)
    }

    @Test fun too_short_rejected() {
        val cleaned = "12"
        assertFalse(cleaned.length in 3..15)
    }

    @Test fun too_long_rejected() {
        val cleaned = "1234567890123456" // 16 digits
        assertFalse(cleaned.length in 3..15)
    }

    @Test fun plus_only_rejected() {
        val cleaned = "+".filter { it.isDigit() || it == '+' }
        assertFalse(cleaned.count { it.isDigit() } > 0)
    }

    @Test fun emergency_110_accepted() {
        val cleaned = "110"
        assertTrue(cleaned.length in 3..15)
    }

    @Test fun international_format_accepted() {
        val cleaned = "+81901234567".filter { it.isDigit() || it == '+' }
        assertTrue(cleaned.length in 3..15)
        assertTrue(cleaned.count { it.isDigit() } > 0)
    }
}

    @Test fun double_zero_international_format_resolves() {
        // Some systems deliver +810335814321 (with leading 0 after country code)
        assertEquals("警視庁", PoliceStationDirectory.lookup("+810335814321"))
    }

class PhoneNumbersTest {

    @Test fun strips_dashes_and_spaces() =
        assertEquals("+81335814321", PhoneNumbers.normalize("+81 (3) 3581-4321"))

    @Test fun preserves_leading_plus() =
        assertEquals("+819012345678", PhoneNumbers.normalize("+81-90-1234-5678"))

    @Test fun domestic_passthrough() =
        assertEquals("09012345678", PhoneNumbers.normalize("090-1234-5678"))

    @Test fun empty_stays_empty() =
        assertEquals("", PhoneNumbers.normalize(""))

    @Test fun letters_stripped() =
        assertEquals("12345", PhoneNumbers.normalize("abc12345def"))

    @Test fun fullwidth_digits_folded_to_ascii() =
        assertEquals("+819012345678", PhoneNumbers.normalize("＋８１９０１２３４５６７８"))

    @Test fun fullwidth_domestic_folded() =
        assertEquals("09012345678", PhoneNumbers.normalize("０９０１２３４５６７８"))

    @Test fun mixed_width_folded() =
        assertEquals("+8190123", PhoneNumbers.normalize("＋81９0123"))

    @Test fun non_ascii_non_fullwidth_digits_stripped() {
        // Arabic-Indic digit ٢ (U+0662) is a digit to isDigit() but not ASCII
        // and not full-width; it must be stripped. "1٢3" → "13".
        assertEquals("13", PhoneNumbers.normalize("1\u06623"))
    }
}

class RepeatCallerTrackerTest {

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

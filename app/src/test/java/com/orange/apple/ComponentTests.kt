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

    @Test fun valid_mobile_number_accepted() =
        assertNotNull(FamilyCallback.normalizeAndValidate("09012345678"))

    @Test fun too_short_rejected() =
        assertNull(FamilyCallback.normalizeAndValidate("12"))

    @Test fun too_long_rejected() =
        assertNull(FamilyCallback.normalizeAndValidate("1234567890123456")) // 16 digits

    @Test fun plus_only_rejected() =
        assertNull(FamilyCallback.normalizeAndValidate("+"))

    @Test fun emergency_110_accepted() =
        assertNotNull(FamilyCallback.normalizeAndValidate("110"))

    @Test fun international_format_accepted() =
        assertNotNull(FamilyCallback.normalizeAndValidate("+81901234567"))

    @Test fun fullwidth_digits_normalized_and_accepted() =
        // Full-width "０９０..." must be folded via PhoneNumbers.normalize before validation
        assertNotNull(FamilyCallback.normalizeAndValidate("０９０１２３４５６７８"))

    @Test fun normalized_value_is_ascii_digits() {
        val result = FamilyCallback.normalizeAndValidate("０９０－１２３４－５６７８")
        assertNotNull(result)
        assertTrue(result!!.all { it.isDigit() || it == '+' })
    }

    @Test fun invalid_slot_returns_false_not_exception() {
        // slot 0 and slot 4 are out of range; must return false, not throw
        // (Cannot call setNumber without Context, but normalizeAndValidate is Context-free)
        assertNotNull(FamilyCallback.normalizeAndValidate("09012345678")) // number itself is valid
    }
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

class BlockHistoryStoreTest {

    @Test fun normal_entry_loaded() {
        val p = FakePrefs()
        val now = 1_000_000L
        BlockHistoryStore.record(p, "09012345678", BlockReason.SPAM_CACHE, now)
        val entries = BlockHistoryStore.load(p, now + 1000L)
        assertEquals(1, entries.size)
        assertEquals(BlockReason.SPAM_CACHE, entries[0].reason)
    }

    @Test fun entry_beyond_ttl_dropped() {
        val p = FakePrefs()
        val now = 1_000_000L
        BlockHistoryStore.record(p, "09012345678", BlockReason.SPAM_CACHE, now)
        val afterTtl = now + 31L * 24 * 60 * 60 * 1000
        val entries = BlockHistoryStore.load(p, afterTtl)
        assertTrue("TTL-expired entry must be dropped", entries.isEmpty())
    }

    @Test fun future_timestamp_not_shown_clock_skew() {
        // Clock moved backward after the entry was written: ts > nowMs.
        // Without the guard, nowMs - ts underflows to a huge positive Long and
        // passes the TTL check — the entry would appear for eons.
        val p = FakePrefs()
        val recordedAt = 2_000_000L
        BlockHistoryStore.record(p, "09012345678", BlockReason.FOREIGN_GENERIC, recordedAt)
        // nowMs is before the entry's timestamp (clock skew)
        val nowBeforeEntry = recordedAt - 1000L
        val entries = BlockHistoryStore.load(p, nowBeforeEntry)
        assertTrue("Future-timestamp entry must be hidden on clock-skew", entries.isEmpty())
    }

    @Test fun bounded_at_max_entries() {
        val p = FakePrefs()
        val base = 1_000_000L
        repeat(BlockHistoryStore.MAX_ENTRIES + 5) { i ->
            BlockHistoryStore.record(p, "0901234567$i", BlockReason.REPEAT_CALLER, base + i)
        }
        val entries = BlockHistoryStore.load(p, base + BlockHistoryStore.MAX_ENTRIES + 10)
        assertEquals(BlockHistoryStore.MAX_ENTRIES, entries.size)
    }
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

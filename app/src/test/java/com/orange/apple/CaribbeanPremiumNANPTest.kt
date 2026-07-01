package com.orange.apple

import org.junit.Assert.*
import org.junit.Test

class CaribbeanPremiumNANPTest {

    @Test fun bahamas_242_recognized() {
        assertTrue(CaribbeanPremiumNANP.isPremiumNANP("+12425551234"))
    }

    @Test fun jamaica_876_recognized() {
        assertTrue(CaribbeanPremiumNANP.isPremiumNANP("+18765551234"))
    }

    @Test fun non_premium_area_code_rejected() {
        // 202 is Washington DC, not in the premium set
        assertFalse(CaribbeanPremiumNANP.isPremiumNANP("+12025551234"))
    }

    @Test fun non_plus_one_rejected() {
        // +81 (Japan) should not match
        assertFalse(CaribbeanPremiumNANP.isPremiumNANP("+8190123456789"))
    }

    @Test fun exactly_ten_digits_accepted() {
        // +1 + 3-digit area code + 7-digit local = 10 digits after +1
        val valid = "+1" + "242" + "5551234"
        assertEquals(10, valid.removePrefix("+1").length)
        assertTrue(CaribbeanPremiumNANP.isPremiumNANP(valid))
    }

    @Test fun fewer_than_ten_digits_rejected() {
        // Only 9 digits (incomplete local number)
        assertFalse(CaribbeanPremiumNANP.isPremiumNANP("+124255512"))
    }

    @Test fun more_than_ten_digits_rejected() {
        // 11 digits (extra digit after valid NANP number) — malformed
        assertFalse(CaribbeanPremiumNANP.isPremiumNANP("+124255512345"))
    }

    @Test fun short_number_without_area_code_rejected() {
        // "+1242" (only area code, no local)
        assertFalse(CaribbeanPremiumNANP.isPremiumNANP("+1242"))
    }

    @Test fun all_premium_codes_valid() {
        // Spot-check a few premium area codes
        assertTrue(CaribbeanPremiumNANP.isPremiumNANP("+12465551234"))  // Barbados
        assertTrue(CaribbeanPremiumNANP.isPremiumNANP("+13405551234"))  // US Virgin Islands
        assertTrue(CaribbeanPremiumNANP.isPremiumNANP("+14415551234"))  // Bermuda
        assertTrue(CaribbeanPremiumNANP.isPremiumNANP("+18095551234"))  // Dominican Republic
    }

    @Test fun puerto_rico_overlay_939_recognized() {
        // 939 is the overlay area code sharing Puerto Rico's geography with 787.
        assertTrue(CaribbeanPremiumNANP.isPremiumNANP("+19395551234"))
    }
}

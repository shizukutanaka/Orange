package com.orange.apple

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DomesticSpoofDetectorTest {

    // --- Structural violations (should be flagged as spoofed) -------------

    // --- 020x reserved vs 02[1-9]x geographic area codes -------------------------

    @Test fun starts_with_020_is_spoof() =
        // 020 = MIC reserved / net services, not allocated to subscriber lines
        assertTrue(DomesticSpoofDetector.isImpossibleJpNumber("02012345678"))

    @Test fun sendai_022_is_valid_geographic() =
        // 022 = 仙台市外局番. Previous code WRONGLY flagged this as spoof.
        assertFalse(DomesticSpoofDetector.isImpossibleJpNumber("0222211611"))

    @Test fun yamagata_023_is_valid_geographic() =
        assertFalse(DomesticSpoofDetector.isImpossibleJpNumber("0236265211"))

    @Test fun mito_029_is_valid_geographic() =
        assertFalse(DomesticSpoofDetector.isImpossibleJpNumber("0293011621"))

    @Test fun mobile_090_wrong_length_is_spoof() =
        assertTrue(DomesticSpoofDetector.isImpossibleJpNumber("0901234567"))  // 10 digits

    @Test fun mobile_080_too_long_is_spoof() =
        assertTrue(DomesticSpoofDetector.isImpossibleJpNumber("080123456789"))  // 12 digits

    @Test fun toll_free_0120_wrong_length_is_spoof() =
        assertTrue(DomesticSpoofDetector.isImpossibleJpNumber("012012345"))  // 9 digits

    @Test fun ip_050_too_short_is_spoof() =
        assertTrue(DomesticSpoofDetector.isImpossibleJpNumber("0501234567"))

    @Test fun premium_0990_wrong_length_eleven_is_spoof() =
        // 0990 is a teledome premium-rate service requiring exactly 10 digits.
        // An 11-digit "0990" number is structurally impossible per MIC plan.
        assertTrue(DomesticSpoofDetector.isImpossibleJpNumber("09901234567"))

    @Test fun eight_consecutive_identical_digits_is_spoof() =
        assertTrue(DomesticSpoofDetector.isImpossibleJpNumber("09011111111"))

    @Test fun missing_leading_zero_is_spoof() =
        assertTrue(DomesticSpoofDetector.isImpossibleJpNumber("9012345678"))

    @Test fun too_short_is_spoof() =
        assertTrue(DomesticSpoofDetector.isImpossibleJpNumber("035555"))

    @Test fun international_plus81_with_0_2_is_spoof() =
        assertTrue(DomesticSpoofDetector.isImpossibleJpNumber("+812012345678"))

    // --- Geographic landline length regression (bug: 11-digit geo passed) -----
    // JP geographic numbers (03, 06, 022, etc.) are always exactly 10 digits
    // per MIC numbering plan. An 11-digit "03XXXXXXXXXX" cannot exist.

    @Test fun tokyo_landline_11_digits_is_spoof() =
        // "03" + 9 digits = 11 total. Impossible in the JP plan.
        assertTrue(DomesticSpoofDetector.isImpossibleJpNumber("03555512345"))

    @Test fun osaka_landline_11_digits_is_spoof() =
        assertTrue(DomesticSpoofDetector.isImpossibleJpNumber("06123456789"))

    @Test fun sendai_landline_11_digits_is_spoof() =
        assertTrue(DomesticSpoofDetector.isImpossibleJpNumber("02222116119"))

    // --- Legitimate numbers (must not be flagged) -------------------------

    @Test fun valid_mobile_090_passes() =
        assertFalse(DomesticSpoofDetector.isImpossibleJpNumber("09012345678"))

    @Test fun valid_mobile_080_passes() =
        assertFalse(DomesticSpoofDetector.isImpossibleJpNumber("08012345678"))

    @Test fun valid_mobile_070_passes() =
        assertFalse(DomesticSpoofDetector.isImpossibleJpNumber("07012345678"))

    @Test fun valid_landline_tokyo_passes() =
        assertFalse(DomesticSpoofDetector.isImpossibleJpNumber("0355551234"))

    @Test fun valid_landline_osaka_passes() =
        assertFalse(DomesticSpoofDetector.isImpossibleJpNumber("0612345678"))

    @Test fun valid_toll_free_0120_passes() =
        assertFalse(DomesticSpoofDetector.isImpossibleJpNumber("0120123456"))

    @Test fun invalid_0800_ten_digits_is_spoof() =
        assertTrue(DomesticSpoofDetector.isImpossibleJpNumber("0800123456"))  // must be 11 digits

    @Test fun valid_ip_050_passes() =
        assertFalse(DomesticSpoofDetector.isImpossibleJpNumber("05012345678"))

    @Test fun valid_international_plus81_mobile_passes() =
        assertFalse(DomesticSpoofDetector.isImpossibleJpNumber("+819012345678"))

    // --- 060 mobile (allocated Dec 2025) ---

    @Test fun valid_060_mobile_11_digits_passes() =
        assertFalse(DomesticSpoofDetector.isImpossibleJpNumber("06012345678"))

    @Test fun invalid_060_mobile_10_digits_is_spoof() =
        assertTrue(DomesticSpoofDetector.isImpossibleJpNumber("0601234567"))

    // --- Regression guard: special-prefix digit-length rules --------------------
    // These caught a bug where 0120/0570 (10-digit) were wrongly required to be 11.

    @Test fun navi_dial_0570_valid_ten_digit_passes() =
        assertFalse(DomesticSpoofDetector.isImpossibleJpNumber("0570123456"))

    @Test fun navi_dial_0570_wrong_eleven_digit_is_spoof() =
        assertTrue(DomesticSpoofDetector.isImpossibleJpNumber("05701234567"))

    @Test fun teledome_0990_valid_ten_digit_passes() =
        assertFalse(DomesticSpoofDetector.isImpossibleJpNumber("0990123456"))

    @Test fun freephone_0800_valid_eleven_digit_passes() =
        assertFalse(DomesticSpoofDetector.isImpossibleJpNumber("08001234567"))

    @Test fun freephone_0120_wrong_eleven_digit_is_spoof() =
        assertTrue(DomesticSpoofDetector.isImpossibleJpNumber("01201234567"))

    @Test fun mobile_060_valid_eleven_digit_passes() =
        assertFalse(DomesticSpoofDetector.isImpossibleJpNumber("06012345678"))

    // --- Non-JP numbers: not our concern, always return false --------------

    @Test fun us_number_is_not_flagged() =
        assertFalse(DomesticSpoofDetector.isImpossibleJpNumber("+14155551234"))

    @Test fun cn_number_is_not_flagged() =
        assertFalse(DomesticSpoofDetector.isImpossibleJpNumber("+8613812345678"))

    @Test fun short_code_110_is_not_flagged() =
        // 110 is under 10 digits, so it's "impossible" as a JP full number,
        // but EmergencyWhitelist catches it before this detector sees it.
        // Here we just verify the detector's own behavior for the record.
        assertTrue(DomesticSpoofDetector.isImpossibleJpNumber("110"))
}

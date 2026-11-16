package com.orange.apple

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NANP impossible-number rules (ADR 015): NPA/NXX shape, N11 reservation,
 * unassigned 555 area code, and NANPA's fictitious 555-0100–0199 range.
 * The detector must flag only what the plan makes provably impossible —
 * everything else (valid premium-rate, toll-free, foreign, short codes)
 * abstains.
 */
class NanpSpoofDetectorTest {

    @Test fun valid_geographic_numbers_not_flagged() {
        assertFalse(NanpSpoofDetector.isImpossibleUsNumber("+12024561414"))
        assertFalse(NanpSpoofDetector.isImpossibleUsNumber("+13127460950"))
        assertFalse(NanpSpoofDetector.isImpossibleUsNumber("+19175551234"))
    }

    @Test fun npa_starting_with_1_is_impossible() {
        assertTrue(NanpSpoofDetector.isImpossibleUsNumber("+11234567890"))
    }

    @Test fun npa_starting_with_0_is_impossible() {
        assertTrue(NanpSpoofDetector.isImpossibleUsNumber("+10234567890"))
    }

    @Test fun nxx_starting_with_1_is_impossible() {
        assertTrue(NanpSpoofDetector.isImpossibleUsNumber("+12021550142"))
    }

    @Test fun nxx_starting_with_0_is_impossible() {
        assertTrue(NanpSpoofDetector.isImpossibleUsNumber("+12020550142"))
    }

    @Test fun n11_as_npa_is_impossible() {
        // 211 can never be an area code: it is the community-services code.
        assertTrue(NanpSpoofDetector.isImpossibleUsNumber("+12114567890"))
    }

    @Test fun n11_as_nxx_is_impossible() {
        // 911 can never be a central-office code.
        assertTrue(NanpSpoofDetector.isImpossibleUsNumber("+12029116789"))
    }

    @Test fun npa_555_is_unassigned_and_impossible() {
        assertTrue(NanpSpoofDetector.isImpossibleUsNumber("+15552345678"))
    }

    @Test fun fictitious_555_range_is_impossible() {
        // NANPA-reserved non-working numbers for entertainment/advertising.
        assertTrue(NanpSpoofDetector.isImpossibleUsNumber("+12025550100"))
        assertTrue(NanpSpoofDetector.isImpossibleUsNumber("+12025550199"))
        assertTrue(NanpSpoofDetector.isImpossibleUsNumber("+12025550142"))
    }

    @Test fun assignable_555_numbers_not_flagged() {
        // Outside 0100–0199 the 555 NXX is assignable (555-1212 is directory
        // assistance); only the fictitious range is provably impossible.
        assertFalse(NanpSpoofDetector.isImpossibleUsNumber("+12025550200"))
        assertFalse(NanpSpoofDetector.isImpossibleUsNumber("+12025551212"))
    }

    @Test fun toll_free_npas_not_flagged() {
        assertFalse(NanpSpoofDetector.isImpossibleUsNumber("+18008291040"))
        assertFalse(NanpSpoofDetector.isImpossibleUsNumber("+18882255322"))
        assertFalse(NanpSpoofDetector.isImpossibleUsNumber("+18334567890"))
        assertFalse(NanpSpoofDetector.isImpossibleUsNumber("+18773824357"))
    }

    @Test fun premium_900_not_flagged() {
        assertFalse(NanpSpoofDetector.isImpossibleUsNumber("+19004567890"))
    }

    @Test fun domestic_forms_accepted() {
        assertFalse(NanpSpoofDetector.isImpossibleUsNumber("2024561414"))
        assertFalse(NanpSpoofDetector.isImpossibleUsNumber("12024561414"))
        assertTrue(NanpSpoofDetector.isImpossibleUsNumber("2025550142"))
    }

    @Test fun other_countries_abstain() {
        assertFalse(NanpSpoofDetector.isImpossibleUsNumber("+442071234567"))
        assertFalse(NanpSpoofDetector.isImpossibleUsNumber("+819012345678"))
        assertFalse(NanpSpoofDetector.isImpossibleUsNumber("+33612345678"))
    }

    @Test fun short_codes_abstain() {
        assertFalse(NanpSpoofDetector.isImpossibleUsNumber("911"))
        assertFalse(NanpSpoofDetector.isImpossibleUsNumber("988"))
        assertFalse(NanpSpoofDetector.isImpossibleUsNumber("112"))
    }

    @Test fun malformed_input_abstains() {
        assertFalse(NanpSpoofDetector.isImpossibleUsNumber(""))
        assertFalse(NanpSpoofDetector.isImpossibleUsNumber("+1"))
        assertFalse(NanpSpoofDetector.isImpossibleUsNumber("+1202456789"))
        assertFalse(NanpSpoofDetector.isImpossibleUsNumber("12345"))
    }

    @Test fun to_nanp_national_normalizes_forms() {
        assertEquals("2024561414", NanpSpoofDetector.toNanpNational("+1 (202) 456-1414"))
        assertEquals("2024561414", NanpSpoofDetector.toNanpNational("12024561414"))
        assertEquals("2024561414", NanpSpoofDetector.toNanpNational("2024561414"))
        assertNull(NanpSpoofDetector.toNanpNational("+442071234567"))
        assertNull(NanpSpoofDetector.toNanpNational("911"))
    }
}

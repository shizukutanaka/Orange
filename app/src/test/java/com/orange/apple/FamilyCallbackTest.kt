package com.orange.apple

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for FamilyCallback.normalizeAndValidate() — the internal validation
 * function is testable without a Context since it has no Android dependencies.
 */
class FamilyCallbackTest {

    private fun validate(n: String) = FamilyCallback.normalizeAndValidate(n)

    @Test fun `valid JP mobile accepted`() {
        assertNotNull(validate("090-1234-5678"))
        assertEquals("09012345678", validate("090-1234-5678"))
    }

    @Test fun `valid short code accepted`() {
        // Min 3 digits (e.g. 110/119)
        assertNotNull(validate("110"))
        assertEquals("110", validate("110"))
    }

    @Test fun `valid E164 accepted`() {
        assertNotNull(validate("+819012345678"))
        assertEquals("+819012345678", validate("+819012345678"))
    }

    @Test fun `full-width digits normalized and accepted`() {
        // ０９０ (full-width) must fold to "090"
        val result = validate("０９０１２３４５６７８")
        assertNotNull(result)
        assertEquals("09012345678", result)
    }

    @Test fun `too short rejected`() {
        // 2 digits < 3 minimum
        assertNull(validate("09"))
    }

    @Test fun `empty string rejected`() {
        assertNull(validate(""))
    }

    @Test fun `only plus sign rejected`() {
        assertNull(validate("+"))
    }

    @Test fun `too long rejected`() {
        // 16 digits > E.164 maximum of 15
        assertNull(validate("1234567890123456"))
    }

    @Test fun `exactly 15 digits accepted`() {
        assertNotNull(validate("123456789012345"))
    }

    @Test fun `spaces and hyphens stripped during normalization`() {
        assertEquals("09012345678", validate("090 1234 5678"))
        assertEquals("09012345678", validate("090-1234-5678"))
    }

    @Test fun `calling code only rejected`() {
        // "+81" has only 2 digits — not a dialable number
        assertNull(validate("+81"))
    }

    @Test fun `two digits rejected`() {
        // 2 digits is below the 3-digit minimum
        assertNull(validate("81"))
    }

    @Test fun `E164 with maximum 15 digits accepted`() {
        // '+' + 15 digits = 16 characters total. Old code checked cleaned.length <= 15
        // (counting '+'), incorrectly rejecting this valid E.164 number. New code checks
        // digit count only, so '+' + 15 digits passes.
        val maxE164 = "+" + "9".repeat(15)  // "+999999999999999"
        assertNotNull("E.164 with 15 digits must be accepted", validate(maxE164))
    }

    @Test fun `E164 with 16 digits rejected`() {
        val tooLong = "+" + "9".repeat(16)  // '+' + 16 digits exceeds E.164 max
        assertNull("E.164 with 16 digits must be rejected", validate(tooLong))
    }
}

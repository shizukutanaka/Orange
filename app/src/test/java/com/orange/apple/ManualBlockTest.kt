package com.orange.apple

import org.junit.Assert.*
import org.junit.Test

class ManualBlockTest {

    @Test fun `valid domestic mobile number is accepted`() {
        assertEquals("09012345678", ManualBlock.normalizeAndValidate("090-1234-5678"))
    }

    @Test fun `valid e164 number is accepted`() {
        assertEquals("+819012345678", ManualBlock.normalizeAndValidate("+81 90 1234 5678"))
    }

    @Test fun `full-width digits are folded to ASCII`() {
        assertEquals("09012345678", ManualBlock.normalizeAndValidate("０９０１２３４５６７８"))
    }

    @Test fun `empty input is rejected`() {
        assertNull(ManualBlock.normalizeAndValidate(""))
    }

    @Test fun `too-short input is rejected`() {
        assertNull(ManualBlock.normalizeAndValidate("12"))
    }

    @Test fun `too-long input is rejected`() {
        assertNull(ManualBlock.normalizeAndValidate("1234567890123456"))
    }

    @Test fun `emergency number 110 is rejected`() {
        assertNull(ManualBlock.normalizeAndValidate("110"))
    }

    @Test fun `emergency number 119 is rejected`() {
        assertNull(ManualBlock.normalizeAndValidate("119"))
    }

    @Test fun `emergency e164 form is rejected`() {
        assertNull(ManualBlock.normalizeAndValidate("+81110"))
    }

    @Test fun `non-digit garbage input is rejected`() {
        assertNull(ManualBlock.normalizeAndValidate("abc"))
    }

    @Test fun `short code 188 is accepted since it is not an emergency number`() {
        // 188 (consumer hotline) is not in EmergencyWhitelist, and short codes
        // are legitimate blockable targets in principle even if unlikely in practice.
        assertEquals("188", ManualBlock.normalizeAndValidate("188"))
    }
}

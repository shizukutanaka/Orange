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

    // --- classify(): trust-set precedence mirrors SilentBlockerService.screenIncoming ---

    private val noHash: (String) -> String = { it } // identity hash for tests that don't need outbound matching

    @Test fun `classify returns BLOCKED for an untrusted number`() {
        val result = ManualBlock.classify(
            variants = setOf("09011112222"),
            familyNumbers = emptySet(),
            businessNumbers = emptySet(),
            outboundHashes = emptySet(),
            hashOf = noHash,
        )
        assertEquals(ManualBlock.Result.BLOCKED, result)
    }

    @Test fun `classify returns ALREADY_TRUSTED for a family number`() {
        val result = ManualBlock.classify(
            variants = setOf("09011112222", "+819011112222"),
            familyNumbers = setOf("09011112222"),
            businessNumbers = emptySet(),
            outboundHashes = emptySet(),
            hashOf = noHash,
        )
        assertEquals(ManualBlock.Result.ALREADY_TRUSTED, result)
    }

    @Test fun `classify matches a family number via its E164 variant`() {
        // Family stored in domestic form; block attempted lookup expands to E.164 too —
        // the variant set (not just the raw input) must be checked against family.
        val result = ManualBlock.classify(
            variants = setOf("+819011112222", "09011112222"),
            familyNumbers = setOf("09011112222"),
            businessNumbers = emptySet(),
            outboundHashes = emptySet(),
            hashOf = noHash,
        )
        assertEquals(ManualBlock.Result.ALREADY_TRUSTED, result)
    }

    @Test fun `classify returns ALREADY_TRUSTED for a bundled business number`() {
        val result = ManualBlock.classify(
            variants = setOf("+81352535111"),
            familyNumbers = emptySet(),
            businessNumbers = setOf("+81352535111"),
            outboundHashes = emptySet(),
            hashOf = noHash,
        )
        assertEquals(ManualBlock.Result.ALREADY_TRUSTED, result)
    }

    @Test fun `classify returns ALREADY_TRUSTED for an outbound-known number via its hash`() {
        val fakeHash: (String) -> String = { n -> "hash:$n" }
        val result = ManualBlock.classify(
            variants = setOf("+14155551234"),
            familyNumbers = emptySet(),
            businessNumbers = emptySet(),
            outboundHashes = setOf("hash:+14155551234"),
            hashOf = fakeHash,
        )
        assertEquals(ManualBlock.Result.ALREADY_TRUSTED, result)
    }

    @Test fun `classify does not match on unrelated outbound hashes`() {
        val fakeHash: (String) -> String = { n -> "hash:$n" }
        val result = ManualBlock.classify(
            variants = setOf("+14155551234"),
            familyNumbers = emptySet(),
            businessNumbers = emptySet(),
            outboundHashes = setOf("hash:+19995551234"),
            hashOf = fakeHash,
        )
        assertEquals(ManualBlock.Result.BLOCKED, result)
    }
}

package com.orange.apple

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for phoneVariants() — the domestic↔E.164 equivalence helper that lets
 * a number dialed/stored in one form match an incoming call in the other.
 * Pure function, zero Android dependencies.
 */
class PhoneVariantsTest {

    @Test fun `domestic JP number expands to E164`() {
        val v = phoneVariants("09012345678", "81")
        assertTrue(v.contains("09012345678"))
        assertTrue(v.contains("+819012345678"))
    }

    @Test fun `E164 JP number expands to domestic`() {
        val v = phoneVariants("+819012345678", "81")
        assertTrue(v.contains("+819012345678"))
        assertTrue(v.contains("09012345678"))
    }

    @Test fun `round-trip is symmetric`() {
        val fromDomestic = phoneVariants("09012345678", "81")
        val fromE164 = phoneVariants("+819012345678", "81")
        assertEquals(fromDomestic, fromE164)
    }

    @Test fun `null calling code returns only the number itself`() {
        val v = phoneVariants("09012345678", null)
        assertEquals(setOf("09012345678"), v)
    }

    @Test fun `empty number returns empty set`() {
        assertTrue(phoneVariants("", "81").isEmpty())
    }

    @Test fun `foreign E164 with mismatched calling code is not expanded`() {
        // +852 (Hong Kong) number with JP calling code "81" — no domestic form.
        val v = phoneVariants("+85290001234", "81")
        assertEquals(setOf("+85290001234"), v)
    }

    @Test fun `domestic number without leading zero is not expanded`() {
        // Short codes / non-trunk numbers ("110") aren't domestic-trunk form.
        val v = phoneVariants("110", "81")
        assertEquals(setOf("110"), v)
    }

    @Test fun `US domestic expands with calling code 1`() {
        val v = phoneVariants("02125551234", "1")
        assertTrue(v.contains("+12125551234"))
    }

    @Test fun `outbound callback in E164 matches domestic-dialed number`() {
        // The real-world scenario: user dialed "09012345678" (stored domestic),
        // the contact calls back and Android delivers "+819012345678".
        val outbound = setOf("09012345678")
        val incoming = "+819012345678"
        val matched = phoneVariants(incoming, "81").any { it in outbound }
        assertTrue("E.164 callback must match domestic-dialed number", matched)
    }

    @Test fun `business directory E164 entry matched when carrier delivers domestic form`() {
        // Regression: BusinessDirectoryBundle stores "+81352535111" (総務省 E.164).
        // Some JP carriers deliver the same number as "0352535111" (domestic).
        // Without variant expansion, Layer 5 misses and the call may get a false
        // PostCallAdvisor advisory. screenIncoming() now checks variants.
        val directoryKeys = setOf("+81352535111")  // E.164 as stored in CSV
        val incomingDomestic = "0352535111"         // as delivered by carrier
        val matched = phoneVariants(incomingDomestic, "81").any { it in directoryKeys }
        assertTrue("domestic-form delivery must match E.164 business directory entry", matched)
    }

    @Test fun `business directory domestic shortcode matched in E164 delivery`() {
        // Shortcodes (e.g., "188" 消費者ホットライン) are stored as-is in the CSV.
        // They don't have a domestic/E.164 dual form, so phoneVariants returns only
        // the shortcode itself, and the match works by exact string comparison.
        val directoryKeys = setOf("188")
        val incoming = "188"
        val matched = phoneVariants(incoming, "81").any { it in directoryKeys }
        assertTrue("shortcode must match directly", matched)
    }
}

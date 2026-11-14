package com.orange.apple

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lookup/classification tests for the Ring port. The parse and
 * classification vectors are Ring's own golden vectors
 * (SpamJudgeTest.normalizeTests / formatTests), re-expressed against
 * NumberInspector so the port is held to the original expectations.
 */
class NumberInspectorTest {

    private fun inspect(raw: String) =
        NumberInspector.inspect(raw, emptyList(), isSpamCached = false, calleeCountryIso = "JP")

    // --- Classification (Ring vectors) -----------------------------------------

    @Test fun `050 is ip phone`() {
        val r = inspect("050-1234-5678")
        assertTrue(r.valid)
        assertEquals(NumberInspector.Kind.IP_PHONE, r.kind)
        assertEquals("05012345678", r.nationalForm)
    }

    @Test fun `090 is mobile`() =
        assertEquals(NumberInspector.Kind.MOBILE, inspect("090 1234 5678").kind)

    @Test fun `070 and 080 are mobile`() {
        assertEquals(NumberInspector.Kind.MOBILE, inspect("07012345678").kind)
        assertEquals(NumberInspector.Kind.MOBILE, inspect("08012345678").kind)
    }

    @Test fun `0800 and 0120 are toll-free`() {
        assertEquals(NumberInspector.Kind.TOLLFREE, inspect("0800-123-4567").kind)
        assertEquals(NumberInspector.Kind.TOLLFREE, inspect("0120-123-456").kind)
    }

    @Test fun `0570 is navi dial`() =
        assertEquals(NumberInspector.Kind.NAVI_DIAL, inspect("0570-123-456").kind)

    @Test fun `03 is fixed line`() {
        val r = inspect("03-1234-5678")
        assertEquals(NumberInspector.Kind.FIXEDLINE, r.kind)
        assertEquals("0312345678", r.nationalForm)
    }

    @Test fun `full-width digits fold and validate`() {
        val r = inspect("０９０１２３４５６７８")
        assertTrue(r.valid)
        assertEquals("09012345678", r.nationalForm)
    }

    @Test fun `parenthesised number validates`() =
        assertTrue(inspect("(03) 1234-5678").valid)

    // --- International spellings of JP numbers (Ring vectors) ------------------

    @Test fun `plus 81 form resolves to national`() {
        val r = inspect("+81 90-1234-5678")
        assertTrue(r.valid)
        assertEquals("09012345678", r.nationalForm)
        assertEquals(NumberInspector.Kind.MOBILE, r.kind)
    }

    @Test fun `0081 access prefix resolves`() =
        assertEquals("05012345678", inspect("0081-50-1234-5678").nationalForm)

    @Test fun `01081 access prefix resolves`() =
        assertEquals("09012345678", inspect("01081-90-1234-5678").nationalForm)

    @Test fun `us number is international`() {
        val r = inspect("+1 202-555-0123")
        assertTrue(r.valid)
        assertEquals(NumberInspector.Kind.INTERNATIONAL, r.kind)
        assertNull(r.nationalForm)
    }

    // --- Invalid inputs (Ring vectors) ------------------------------------------

    @Test fun `invalid inputs are flagged`() {
        for (bad in listOf("", "12345", "abcd", "090-123", "phone-number", "   ")) {
            val r = inspect(bad)
            assertFalse("expected invalid for '$bad'", r.valid)
            assertEquals(NumberInspector.Kind.INVALID, r.kind)
        }
    }

    @Test fun `no leading zero domestic is invalid`() =
        assertFalse(inspect("1234567890").valid)

    // --- Formatting (Ring vectors) ----------------------------------------------

    @Test fun `format 11 digit mobile`() =
        assertEquals("050-1234-5678", NumberInspector.formatNational("05012345678"))

    @Test fun `format 0120`() =
        assertEquals("0120-123-456", NumberInspector.formatNational("0120123456"))

    @Test fun `format 03`() =
        assertEquals("03-1234-5678", NumberInspector.formatNational("0312345678"))

    @Test fun `format null is empty`() =
        assertEquals("", NumberInspector.formatNational(null))

    @Test fun `inspection carries the formatted national form`() =
        assertEquals("090-1234-5678", inspect("09012345678").formatted)

    // --- Protection lookups -------------------------------------------------------

    @Test fun `prefix rule match is reported`() {
        val rules = listOf(PrefixRule("0800", PrefixRule.SCOPE_NATIONAL))
        val r = NumberInspector.inspect("08001234567", rules, isSpamCached = false, calleeCountryIso = "JP")
        assertEquals(PrefixRule("0800", PrefixRule.SCOPE_NATIONAL), r.matchedPrefixRule)
    }

    @Test fun `spam cache flag passes through`() {
        val r = NumberInspector.inspect("09012345678", emptyList(), isSpamCached = true, calleeCountryIso = "JP")
        assertTrue(r.spamCached)
    }

    @Test fun `impossible jp number is flagged`() {
        // 090 numbers are 11 digits in the MIC plan; a 10-digit 090 cannot exist.
        val r = inspect("0901234567")
        assertTrue(r.valid)
        assertTrue(r.impossibleJp)
    }

    @Test fun `ordinary mobile is not flagged impossible`() {
        val r = inspect("09012345678")
        assertTrue(r.valid)
        assertFalse(r.impossibleJp)
    }
}

package com.orange.apple

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the prefix-rule model ported from Ring's spam blocker
 * (app.ring.spamblocker.core). The canonicalization vectors are Ring's
 * own (SpamJudgeTest.prefixRuleTests), carried over so the ported code
 * is held to the same expectations as the original.
 */
class PrefixRulesTest {

    // --- Canonicalization (Ring vectors) --------------------------------------

    @Test fun `rule 0800 national`() =
        assertEquals(PrefixRule("0800", "national"), canonicalPrefixRule("0800"))

    @Test fun `rule 050-1234 strips separators`() =
        assertEquals(PrefixRule("0501234", "national"), canonicalPrefixRule("050-1234"))

    @Test fun `rule +8190 e164`() =
        assertEquals(PrefixRule("8190", "e164"), canonicalPrefixRule("+8190"))

    @Test fun `rule full-width input folds`() =
        assertEquals(PrefixRule("0800", "national"), canonicalPrefixRule("０８００"))

    @Test fun `rule empty rejected`() =
        assertNull(canonicalPrefixRule(""))

    @Test fun `rule letters rejected`() =
        assertNull(canonicalPrefixRule("ab"))

    @Test fun `rule too short rejected`() =
        assertNull(canonicalPrefixRule("90"))

    @Test fun `rule national must start with 0`() =
        assertNull(canonicalPrefixRule("1234"))

    @Test fun `rule e164 must not start with 0`() =
        assertNull(canonicalPrefixRule("+090"))

    @Test fun `rule null rejected`() =
        assertNull(canonicalPrefixRule(null))

    // --- Matching --------------------------------------------------------------

    private val tollfreeRule = PrefixRule("0800", PrefixRule.SCOPE_NATIONAL)
    private val jpMobileRule = PrefixRule("8190", PrefixRule.SCOPE_E164)

    @Test fun `national rule matches domestic form`() =
        assertEquals(tollfreeRule, matchPrefixRule("08001234567", "JP", listOf(tollfreeRule)))

    @Test fun `national rule matches e164 delivery of same number`() =
        assertEquals(tollfreeRule, matchPrefixRule("+818001234567", "JP", listOf(tollfreeRule)))

    @Test fun `e164 rule matches domestic delivery`() =
        assertEquals(jpMobileRule, matchPrefixRule("09012345678", "JP", listOf(jpMobileRule)))

    @Test fun `e164 rule matches e164 delivery`() =
        assertEquals(jpMobileRule, matchPrefixRule("+819012345678", "JP", listOf(jpMobileRule)))

    @Test fun `e164 rule does not match a different carrier block`() =
        assertNull(matchPrefixRule("08012345678", "JP", listOf(jpMobileRule)))

    @Test fun `national rule does not match unrelated number`() =
        assertNull(matchPrefixRule("09012345678", "JP", listOf(tollfreeRule)))

    @Test fun `international caller matches e164 rule`() {
        val rule = PrefixRule("4420", PrefixRule.SCOPE_E164)
        assertEquals(rule, matchPrefixRule("+442071234567", "JP", listOf(rule)))
    }

    @Test fun `empty number never matches`() =
        assertNull(matchPrefixRule("", "JP", listOf(tollfreeRule)))

    @Test fun `no rules no match`() =
        assertNull(matchPrefixRule("08001234567", "JP", emptyList()))

    @Test fun `first matching rule wins`() {
        val broad = PrefixRule("080", PrefixRule.SCOPE_NATIONAL)
        assertEquals(broad, matchPrefixRule("08001234567", "JP", listOf(broad, tollfreeRule)))
    }
}

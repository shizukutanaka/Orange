package com.orange.apple

import org.junit.Assert.*
import org.junit.Test

class TaxAgencyDirectoryTest {

    @Test fun nta_domestic_matches() {
        assertEquals("国税庁", TaxAgencyDirectory.lookup("0352533111"))
    }

    @Test fun nta_e164_matches() {
        assertEquals("国税庁", TaxAgencyDirectory.lookup("+81352533111"))
    }

    @Test fun regular_mobile_not_matched() {
        assertNull(TaxAgencyDirectory.lookup("09012345678"))
    }

    @Test fun empty_not_matched() {
        assertNull(TaxAgencyDirectory.lookup(""))
    }

    @Test fun international_non_jp_not_matched() {
        assertNull(TaxAgencyDirectory.lookup("+14155551234"))
    }

    @Test fun all_keys_are_10_digit_domestic() {
        for ((k, _) in TaxAgencyDirectory.entries) {
            assertTrue("Key '$k' should start with 0", k.startsWith("0"))
            assertEquals("Key '$k' should be 10 digits", 10, k.length)
            assertTrue("Key '$k' should be all digits", k.all { it.isDigit() })
        }
    }

    @Test fun all_values_non_blank() {
        for ((_, v) in TaxAgencyDirectory.entries) {
            assertTrue("Display name should not be blank", v.isNotBlank())
        }
    }

    // --- Integration: Layer 9b fires for known tax-agency numbers ---

    @Test fun decide_rings_with_tax_warning_for_nta() {
        val ctx = CallContext(
            number = "0352533111",
            calleeCountryIso = "JP",
            nowMillis = 1_000_000L
        )
        val state = CallState(
            outboundKnown = emptySet(),
            isSpamCached = false,
            knownBusinesses = emptySet(),
            pausedUntilMillis = 0L,
            wangiriRingAt = null
        )
        val d = decide(ctx, state)
        assertEquals(Verdict.RING, d.verdict)
        assertEquals(WarnReason.TAX_AGENCY_IMPERSONATION, d.warning)
        assertEquals("国税庁", d.warnPayload)
    }

    @Test fun decide_escalates_to_high_when_stir_shaken_also_fails() {
        val ctx = CallContext(
            number = "0352533111",
            calleeCountryIso = "JP",
            nowMillis = 1_000_000L,
            verificationFailed = true
        )
        val state = CallState(
            outboundKnown = emptySet(),
            isSpamCached = false,
            knownBusinesses = emptySet(),
            pausedUntilMillis = 0L,
            wangiriRingAt = null
        )
        val d = decide(ctx, state)
        assertEquals(Verdict.RING, d.verdict)
        assertEquals(WarnReason.TAX_AGENCY_IMPERSONATION_HIGH, d.warning)
    }

    @Test fun decide_no_tax_warning_outside_jp() {
        val ctx = CallContext(
            number = "0352533111",
            calleeCountryIso = "US",
            nowMillis = 1_000_000L
        )
        val state = CallState(
            outboundKnown = emptySet(),
            isSpamCached = false,
            knownBusinesses = emptySet(),
            pausedUntilMillis = 0L,
            wangiriRingAt = null
        )
        val d = decide(ctx, state)
        assertNull(d.warning)
    }

    @Test fun decide_tax_warning_survives_pause() {
        // Same rationale as the police-warning pause test: pause exists for
        // call-volume fatigue, not to silence active-fraud impersonation warnings.
        val ctx = CallContext(
            number = "0352533111",
            calleeCountryIso = "JP",
            nowMillis = 1_000_000L
        )
        val state = CallState(
            outboundKnown = emptySet(),
            isSpamCached = false,
            knownBusinesses = emptySet(),
            pausedUntilMillis = Long.MAX_VALUE,
            wangiriRingAt = null
        )
        val d = decide(ctx, state)
        assertEquals(Verdict.RING, d.verdict)
        assertEquals(WarnReason.TAX_AGENCY_IMPERSONATION, d.warning)
        assertEquals("国税庁", d.warnPayload)
    }

    @Test fun decide_outbound_known_bypasses_tax_warning() {
        // A tax-agency number the user has previously dialed rings with no warning —
        // outbound-known (Layer 4) fires before the tax-agency check (Layer 9b).
        val ctx = CallContext(
            number = "0352533111",
            calleeCountryIso = "JP",
            nowMillis = 1_000_000L
        )
        val state = CallState(
            outboundKnown = setOf("0352533111"),
            isSpamCached = false,
            knownBusinesses = emptySet(),
            pausedUntilMillis = 0L,
            wangiriRingAt = null
        )
        val d = decide(ctx, state)
        assertEquals(Verdict.RING, d.verdict)
        assertNull(d.warning)
    }
}

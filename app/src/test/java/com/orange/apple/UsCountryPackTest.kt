package com.orange.apple

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The US country pack (ADR 015): registration, the pack's own verified
 * data, NANP-correct directory lookups, and — the point of the exercise —
 * proof that the ADR 014 pack mechanism serves a second country through
 * decide() with no engine changes, while JP callees see none of it.
 */
class UsCountryPackTest {

    private val emptyState = CallState(
        outboundKnown = emptySet(),
        isSpamCached = false,
        knownBusinesses = emptySet(),
        pausedUntilMillis = 0L,
        wangiriRingAt = null
    )

    private fun ctx(number: String, iso: String?) = CallContext(
        number = number,
        calleeCountryIso = iso,
        nowMillis = 1_000_000L
    )

    @Test fun us_pack_is_registered_second() {
        assertEquals(UsCountryPack, CountryPacks.forIso("US"))
        assertEquals("JP", CountryPacks.all.first().iso)
        assertEquals("US", CountryPacks.all[1].iso)
    }

    @Test fun us_pack_identity_fields() {
        assertEquals("US", UsCountryPack.iso)
        assertEquals("1", UsCountryPack.callingCode)
    }

    @Test fun us_police_lookup_accepts_domestic_and_e164() {
        assertEquals("FBI Headquarters", UsCountryPack.lookupPolice("2023243000"))
        assertEquals("FBI Headquarters", UsCountryPack.lookupPolice("+12023243000"))
        assertEquals("FBI Headquarters", UsCountryPack.lookupPolice("12023243000"))
        // JP police numbers are not US data and must not resolve here.
        assertNull(UsCountryPack.lookupPolice("0335814321"))
        assertNull(UsCountryPack.lookupPolice("+81335814321"))
    }

    @Test fun us_tax_lookup_accepts_domestic_and_e164() {
        assertEquals("Internal Revenue Service (IRS)", UsCountryPack.lookupTax("8008291040"))
        assertEquals("Internal Revenue Service (IRS)", UsCountryPack.lookupTax("+18008291040"))
        assertNull(UsCountryPack.lookupTax("0352533111"))
        assertNull(UsCountryPack.lookupTax("+81352533111"))
    }

    @Test fun us_emergency_numbers_are_whitelisted() {
        for (n in UsCountryPack.emergencyNumbers) {
            assertTrue("$n (US pack) must be an emergency number", EmergencyWhitelist.isEmergency(n))
        }
        assertTrue(EmergencyWhitelist.isEmergency("988"))
        assertTrue(EmergencyWhitelist.isEmergency("+18002738255"))
    }

    @Test fun decide_fbi_number_warns_us_callee() {
        val d = decide(ctx("+12023243000", "US"), emptyState)
        assertEquals(Verdict.RING, d.verdict)
        assertEquals(WarnReason.POLICE_IMPERSONATION, d.warning)
        assertEquals("FBI Headquarters", d.warnPayload)
    }

    @Test fun decide_fbi_number_does_not_warn_jp_callee() {
        // The warn directories are the CALLEE's pack's: a JP user receiving
        // the FBI's number gets no US police warning.
        val d = decide(ctx("+12023243000", "JP"), emptyState)
        assertNull(d.warning)
    }

    @Test fun decide_irs_number_warns_us_callee() {
        val d = decide(ctx("+18008291040", "US"), emptyState)
        assertEquals(Verdict.RING, d.verdict)
        assertEquals(WarnReason.TAX_AGENCY_IMPERSONATION, d.warning)
        assertEquals("Internal Revenue Service (IRS)", d.warnPayload)
    }

    @Test fun decide_india_caller_is_elevated_for_us_callee() {
        // +91 is the US pack's documented impersonation corridor (DOJ).
        val d = decide(ctx("+919876543210", "US"), emptyState)
        assertEquals(Verdict.SILENCE, d.verdict)
        assertEquals(BlockReason.FOREIGN_ELEVATED, d.reason)
    }

    @Test fun decide_jp_only_corridor_is_not_elevated_for_us_callee() {
        // +675 is elevated for JP users only; risk is relative to the
        // callee's country, so a US callee gets the generic foreign rule.
        val d = decide(ctx("+67512345678", "US"), emptyState)
        assertNotEquals(BlockReason.FOREIGN_ELEVATED, d.reason)
        assertEquals(BlockReason.FOREIGN_GENERIC, d.reason)
    }

    @Test fun decide_plus1_caller_is_never_elevated_for_us_callee() {
        // +1 is the home plan: a domestic US call must not be silenced by
        // the elevated-risk layer of its own pack.
        val d = decide(ctx("+13127460950", "US"), emptyState)
        assertNotEquals(BlockReason.FOREIGN_ELEVATED, d.reason)
    }

    @Test fun us_pack_spoof_check_follows_the_nanp() {
        assertTrue(UsCountryPack.isImpossibleDomesticNumber("+12025550142"))
        assertTrue(UsCountryPack.isImpossibleDomesticNumber("+12114567890"))
        assertFalse(UsCountryPack.isImpossibleDomesticNumber("+12024561414"))
        // Other countries' numbers are out of scope, never flagged.
        assertFalse(UsCountryPack.isImpossibleDomesticNumber("+819012345678"))
        assertFalse(UsCountryPack.isImpossibleDomesticNumber("09012345678"))
    }

    @Test fun decide_impossible_nanp_number_is_flagged_for_us_callee() {
        // Layer 8 through the pack: a fictitious 555-01XX caller ID.
        val d = decide(ctx("+12025550142", "US"), emptyState)
        assertEquals(BlockReason.DOMESTIC_SPOOF, d.reason)
    }
}

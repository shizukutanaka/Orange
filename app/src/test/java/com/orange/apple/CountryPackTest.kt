package com.orange.apple

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader

/**
 * Country-pack structure (ADR 014): the registry, the JP pack's shape, and
 * the guarantee that the pre-pack objects (PoliceStationDirectory,
 * TaxAgencyDirectory, EmergencyWhitelist, ScamPrefixSeed) still expose
 * exactly the data the packs carry — the restructure relocated the data,
 * it did not change it.
 */
class CountryPackTest {

    @Test fun jp_is_the_first_registered_pack() {
        assertEquals("JP", CountryPacks.all.first().iso)
        assertEquals(JpCountryPack, CountryPacks.forIso("JP"))
    }

    @Test fun country_without_a_pack_resolves_to_null() {
        // No US pack ships yet: layers keyed on a pack must abstain, which
        // forIso()==null is how decide() knows to skip them.
        assertNull(CountryPacks.forIso("US"))
        assertNull(CountryPacks.forIso(null))
    }

    @Test fun jp_pack_identity_fields() {
        assertEquals("JP", JpCountryPack.iso)
        assertEquals("81", JpCountryPack.callingCode)
    }

    @Test fun jp_pack_police_lookup_accepts_domestic_and_e164() {
        assertEquals("警視庁", JpCountryPack.lookupPolice("0335814321"))
        assertEquals("警視庁", JpCountryPack.lookupPolice("+81335814321"))
        // Carrier-mangled form (trunk zero kept after +81) still matches.
        assertEquals("警視庁", JpCountryPack.lookupPolice("+810335814321"))
        assertNull(JpCountryPack.lookupPolice("+14155551234"))
        assertNull(JpCountryPack.lookupPolice("09012345678"))
    }

    @Test fun jp_pack_tax_lookup_accepts_domestic_and_e164() {
        assertEquals("国税庁", JpCountryPack.lookupTax("0352533111"))
        assertEquals("国税庁", JpCountryPack.lookupTax("+81352533111"))
        assertNull(JpCountryPack.lookupTax("0335814321"))
    }

    @Test fun police_aggregate_is_exactly_the_jp_pack() {
        assertEquals(JpCountryPack.policeDirectory, PoliceStationDirectory.entries)
        assertEquals(54, PoliceStationDirectory.entries.size)
    }

    @Test fun tax_aggregate_is_exactly_the_jp_pack() {
        assertEquals(JpCountryPack.taxDirectory, TaxAgencyDirectory.entries)
        assertEquals(1, TaxAgencyDirectory.entries.size)
    }

    @Test fun emergency_whitelist_unions_pack_and_roaming_numbers() {
        for (n in JpCountryPack.emergencyNumbers) {
            assertTrue("$n (JP pack) must be an emergency number", EmergencyWhitelist.isEmergency(n))
        }
        // Roaming numbers are owned by EmergencyWhitelist itself, not a pack.
        for (n in listOf("911", "112", "999", "000", "+1911", "+44999", "+61000", "+112")) {
            assertTrue("$n (roaming) must be an emergency number", EmergencyWhitelist.isEmergency(n))
        }
        assertFalse(EmergencyWhitelist.isEmergency("1100"))
    }

    @Test fun seed_view_is_exactly_the_jp_pack_elevated_set() {
        assertEquals(JpCountryPack.elevatedRiskCountryCodes, ScamPrefixSeed.elevatedRiskCountryCodes)
        assertEquals(20, ScamPrefixSeed.elevatedRiskCountryCodes.size)
    }

    @Test fun jp_pack_spoof_check_follows_the_jp_plan() {
        assertTrue(JpCountryPack.isImpossibleDomesticNumber("02012345678"))
        assertFalse(JpCountryPack.isImpossibleDomesticNumber("09012345678"))
        // Non-JP numbers are out of scope for the JP plan, never flagged.
        assertFalse(JpCountryPack.isImpossibleDomesticNumber("+14155551234"))
    }

    @Test fun pack_index_parse_skips_comments_blanks_and_uppercases() {
        val codes = CountryPackIndex.parse(BufferedReader(StringReader(
            "# registry\n\njp\n  kr  \n# trailing comment\n")))
        assertEquals(listOf("JP", "KR"), codes)
    }
}

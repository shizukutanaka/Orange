package com.orange.apple

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Small sanity tests for the static lookup tables. */
class WhitelistAndSeedTest {

    // Emergency ---------------------------------------------------------------

    @Test fun emergency_jp_110() = assertTrue(EmergencyWhitelist.isEmergency("110"))
    @Test fun emergency_jp_119() = assertTrue(EmergencyWhitelist.isEmergency("119"))
    @Test fun emergency_jp_118() = assertTrue(EmergencyWhitelist.isEmergency("118"))
    @Test fun emergency_jp_189() = assertTrue(EmergencyWhitelist.isEmergency("189"))  // 児童相談所
    @Test fun emergency_jp_171() = assertTrue(EmergencyWhitelist.isEmergency("171"))  // 災害用伝言ダイヤル
    @Test fun emergency_us_911() = assertTrue(EmergencyWhitelist.isEmergency("911"))
    @Test fun emergency_eu_112() = assertTrue(EmergencyWhitelist.isEmergency("112"))
    @Test fun emergency_uk_999() = assertTrue(EmergencyWhitelist.isEmergency("999"))
    @Test fun emergency_au_000() = assertTrue(EmergencyWhitelist.isEmergency("000"))
    @Test fun emergency_jp_189_intl_form() = assertTrue(EmergencyWhitelist.isEmergency("+81189"))
    @Test fun emergency_jp_171_intl_form() = assertTrue(EmergencyWhitelist.isEmergency("+81171"))
    @Test fun not_emergency_regular_number() =
        assertFalse(EmergencyWhitelist.isEmergency("09012345678"))
    @Test fun not_emergency_empty() = assertFalse(EmergencyWhitelist.isEmergency(""))

    // Country code parsing — prefix-free property ----------------------------

    @Test fun cc_jp_81() = assertEquals("81", ScamPrefixSeed.countryCodeOf("+819012345678"))
    @Test fun cc_us_1() = assertEquals("1", ScamPrefixSeed.countryCodeOf("+14155551234"))
    @Test fun cc_png_675() = assertEquals("675", ScamPrefixSeed.countryCodeOf("+67512345678"))
    @Test fun cc_russia_7() = assertEquals("7", ScamPrefixSeed.countryCodeOf("+79121234567"))
    @Test fun cc_domestic_null() = assertNull(ScamPrefixSeed.countryCodeOf("09012345678"))
    @Test fun cc_unknown_null() = assertNull(ScamPrefixSeed.countryCodeOf("+99900000000"))

    // Elevated-risk set --------------------------------------------------------

    @Test fun elevated_png() = assertTrue("675" in ScamPrefixSeed.elevatedRiskCountryCodes)
    @Test fun elevated_morocco() = assertTrue("212" in ScamPrefixSeed.elevatedRiskCountryCodes)
    @Test fun elevated_russia() = assertTrue("7" in ScamPrefixSeed.elevatedRiskCountryCodes)
    @Test fun not_elevated_au() = assertFalse("61" in ScamPrefixSeed.elevatedRiskCountryCodes)

    // +1 (US/Canada) is DELIBERATELY excluded from the elevated set even though
    // it carries high raw scam volume: it is also the highest-volume LEGITIMATE
    // international corridor to JP, so an unsolicited +1 call is still silenced
    // (by the generic foreign-unsolicited rule) but not mislabeled as elevated.
    // See ScamPrefixSeed KDoc. This test guards against a future edit silently
    // promoting +1 and overstating risk on the most legit-heavy corridor.
    @Test fun not_elevated_us_plus1() = assertFalse("1" in ScamPrefixSeed.elevatedRiskCountryCodes)

    // IRSF/Wangiri high-cost termination corridors (NDSS 2021, CFCA)
    @Test fun elevated_latvia() = assertTrue("371" in ScamPrefixSeed.elevatedRiskCountryCodes)
    @Test fun elevated_lithuania() = assertTrue("370" in ScamPrefixSeed.elevatedRiskCountryCodes)
    @Test fun elevated_saotome() = assertTrue("239" in ScamPrefixSeed.elevatedRiskCountryCodes)
    @Test fun elevated_cook_islands() = assertTrue("682" in ScamPrefixSeed.elevatedRiskCountryCodes)
    @Test fun irsf_corridor_resolves_country_code() =
        assertEquals("371", ScamPrefixSeed.countryCodeOf("+3711234567"))
    @Test fun not_elevated_jp() = assertFalse("81" in ScamPrefixSeed.elevatedRiskCountryCodes)
    @Test fun elevated_cambodia() = assertTrue("855" in ScamPrefixSeed.elevatedRiskCountryCodes)
    @Test fun cambodia_resolves_country_code() =
        assertEquals("855", ScamPrefixSeed.countryCodeOf("+85512345678"))
    @Test fun elevated_myanmar() = assertTrue("95" in ScamPrefixSeed.elevatedRiskCountryCodes)
    @Test fun myanmar_resolves_country_code() =
        assertEquals("95", ScamPrefixSeed.countryCodeOf("+9512345678"))
    @Test fun elevated_laos() = assertTrue("856" in ScamPrefixSeed.elevatedRiskCountryCodes)
    @Test fun laos_resolves_country_code() =
        assertEquals("856", ScamPrefixSeed.countryCodeOf("+85612345678"))

    // Caribbean premium NANP ---------------------------------------------------

    @Test fun caribbean_bahamas_is_premium() =
        assertTrue(CaribbeanPremiumNANP.isPremiumNANP("+12421234567"))
    @Test fun caribbean_jamaica_is_premium() =
        assertTrue(CaribbeanPremiumNANP.isPremiumNANP("+18761234567"))
    @Test fun caribbean_us_not_premium() =
        assertFalse(CaribbeanPremiumNANP.isPremiumNANP("+12125551234"))  // NYC
    @Test fun caribbean_non_nanp_not_premium() =
        assertFalse(CaribbeanPremiumNANP.isPremiumNANP("+447911123456"))
    // Regression: partial NANP number must NOT match (old length < 3 let "+1242" through)
    @Test fun caribbean_partial_nanp_not_premium() =
        assertFalse(CaribbeanPremiumNANP.isPremiumNANP("+1242"))
    @Test fun caribbean_too_short_local_not_premium() =
        assertFalse(CaribbeanPremiumNANP.isPremiumNANP("+1242123"))  // 7-digit local
}

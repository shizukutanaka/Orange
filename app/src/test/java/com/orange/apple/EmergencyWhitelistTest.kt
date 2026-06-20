package com.orange.apple

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for EmergencyWhitelist — the hard-coded emergency bypass that always
 * allows through regardless of any other rule. Incomplete coverage is a safety issue.
 */
class EmergencyWhitelistTest {

    @Test fun `JP police 110 is emergency`() {
        assertTrue(EmergencyWhitelist.isEmergency("110"))
    }

    @Test fun `JP fire 119 is emergency`() {
        assertTrue(EmergencyWhitelist.isEmergency("119"))
    }

    @Test fun `JP coast guard 118 is emergency`() {
        assertTrue(EmergencyWhitelist.isEmergency("118"))
    }

    @Test fun `JP child welfare 189 is emergency`() {
        assertTrue(EmergencyWhitelist.isEmergency("189"))
    }

    @Test fun `JP disaster info 171 is emergency`() {
        assertTrue(EmergencyWhitelist.isEmergency("171"))
    }

    @Test fun `US emergency 911 is emergency`() {
        assertTrue(EmergencyWhitelist.isEmergency("911"))
    }

    @Test fun `EU emergency 112 is emergency`() {
        assertTrue(EmergencyWhitelist.isEmergency("112"))
    }

    @Test fun `UK emergency 999 is emergency`() {
        assertTrue(EmergencyWhitelist.isEmergency("999"))
    }

    @Test fun `AU emergency 000 is emergency`() {
        assertTrue(EmergencyWhitelist.isEmergency("000"))
    }

    @Test fun `E164 JP police is emergency`() {
        assertTrue(EmergencyWhitelist.isEmergency("+81110"))
    }

    @Test fun `E164 JP fire is emergency`() {
        assertTrue(EmergencyWhitelist.isEmergency("+81119"))
    }

    @Test fun `E164 JP coast guard is emergency`() {
        assertTrue(EmergencyWhitelist.isEmergency("+81118"))
    }

    @Test fun `E164 US is emergency`() {
        assertTrue(EmergencyWhitelist.isEmergency("+1911"))
    }

    @Test fun `E164 UK is emergency`() {
        assertTrue(EmergencyWhitelist.isEmergency("+44999"))
    }

    @Test fun `E164 EU is emergency`() {
        assertTrue(EmergencyWhitelist.isEmergency("+112"))
    }

    @Test fun `E164 AU is emergency`() {
        assertTrue(EmergencyWhitelist.isEmergency("+61000"))
    }

    @Test fun `random domestic is not emergency`() {
        assertFalse(EmergencyWhitelist.isEmergency("0312345678"))
    }

    @Test fun `random international is not emergency`() {
        assertFalse(EmergencyWhitelist.isEmergency("+12125551234"))
    }

    @Test fun `empty string is not emergency`() {
        assertFalse(EmergencyWhitelist.isEmergency(""))
    }

    @Test fun `partial match like 911 prefix not emergency (must be exact)`() {
        assertFalse(EmergencyWhitelist.isEmergency("9111111"))
    }

    @Test fun `partial match like 119 prefix not emergency`() {
        assertFalse(EmergencyWhitelist.isEmergency("1191234"))
    }

    @Test fun `withheld caller ID is not emergency`() {
        assertFalse(EmergencyWhitelist.isEmergency(""))
    }

    @Test fun `restricted caller ID is not emergency`() {
        assertFalse(EmergencyWhitelist.isEmergency("restricted"))
    }

    @Test fun `special characters like asterisk in 110 are not covered (accept as documented limitation)`() {
        // *110 or #110 are NOT in the whitelist.
        // Per HONESTY_ADDENDUM item 7, spoofed emergency numbers are an accepted risk:
        // "We hard-allow the emergency list. A scammer spoofing 110 would ring through.
        // We accept this because silencing 110 to stop the 0.001% scammer would kill
        // real users calling for help."
        //
        // The inverse is also true: if a number arrives with special characters
        // (*110, #110), it is not a known emergency format and is not whitelisted.
        // This is acceptable because:
        // 1. Android's PHONE_STATE broadcast and CallScreeningService typically deliver
        //    numeric phone numbers, not special-char variants.
        // 2. If a real emergency call arrives with unusual formatting, it will fall
        //    through to the default ALLOW layer (Layer 16) and ring anyway.
        assertFalse(EmergencyWhitelist.isEmergency("*110"))
        assertFalse(EmergencyWhitelist.isEmergency("#110"))
        assertFalse(EmergencyWhitelist.isEmergency("*911"))
        assertFalse(EmergencyWhitelist.isEmergency("#911"))
    }
}

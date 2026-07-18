package com.orange.apple

import org.junit.Assert.*
import org.junit.Test

class PoliceStationDirectoryTest {

    // --- Domestic form lookups ---

    @Test fun keishicho_domestic_matches() {
        assertEquals("警視庁", PoliceStationDirectory.lookup("0335814321"))
    }

    @Test fun osaka_domestic_matches() {
        assertEquals("大阪府警察", PoliceStationDirectory.lookup("0669430110"))
    }

    @Test fun npa_domestic_matches() {
        // National Police Agency (警察庁) must be covered here — not in
        // BusinessDirectoryBundle, where it would ring with NO impersonation
        // warning and bypass STIR/SHAKEN verification (see business_directory.csv).
        assertEquals("警察庁", PoliceStationDirectory.lookup("0335810141"))
    }

    @Test fun npa_e164_matches() {
        assertEquals("警察庁", PoliceStationDirectory.lookup("+81335810141"))
    }

    @Test fun all_47_entries_are_non_empty() {
        assertTrue(PoliceStationDirectory.entries.isNotEmpty())
        // All 47 prefectural HQ should be present.
        assertTrue("Expected at least 47 entries", PoliceStationDirectory.entries.size >= 47)
    }

    @Test fun all_keys_are_10_digit_domestic() {
        for ((k, _) in PoliceStationDirectory.entries) {
            assertTrue("Key '$k' should start with 0", k.startsWith("0"))
            assertEquals("Key '$k' should be 10 digits", 10, k.length)
            assertTrue("Key '$k' should be all digits", k.all { it.isDigit() })
        }
    }

    @Test fun all_values_non_blank() {
        for ((_, v) in PoliceStationDirectory.entries) {
            assertTrue("Display name should not be blank", v.isNotBlank())
        }
    }

    // --- E.164 form lookups ---

    @Test fun keishicho_e164_matches() {
        // Standard E.164: leading zero stripped after +81
        assertEquals("警視庁", PoliceStationDirectory.lookup("+81335814321"))
    }

    @Test fun osaka_e164_matches() {
        assertEquals("大阪府警察", PoliceStationDirectory.lookup("+81669430110"))
    }

    @Test fun carrier_mangled_e164_with_leading_zero_matches() {
        // Some JP carriers deliver "+810335814321" (domestic leading zero preserved after +81).
        assertEquals("警視庁", PoliceStationDirectory.lookup("+810335814321"))
    }

    @Test fun hokkaido_carrier_mangled_matches() {
        assertEquals("北海道警察", PoliceStationDirectory.lookup("+810112510110"))
    }

    // --- Non-police numbers ---

    @Test fun regular_mobile_not_matched() {
        assertNull(PoliceStationDirectory.lookup("09012345678"))
    }

    @Test fun regular_landline_not_matched() {
        assertNull(PoliceStationDirectory.lookup("0312345678"))
    }

    @Test fun emergency_110_not_in_directory() {
        // 110 is handled by EmergencyWhitelist (Layer 1), not PoliceStationDirectory (Layer 9).
        // Calling 110 as a domestic number must not match the directory.
        assertNull(PoliceStationDirectory.lookup("110"))
    }

    @Test fun empty_not_matched() {
        assertNull(PoliceStationDirectory.lookup(""))
    }

    @Test fun international_non_jp_not_matched() {
        assertNull(PoliceStationDirectory.lookup("+14155551234"))
    }

    // --- Integration: Layer 9 fires for known police numbers ---

    @Test fun decide_rings_with_police_warning_for_keishicho() {
        val ctx = CallContext(
            number = "0335814321",
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
        assertEquals(WarnReason.POLICE_IMPERSONATION, d.warning)
        assertEquals("警視庁", d.warnPayload)
    }

    @Test fun decide_escalates_to_high_when_stir_shaken_also_fails() {
        val ctx = CallContext(
            number = "0335814321",
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
        assertEquals(WarnReason.POLICE_IMPERSONATION_HIGH, d.warning)
    }

    // --- 2025 アポ電: individual station numbers actively spoofed ---

    @Test fun marunouchi_domestic_matches() {
        // 千代田区公式警告 2025-06-19: 丸の内警察署番号でのアポ電が実際に発生中
        assertEquals("丸の内警察署", PoliceStationDirectory.lookup("0332130110"))
    }

    @Test fun marunouchi_e164_matches() {
        assertEquals("丸の内警察署", PoliceStationDirectory.lookup("+81332130110"))
    }

    @Test fun decide_rings_with_police_warning_for_marunouchi() {
        val ctx = CallContext(
            number = "0332130110",
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
        assertEquals(WarnReason.POLICE_IMPERSONATION, d.warning)
        assertEquals("丸の内警察署", d.warnPayload)
    }

    @Test fun shinjuku_station_domestic_matches() {
        assertEquals("新宿警察署", PoliceStationDirectory.lookup("0333460110"))
    }

    @Test fun decide_police_warning_survives_pause() {
        // Pause exists for call-volume fatigue, not to disable active-fraud
        // warnings. The call rings either way during pause; this asserts the
        // warning still surfaces rather than being silently dropped.
        val ctx = CallContext(
            number = "0335814321",
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
        assertEquals(WarnReason.POLICE_IMPERSONATION, d.warning)
        assertEquals("警視庁", d.warnPayload)
    }

    @Test fun decide_rings_with_no_warning_while_paused_for_non_gov_number() {
        // A regular unknown number during pause still just rings, no warning —
        // pause's blanket RING behaviour is otherwise unchanged for a number
        // that isn't a gov-impersonation target and isn't in a high-risk hour.
        // NOTE: nowMillis must be OUTSIDE the アポ電 high-risk windows here. Since
        // the high-risk-hour warning was made to survive Pause (see
        // CallDecisionTest.high_risk_hour_warning_survives_pause), an unknown
        // mobile at a high-risk hour would correctly warn even while paused —
        // so this "no warning" case is isolated with an off-peak time.
        // 79_200_000L = 1970-01-01 22:00 JST (Thu, off-peak), verified non-high-risk.
        val ctx = CallContext(
            number = "09099998888",
            calleeCountryIso = "JP",
            nowMillis = 79_200_000L
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
        assertNull(d.warning)
    }

    @Test fun decide_no_police_warning_outside_jp() {
        // Police directory is JP-specific; same number from non-JP callee gets no Layer 9 check.
        val ctx = CallContext(
            number = "0335814321",
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
        // Should ring without police warning (no Layer 9 match outside JP)
        assertNull(d.warning)
    }
}

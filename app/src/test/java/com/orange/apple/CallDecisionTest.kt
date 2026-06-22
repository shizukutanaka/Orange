package com.orange.apple

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the pure decision engine. Zero Android dependencies.
 * Zero mocks. Zero reflection. Just data-in, decision-out.
 *
 * Every branch in decide() is covered here. If a test is added to this
 * file, that's the correct signal to add the matching test case.
 * If a branch ships without a test in this file, assume it doesn't work.
 */
class CallDecisionTest {

    private val emptyState = CallState(
        outboundKnown = emptySet(),
        isSpamCached = false,
        knownBusinesses = emptySet(),
        pausedUntilMillis = 0L,
        recentShortRings = emptyMap(),
    )

    private fun call(number: String, iso: String? = "JP", now: Long = 1_700_000_000_000L) =
        CallContext(number = number, calleeCountryIso = iso, nowMillis = now)

    // --- Layer 1: Emergency ---------------------------------------------------

    @Test fun emergency_110_rings() =
        assertEquals(Verdict.RING, decide(call("110"), emptyState).verdict)

    @Test fun emergency_rings_through_pause() =
        assertEquals(
            Verdict.RING,
            decide(call("119", now = 100), emptyState.copy(pausedUntilMillis = Long.MAX_VALUE)).verdict
        )

    @Test fun emergency_rings_through_spam_cache() {
        // Adversarial: even if 110 is somehow in the spam cache, emergency wins.
        val state = emptyState.copy(isSpamCached = true)
        assertEquals(Verdict.RING, decide(call("110"), state).verdict)
    }

    @Test fun emergency_international_variant_rings() =
        assertEquals(Verdict.RING, decide(call("+81110"), emptyState).verdict)

    @Test fun emergency_jp_189_rings() =
        assertEquals(Verdict.RING, decide(call("189"), emptyState).verdict)

    @Test fun emergency_jp_171_rings() =
        assertEquals(Verdict.RING, decide(call("171"), emptyState).verdict)

    @Test fun emergency_jp_189_international_rings() =
        assertEquals(Verdict.RING, decide(call("+81189"), emptyState).verdict)

    @Test fun emergency_jp_171_international_rings() =
        assertEquals(Verdict.RING, decide(call("+81171"), emptyState).verdict)

    @Test fun emergency_au_000_international_rings() =
        assertEquals(Verdict.RING, decide(call("+61000"), emptyState).verdict)

    // --- Layer 2: Pause -------------------------------------------------------

    @Test fun paused_allows_foreign_call() {
        val state = emptyState.copy(pausedUntilMillis = 2000L)
        assertEquals(Verdict.RING, decide(call("+14155551234", now = 1000L), state).verdict)
    }

    @Test fun pause_expired_does_not_allow_foreign() {
        val state = emptyState.copy(pausedUntilMillis = 1000L)
        val d = decide(call("+14155551234", now = 2000L), state)
        assertEquals(Verdict.SILENCE, d.verdict)
    }

    // --- Layer 3: Outbound-known ----------------------------------------------

    @Test fun outbound_known_rings_even_from_foreign() {
        val state = emptyState.copy(outboundKnown = setOf("+14155551234"))
        assertEquals(Verdict.RING, decide(call("+14155551234"), state).verdict)
    }

    // --- Layer 4: Bundled business --------------------------------------------

    @Test fun bundled_business_rings() {
        val state = emptyState.copy(knownBusinesses = setOf("+81570200000"))
        assertEquals(Verdict.RING, decide(call("+81570200000"), state).verdict)
    }

    // --- Layer 5: Spam cache --------------------------------------------------

    @Test fun cached_spam_is_silenced() {
        val state = emptyState.copy(isSpamCached = true)
        val d = decide(call("+819012345678"), state)
        assertEquals(Verdict.SILENCE, d.verdict)
        assertEquals(BlockReason.SPAM_CACHE, d.reason)
    }

    // --- Layer 6: Wangiri callback --------------------------------------------

    @Test fun wangiri_recent_callback_silenced() {
        val now = 1_700_000_000_000L
        val state = emptyState.copy(
            recentShortRings = mapOf("+16712345678" to now - 60_000L)
        )
        val d = decide(call("+16712345678", now = now), state)
        assertEquals(Verdict.SILENCE, d.verdict)
        assertEquals(BlockReason.WANGIRI_CALLBACK, d.reason)
    }

    @Test fun wangiri_after_six_hours_no_longer_blocks() {
        val now = 1_700_000_000_000L
        val oldTs = now - (WANGIRI_WINDOW_MS + 1000L)
        val state = emptyState.copy(recentShortRings = mapOf("+16712345678" to oldTs))
        // Falls through wangiri → +671 (Guam, US code variant) country code
        // parses to "1" which maps to US, so lands in foreign-unsolicited.
        val d = decide(call("+16712345678", now = now), state)
        assertEquals(Verdict.SILENCE, d.verdict)
        assertEquals(BlockReason.FOREIGN_GENERIC, d.reason)
    }

    @Test fun wangiri_after_six_hours_us_number_falls_to_generic_foreign() {
        val now = 1_700_000_000_000L
        val oldTs = now - (WANGIRI_WINDOW_MS + 1000L)
        val state = emptyState.copy(recentShortRings = mapOf("+14155551234" to oldTs))
        val d = decide(call("+14155551234", now = now), state)
        assertEquals(Verdict.SILENCE, d.verdict)
        assertEquals(BlockReason.FOREIGN_GENERIC, d.reason)
    }

    @Test fun wangiri_e164_callback_matches_domestic_short_ring() {
        // Regression: short ring arrives as "09012345678" (domestic), callback arrives
        // as "+819012345678" (E.164). Without variant expansion the map lookup misses
        // and the Wangiri block doesn't fire. The fix expands phoneVariants() in Layer 7.
        val now = 1_700_000_000_000L
        val state = emptyState.copy(
            recentShortRings = mapOf("09012345678" to now - 60_000L)
        )
        val d = decide(call("+819012345678", iso = "JP", now = now), state)
        assertEquals(Verdict.SILENCE, d.verdict)
        assertEquals(BlockReason.WANGIRI_CALLBACK, d.reason)
    }

    @Test fun wangiri_domestic_callback_matches_e164_short_ring() {
        // Inverse: short ring arrives as "+819012345678" (E.164), callback as domestic.
        val now = 1_700_000_000_000L
        val state = emptyState.copy(
            recentShortRings = mapOf("+819012345678" to now - 60_000L)
        )
        val d = decide(call("09012345678", iso = "JP", now = now), state)
        assertEquals(Verdict.SILENCE, d.verdict)
        assertEquals(BlockReason.WANGIRI_CALLBACK, d.reason)
    }

    // --- Layer 7: Domestic JP spoof -------------------------------------------

    @Test fun jp_020_reserved_number_is_spoof() {
        // 020-XXXX is MIC-reserved, not allocated to subscriber lines.
        val d = decide(call("02012345678"), emptyState)
        assertEquals(Verdict.SILENCE, d.verdict)
        assertEquals(BlockReason.DOMESTIC_SPOOF, d.reason)
    }

    @Test fun jp_022_sendai_is_not_spoof() {
        // 022 = 仙台 geographic area code. Must NOT be flagged as spoof.
        // Previous bug: 02x was treated as all-invalid.
        val d = decide(call("0222211611"), emptyState)
        // Not spoof; falls through to Allow (domestic JP rings by default)
        assertEquals(Verdict.RING, d.verdict)
    }

    @Test fun jp_number_with_wrong_length_mobile_is_spoof() {
        // 090 prefix needs 11 digits; this one has 10
        val d = decide(call("0901234567"), emptyState)
        assertEquals(Verdict.SILENCE, d.verdict)
        assertEquals(BlockReason.DOMESTIC_SPOOF, d.reason)
    }

    @Test fun jp_premium_0990_caller_is_spoof() {
        val d = decide(call("0990123456"), emptyState)
        assertEquals(Verdict.SILENCE, d.verdict)
        assertEquals(BlockReason.DOMESTIC_SPOOF, d.reason)
    }

    @Test fun jp_repeating_digit_block_is_spoof() {
        val d = decide(call("09011111111"), emptyState)
        assertEquals(Verdict.SILENCE, d.verdict)
        assertEquals(BlockReason.DOMESTIC_SPOOF, d.reason)
    }

    @Test fun valid_jp_mobile_rings() =
        assertEquals(Verdict.RING, decide(call("09012345678"), emptyState).verdict)

    @Test fun valid_jp_landline_rings() =
        assertEquals(Verdict.RING, decide(call("0355551234"), emptyState).verdict)

    @Test fun valid_jp_toll_free_rings() =
        assertEquals(Verdict.RING, decide(call("0120123456"), emptyState).verdict)

    // --- Layer 8: Foreign elevated-risk ---------------------------------------

    @Test fun palau_corridor_is_silenced_day_zero() {
        val d = decide(call("+67512345678"), emptyState)
        assertEquals(Verdict.SILENCE, d.verdict)
        assertEquals(BlockReason.FOREIGN_ELEVATED, d.reason)
    }

    @Test fun elevated_risk_only_applies_to_jp_users() {
        // A Palau call to a US user — not our concern, falls through to
        // foreign-unsolicited for US.
        val d = decide(call("+67512345678", iso = "US"), emptyState)
        assertEquals(Verdict.SILENCE, d.verdict)
        assertEquals(BlockReason.FOREIGN_GENERIC, d.reason)
    }

    // --- Layer 9: Generic foreign-unsolicited ---------------------------------

    @Test fun us_to_jp_silenced() {
        val d = decide(call("+14155551234"), emptyState)
        assertEquals(Verdict.SILENCE, d.verdict)
        assertEquals(BlockReason.FOREIGN_GENERIC, d.reason)
    }

    // Regression tests for the isoOfCountryCode→callingCodeOf fix.
    // These countries were previously allowed through because they weren't
    // in the 16-entry isoOfCountryCode map. They must now be silenced.
    @Test fun brazil_to_jp_silenced() {
        val d = decide(call("+5511987654321"), emptyState)
        assertEquals(Verdict.SILENCE, d.verdict)
        assertEquals(BlockReason.FOREIGN_GENERIC, d.reason)
    }

    @Test fun thailand_to_jp_silenced() {
        val d = decide(call("+66812345678"), emptyState)
        assertEquals(Verdict.SILENCE, d.verdict)
        assertEquals(BlockReason.FOREIGN_GENERIC, d.reason)
    }

    @Test fun indonesia_to_jp_silenced() {
        val d = decide(call("+62812345678"), emptyState)
        assertEquals(Verdict.SILENCE, d.verdict)
        assertEquals(BlockReason.FOREIGN_GENERIC, d.reason)
    }

    @Test fun turkey_to_jp_silenced() {
        val d = decide(call("+905551234567"), emptyState)
        assertEquals(Verdict.SILENCE, d.verdict)
        assertEquals(BlockReason.FOREIGN_GENERIC, d.reason)
    }

    @Test fun brazil_known_outbound_rings() {
        // A Brazilian number the JP user previously called must still ring.
        val state = emptyState.copy(outboundKnown = setOf("+5511987654321"))
        assertEquals(Verdict.RING, decide(call("+5511987654321"), state).verdict)
    }

    // --- Family number E.164 matching -----------------------------------------
    // Registered in domestic form but Android delivers E.164 — engine must match.

    @Test fun family_domestic_stored_e164_incoming_rings() {
        // User stores "09012345678"; incoming arrives as "+819012345678"
        val state = emptyState.copy(outboundKnown = setOf("09012345678", "+819012345678"))
        assertEquals(Verdict.RING, decide(call("+819012345678"), state).verdict)
    }

    @Test fun family_e164_stored_domestic_incoming_rings() {
        val state = emptyState.copy(outboundKnown = setOf("09012345678", "+819012345678"))
        assertEquals(Verdict.RING, decide(call("09012345678"), state).verdict)
    }

    // Outbound-known numbers ring even through the foreign layers
    @Test fun family_foreign_e164_rings() {
        val state = emptyState.copy(outboundKnown = setOf("+819012345678"))
        // Foreign generic would silence +81 vs +81 mismatch? No — same country code → not silenced anyway.
        // Test with a genuinely foreign number in outboundKnown:
        val state2 = emptyState.copy(outboundKnown = setOf("+85290001234"))
        assertEquals(Verdict.RING, decide(call("+85290001234"), state2).verdict)
    }

    @Test fun jp_to_jp_unknown_rings() =
        assertEquals(Verdict.RING, decide(call("+819087654321"), emptyState).verdict)

    // --- Spam-cache write policy (isCacheableSilence) -------------------------

    @Test fun dnd_honor_silence_is_not_cached() {
        // Contextual silence: must NOT persist into the spam cache, or the number
        // would stay silenced after the user turns DND off.
        assertEquals(false, isCacheableSilence(BlockReason.DND_HONOR))
    }

    @Test fun repeat_caller_silence_is_not_cached() {
        // Velocity-based silence: 60-min window expires naturally. Caching would
        // permanently block a legitimate urgent caller after the window clears.
        assertEquals(false, isCacheableSilence(BlockReason.REPEAT_CALLER))
    }

    @Test fun number_property_silences_are_cached() {
        for (r in listOf(
            BlockReason.SPAM_CACHE, BlockReason.FOREIGN_ELEVATED, BlockReason.FOREIGN_GENERIC,
            BlockReason.DOMESTIC_SPOOF, BlockReason.WANGIRI_CALLBACK,
            BlockReason.CARRIER_VERIFICATION_FAILED, BlockReason.PREMIUM_RATE_INTERNATIONAL,
        )) {
            assertEquals("$r should be cacheable", true, isCacheableSilence(r))
        }
    }

    @Test fun withheld_silence_is_not_cached() {
        // WITHHELD_NUMBER always has number="". SHA-256("salt"+"") is the same for every
        // withheld call, so one false-positive Restore would permanently unblock ALL
        // withheld numbers. The isNotEmpty() guard in SilentBlockerService already prevents
        // caching, but isCacheableSilence must also return false to make the invariant explicit.
        assertEquals(false, isCacheableSilence(BlockReason.WITHHELD_NUMBER))
    }

    // --- Layer 10: Allow default ----------------------------------------------

    @Test fun domestic_format_unknown_rings() =
        assertEquals(Verdict.RING, decide(call("0312345678"), emptyState).verdict)

    @Test fun empty_country_and_unknown_intl_prefix_rings() {
        // +999 doesn't resolve; engine plays it safe by ringing.
        val d = decide(call("+99900000000", iso = null), emptyState)
        assertEquals(Verdict.RING, d.verdict)
    }

    // --- Layer 7.5: STIR/SHAKEN carrier verification failed ----------------

    @Test fun carrier_verification_failed_silences() {
        val d = decide(
            call("09012345678").copy(verificationFailed = true),
            emptyState
        )
        assertEquals(Verdict.SILENCE, d.verdict)
        assertEquals(BlockReason.CARRIER_VERIFICATION_FAILED, d.reason)
    }

    @Test fun carrier_verification_failed_does_not_override_outbound_known() {
        val state = emptyState.copy(outboundKnown = setOf("09012345678"))
        val d = decide(
            call("09012345678").copy(verificationFailed = true),
            state
        )
        assertEquals(Verdict.RING, d.verdict) // outbound-known wins
    }

    // --- Withheld number (非通知) -----------------------------------------------

    @Test fun withheld_number_is_silenced() {
        val d = decide(call("").copy(numberWithheld = true), emptyState)
        assertEquals(Verdict.SILENCE, d.verdict)
        assertEquals(BlockReason.WITHHELD_NUMBER, d.reason)
    }

    @Test fun withheld_does_not_override_emergency() {
        // If somehow a withheld + emergency flags coexist, emergency wins
        // (emergency check runs before withheld)
        val d = decide(call("110").copy(numberWithheld = false), emptyState)
        assertEquals(Verdict.RING, d.verdict)
    }

    // --- Police HQ impersonation warning (rings but warns) ----------------------

    @Test fun police_hq_keishicho_rings_with_warning() {
        // 警視庁 03-3581-4321 is in the directory
        val d = decide(call("0335814321"), emptyState)
        assertEquals(Verdict.RING, d.verdict)
        assertEquals(WarnReason.POLICE_IMPERSONATION, d.warning)
    }

    @Test fun police_hq_osaka_rings_with_warning() {
        // 大阪府警察 06-6943-0110 is in the directory
        val d = decide(call("0669430110"), emptyState)
        assertEquals(Verdict.RING, d.verdict)
        assertEquals(WarnReason.POLICE_IMPERSONATION, d.warning)
    }

    @Test fun real_110_emergency_no_warning() {
        val d = decide(call("110"), emptyState)
        assertEquals(Verdict.RING, d.verdict)
        assertNull(d.warning) // emergency, no police impersonation warning
    }

    @Test fun police_hq_outbound_known_no_warning() {
        // User previously dialed 警視庁 → outbound-known wins → no warning
        val state = emptyState.copy(outboundKnown = setOf("0335814321"))
        val d = decide(call("0335814321"), state)
        assertEquals(Verdict.RING, d.verdict)
        assertNull(d.warning) // outbound-known → trusted, no warning
    }

    @Test fun unknown_non_police_number_no_warning() {
        // Ordinary landline — not in police directory → no warning
        val d = decide(call("0312345678"), emptyState)
        assertEquals(Verdict.RING, d.verdict)
        assertNull(d.warning)
    }

    // --- Premium rate international numbers ------------------------------------

    @Test fun intl_freephone_800_silenced() {
        val d = decide(call("+80012345678"), emptyState)
        assertEquals(Verdict.SILENCE, d.verdict)
        assertEquals(BlockReason.PREMIUM_RATE_INTERNATIONAL, d.reason)
    }

    @Test fun intl_premium_979_silenced() {
        val d = decide(call("+97912345678"), emptyState)
        assertEquals(Verdict.SILENCE, d.verdict)
        assertEquals(BlockReason.PREMIUM_RATE_INTERNATIONAL, d.reason)
    }

    @Test fun intl_network_882_silenced() {
        val d = decide(call("+88212345678"), emptyState)
        assertEquals(Verdict.SILENCE, d.verdict)
        assertEquals(BlockReason.PREMIUM_RATE_INTERNATIONAL, d.reason)
    }

    @Test fun intl_network_883_silenced() {
        val d = decide(call("+88312345678"), emptyState)
        assertEquals(Verdict.SILENCE, d.verdict)
        assertEquals(BlockReason.PREMIUM_RATE_INTERNATIONAL, d.reason)
    }

    // --- Defensive: empty number must not match outbound/spam set ---------------

    @Test fun empty_number_with_empty_in_outbound_does_not_ring() {
        // Adversarial: malicious or buggy state where "" is in outbound set.
        // Withheld layer should catch first; if withheld is false (impossible
        // case since empty number = withheld), the empty-number defensive
        // check should ring (safe fail).
        val state = emptyState.copy(outboundKnown = setOf(""))
        val ctxWithheld = call("").copy(numberWithheld = true)
        // Withheld silenced
        assertEquals(Verdict.SILENCE, decide(ctxWithheld, state).verdict)
        // Non-withheld empty (theoretically impossible) → safe RING
        val ctxNotWithheld = call("").copy(numberWithheld = false)
        assertEquals(Verdict.RING, decide(ctxNotWithheld, state).verdict)
    }

    // --- Caribbean NANP premium (+1-242, +1-876 etc.) -------------------------

    @Test fun caribbean_jamaica_876_silenced() {
        val d = decide(call("+18761234567"), emptyState)
        assertEquals(Verdict.SILENCE, d.verdict)
        assertEquals(BlockReason.PREMIUM_RATE_INTERNATIONAL, d.reason)
    }

    @Test fun caribbean_bahamas_242_silenced() {
        val d = decide(call("+12421234567"), emptyState)
        assertEquals(Verdict.SILENCE, d.verdict)
        assertEquals(BlockReason.PREMIUM_RATE_INTERNATIONAL, d.reason)
    }

    @Test fun us_mainland_415_not_premium() {
        // +1-415 is San Francisco — foreign-generic, not premium
        val d = decide(call("+14155551234"), emptyState)
        assertEquals(Verdict.SILENCE, d.verdict)
        assertEquals(BlockReason.FOREIGN_GENERIC, d.reason) // generic, not premium
    }

    @Test fun caribbean_outbound_known_still_rings() {
        val state = emptyState.copy(outboundKnown = setOf("+18761234567"))
        val d = decide(call("+18761234567"), state)
        assertEquals(Verdict.RING, d.verdict) // outbound-known beats premium
    }

    // --- Pause overrides withheld (new ordering) --------------------------------

    @Test fun paused_allows_withheld_call() {
        // User paused Orange → even 非通知 should ring.
        // Use case: expecting callback from hospital using restricted ID.
        val state = emptyState.copy(pausedUntilMillis = Long.MAX_VALUE)
        val d = decide(
            call("").copy(numberWithheld = true, nowMillis = 100L),
            state
        )
        assertEquals(Verdict.RING, d.verdict)
    }

    @Test fun unpaused_still_blocks_withheld() {
        // Normal state: withheld is blocked.
        val d = decide(call("").copy(numberWithheld = true), emptyState)
        assertEquals(Verdict.SILENCE, d.verdict)
        assertEquals(BlockReason.WITHHELD_NUMBER, d.reason)
    }

    // --- DND honor mode -------------------------------------------------------

    @Test fun dnd_active_silences_unknown_domestic() {
        val d = decide(call("0312345678").copy(dndActive = true), emptyState)
        assertEquals(Verdict.SILENCE, d.verdict)
        assertEquals(BlockReason.DND_HONOR, d.reason)
    }

    @Test fun dnd_inactive_allows_unknown_domestic() {
        val d = decide(call("0312345678").copy(dndActive = false), emptyState)
        assertEquals(Verdict.RING, d.verdict)
    }

    @Test fun dnd_does_not_affect_outbound_known() {
        val state = emptyState.copy(outboundKnown = setOf("0312345678"))
        val d = decide(call("0312345678").copy(dndActive = true), state)
        assertEquals(Verdict.RING, d.verdict) // outbound-known beats DND
    }

    // --- Time-of-day: unknown domestic mobile during high-risk hours ----------

    @Test fun high_risk_hour_domestic_mobile_gets_warning() {
        // Deterministic: construct a Tuesday 10:30 JST timestamp.
        // 2025-05-06 (Tuesday) 10:30 JST = 2025-05-06 01:30 UTC
        // millis = 1746494400000 (2025-05-06 00:00 UTC) + 90*60*1000 = 1746494400000 + 5400000
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Tokyo"))
        cal.set(2025, java.util.Calendar.MAY, 6, 10, 30, 0) // Tuesday 10:30 JST
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val tuesdayMorningJst = cal.timeInMillis

        val d = decide(call("09012345678").copy(nowMillis = tuesdayMorningJst), emptyState)
        assertEquals(Verdict.RING, d.verdict)
        assertEquals(WarnReason.HIGH_RISK_HOUR_DOMESTIC, d.warning)
    }

    @Test fun weekend_domestic_mobile_no_warning() {
        // Deterministic: construct a Saturday 10:00 JST timestamp.
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Tokyo"))
        cal.set(2025, java.util.Calendar.MAY, 10, 10, 0, 0) // Saturday 10:00 JST
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val saturdayJst = cal.timeInMillis

        val d = decide(call("09012345678").copy(nowMillis = saturdayJst), emptyState)
        assertEquals(Verdict.RING, d.verdict)
        assertNull(d.warning)
    }

    @Test fun midday_no_warning() {
        // 12:30 JST Tuesday — between the two peak windows (12:00-13:00)
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Tokyo"))
        cal.set(2025, java.util.Calendar.MAY, 6, 12, 30, 0) // Tuesday 12:30 JST
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val d = decide(call("09012345678").copy(nowMillis = cal.timeInMillis), emptyState)
        assertEquals(Verdict.RING, d.verdict)
        assertNull(d.warning)
    }

    @Test fun afternoon_peak_gets_warning() {
        // 14:00 JST Tuesday — in the 13:00-16:00 peak window
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Tokyo"))
        cal.set(2025, java.util.Calendar.MAY, 6, 14, 0, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val d = decide(call("08012345678").copy(nowMillis = cal.timeInMillis), emptyState)
        assertEquals(Verdict.RING, d.verdict)
        assertEquals(WarnReason.HIGH_RISK_HOUR_DOMESTIC, d.warning)
    }

    @Test fun noon_hour_12_00_gets_warning() {
        // 12:00 JST Tuesday — boundary of first peak window (should be included).
        // Previously: hour in 9..11 excluded hour 12 (bug). Fixed: hour in 9..12 includes it.
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Tokyo"))
        cal.set(2025, java.util.Calendar.MAY, 6, 12, 0, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val d = decide(call("09012345678").copy(nowMillis = cal.timeInMillis), emptyState)
        assertEquals(Verdict.RING, d.verdict)
        assertEquals(WarnReason.HIGH_RISK_HOUR_DOMESTIC, d.warning)
    }

    @Test fun hour_16_00_gets_warning() {
        // 16:00 JST Tuesday — boundary of second peak window (should be included).
        // Previously: hour in 13..15 excluded hour 16 (bug). Fixed: hour in 13..16 includes it.
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Tokyo"))
        cal.set(2025, java.util.Calendar.MAY, 6, 16, 0, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val d = decide(call("07012345678").copy(nowMillis = cal.timeInMillis), emptyState)
        assertEquals(Verdict.RING, d.verdict)
        assertEquals(WarnReason.HIGH_RISK_HOUR_DOMESTIC, d.warning)
    }

    // --- STIR/SHAKEN escalation to POLICE_IMPERSONATION_HIGH -----------------

    @Test fun police_hq_plus_stir_shaken_fail_escalates() {
        val d = decide(
            call("0335814321").copy(verificationFailed = true),
            emptyState
        )
        assertEquals(Verdict.RING, d.verdict)
        assertEquals(WarnReason.POLICE_IMPERSONATION_HIGH, d.warning)
    }

    @Test fun police_hq_without_stir_shaken_stays_normal() {
        val d = decide(call("0335814321"), emptyState)
        assertEquals(Verdict.RING, d.verdict)
        assertEquals(WarnReason.POLICE_IMPERSONATION, d.warning)
    }

    // --- warnPayload carries HQ name (no second lookup) -----------------------

    @Test fun police_hq_warn_payload_contains_name() {
        val d = decide(call("0335814321"), emptyState)
        assertEquals(Verdict.RING, d.verdict)
        assertEquals("警視庁", d.warnPayload)
    }

    @Test fun police_hq_high_payload_contains_name() {
        val d = decide(call("0335814321").copy(verificationFailed = true), emptyState)
        assertEquals(WarnReason.POLICE_IMPERSONATION_HIGH, d.warning)
        assertEquals("警視庁", d.warnPayload)
    }

    @Test fun non_police_number_has_null_payload() {
        val d = decide(call("0312345678"), emptyState)
        assertNull(d.warnPayload)
    }

    // --- E.164 JP mobile numbers also get the アポ電 high-risk-hour warning ----

    @Test fun e164_jp_mobile_high_risk_hour_gets_warning() {
        // Android delivers incoming JP mobile numbers in E.164 (+819x...).
        // isUnknownDomesticMobile must cover +8190/80/70/60 prefixes too.
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Tokyo"))
        cal.set(2025, java.util.Calendar.MAY, 6, 10, 30, 0) // Tuesday 10:30 JST
        cal.set(java.util.Calendar.MILLISECOND, 0)
        // +819012345678 = E.164 for 09012345678. callerCc="81" = calleeCc → passes Layer 13.
        val d = decide(
            CallContext("+819012345678", "JP", cal.timeInMillis),
            emptyState
        )
        assertEquals(Verdict.RING, d.verdict)
        assertEquals(WarnReason.HIGH_RISK_HOUR_DOMESTIC, d.warning)
    }

    @Test fun e164_jp_mobile_outside_high_risk_hour_no_warning() {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Tokyo"))
        cal.set(2025, java.util.Calendar.MAY, 6, 17, 0, 0) // Tuesday 17:00 JST — outside window
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val d = decide(
            CallContext("+819012345678", "JP", cal.timeInMillis),
            emptyState
        )
        assertEquals(Verdict.RING, d.verdict)
        assertNull(d.warning)
    }

    // --- Layer 15 exact boundary hours (hour in 9..11 || hour in 13..15) -------

    private fun jstHour(hour: Int): Long {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Tokyo"))
        cal.set(2025, java.util.Calendar.MAY, 6, hour, 0, 0) // Tuesday
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    @Test fun hour_8_is_outside_high_risk() {
        val d = decide(call("09012345678").copy(nowMillis = jstHour(8)), emptyState)
        assertNull(d.warning)
    }

    @Test fun hour_9_is_inside_high_risk() {
        val d = decide(call("09012345678").copy(nowMillis = jstHour(9)), emptyState)
        assertEquals(WarnReason.HIGH_RISK_HOUR_DOMESTIC, d.warning)
    }

    @Test fun hour_11_is_inside_high_risk() {
        val d = decide(call("09012345678").copy(nowMillis = jstHour(11)), emptyState)
        assertEquals(WarnReason.HIGH_RISK_HOUR_DOMESTIC, d.warning)
    }

    @Test fun hour_12_is_outside_high_risk() {
        val d = decide(call("09012345678").copy(nowMillis = jstHour(12)), emptyState)
        assertNull(d.warning)
    }

    @Test fun hour_13_is_inside_high_risk() {
        val d = decide(call("09012345678").copy(nowMillis = jstHour(13)), emptyState)
        assertEquals(WarnReason.HIGH_RISK_HOUR_DOMESTIC, d.warning)
    }

    @Test fun hour_15_is_inside_high_risk() {
        val d = decide(call("09012345678").copy(nowMillis = jstHour(15)), emptyState)
        assertEquals(WarnReason.HIGH_RISK_HOUR_DOMESTIC, d.warning)
    }

    @Test fun hour_16_is_outside_high_risk() {
        val d = decide(call("09012345678").copy(nowMillis = jstHour(16)), emptyState)
        assertNull(d.warning)
    }

    // --- callingCodeOf --------------------------------------------------------

    @Test fun calling_code_of_jp_is_81() = assertEquals("81", callingCodeOf("JP"))
    @Test fun calling_code_of_us_is_1()  = assertEquals("1",  callingCodeOf("US"))
    @Test fun calling_code_of_kr_is_82() = assertEquals("82", callingCodeOf("KR"))
    @Test fun calling_code_of_null_is_null() = assertNull(callingCodeOf(null))
    @Test fun calling_code_of_unknown_iso_is_null() = assertNull(callingCodeOf("ZZ"))

    // --- phoneVariants --------------------------------------------------------

    @Test fun phone_variants_domestic_expands_to_e164() {
        val v = phoneVariants("09012345678", "81")
        assertTrue(v.contains("09012345678"))
        assertTrue(v.contains("+819012345678"))
    }

    @Test fun phone_variants_e164_expands_to_domestic() {
        val v = phoneVariants("+819012345678", "81")
        assertTrue(v.contains("+819012345678"))
        assertTrue(v.contains("09012345678"))
    }

    @Test fun phone_variants_empty_is_empty() = assertTrue(phoneVariants("", "81").isEmpty())

    @Test fun phone_variants_null_calling_code_returns_singleton() {
        val v = phoneVariants("09012345678", null)
        assertEquals(setOf("09012345678"), v)
    }

    @Test fun phone_variants_does_not_cross_country_codes() {
        // A +1 number should NOT expand to a "0..." domestic when calling code is "81"
        val v = phoneVariants("+12125551234", "81")
        assertFalse(v.any { it.startsWith("0") && !it.startsWith("+") })
    }

    // --- Carrier-mangled E.164 "+810..." in isUnknownDomesticMobile ---

    @Test fun carrier_mangled_e164_mobile_treated_as_domestic_mobile() {
        // Some carriers deliver "+8109012345678" (leading zero kept after +81).
        // Without the "+810" check, isUnknownDomesticMobile() returns false and
        // Layer 15 does NOT fire the high-risk-hour warning for this caller.
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Tokyo"))
        cal.set(2025, java.util.Calendar.MAY, 6, 10, 30, 0) // Tuesday 10:30 JST
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val d = decide(
            CallContext("+8109012345678", "JP", cal.timeInMillis),
            emptyState
        )
        assertEquals(Verdict.RING, d.verdict)
        assertEquals(WarnReason.HIGH_RISK_HOUR_DOMESTIC, d.warning)
    }
}

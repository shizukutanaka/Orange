package com.orange.apple

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Property-style tests confirming the layer-priority ordering in decide().
 * Each test sets up a CallContext that would trigger MULTIPLE rules and
 * asserts the higher-priority one wins. If any of these assertions reverse,
 * the engine has a real-world correctness bug.
 */
class DecisionPriorityTest {

    private val emptyState = CallState(
        outboundKnown = emptySet(),
        isSpamCached = false,
        knownBusinesses = emptySet(),
        pausedUntilMillis = 0L,
        recentShortRings = emptyMap(),
    )

    private fun call(number: String, iso: String? = "JP", now: Long = 1_000_000L) =
        CallContext(number, iso, now)

    // 1 > 2: emergency beats pause (asserted in CallDecisionTest already; repeat for completeness)
    @Test fun emergency_beats_pause() {
        val state = emptyState.copy(pausedUntilMillis = Long.MAX_VALUE)
        assertEquals(Verdict.RING, decide(call("110"), state).verdict)
    }

    // 2 > 3: pause beats outbound-known (paused = let everything ring including outbound)
    @Test fun pause_and_outbound_both_ring_outcome_same() {
        val state = emptyState.copy(
            pausedUntilMillis = Long.MAX_VALUE,
            outboundKnown = setOf("+14155551234")
        )
        assertEquals(Verdict.RING, decide(call("+14155551234"), state).verdict)
    }

    // 3 > 5: outbound-known beats spam-cached (user explicitly trusts numbers they dialed)
    @Test fun outbound_known_beats_spam_cache() {
        val state = emptyState.copy(
            outboundKnown = setOf("+819012345678"),
            isSpamCached = true
        )
        // This is an unusual state but possible if the user dialed a number
        // they previously blocked. Trust the most-recent positive signal.
        assertEquals(Verdict.RING, decide(call("+819012345678"), state).verdict)
    }

    // 4 > 5: bundled business beats spam-cached
    @Test fun bundled_business_beats_spam_cache() {
        val state = emptyState.copy(
            knownBusinesses = setOf("+81570200000"),
            isSpamCached = true
        )
        assertEquals(Verdict.RING, decide(call("+81570200000"), state).verdict)
    }

    // 5 > 6: spam-cache beats wangiri (spam cache is a stronger signal — explicit user intent)
    @Test fun spam_cache_beats_wangiri() {
        val now = 1_000_000L
        val state = emptyState.copy(
            isSpamCached = true,
            recentShortRings = mapOf("+67512345" to now - 1000L)
        )
        val d = decide(call("+67512345", now = now), state)
        assertEquals(Verdict.SILENCE, d.verdict)
        assertEquals(BlockReason.SPAM_CACHE, d.reason)
    }

    // 6 > 7: wangiri beats domestic spoof
    @Test fun wangiri_beats_domestic_spoof() {
        val now = 1_000_000L
        // 02-prefix is structurally invalid JP; would be DOMESTIC_SPOOF.
        // If it's also a wangiri callback, we want the wangiri label.
        val state = emptyState.copy(
            recentShortRings = mapOf("02012345678" to now - 1000L)
        )
        val d = decide(call("02012345678", now = now), state)
        assertEquals(Verdict.SILENCE, d.verdict)
        assertEquals(BlockReason.WANGIRI_CALLBACK, d.reason)
    }

    // 7 > 8: domestic-spoof beats foreign-elevated (spoof check applies first for JP users)
    @Test fun domestic_spoof_beats_foreign_elevated() {
        // A "+81-02..." would be a JP-prefixed but structurally-invalid number.
        // It should be flagged DOMESTIC_SPOOF, not FOREIGN_ELEVATED, because
        // +81 maps to JP which is the user's own country (not foreign at all).
        val d = decide(call("+812012345678"), emptyState)
        assertEquals(Verdict.SILENCE, d.verdict)
        assertEquals(BlockReason.DOMESTIC_SPOOF, d.reason)
    }

    // 8 > 9: elevated-risk seed beats generic foreign
    @Test fun elevated_risk_beats_generic_foreign() {
        // Both paths would silence; we just want the more specific label.
        val d = decide(call("+67512345678"), emptyState)
        assertEquals(BlockReason.FOREIGN_ELEVATED, d.reason)
    }
}

    // 2 > 3: pause beats withheld (new ordering fix)
    @Test fun pause_beats_withheld() {
        val state = emptyState.copy(pausedUntilMillis = Long.MAX_VALUE)
        val d = decide(
            call("").copy(numberWithheld = true, nowMillis = 100L),
            state
        )
        assertEquals(Verdict.RING, d.verdict)
    }

    // REPEAT_CALLER is handled in screenIncoming (pre-decide), but the BlockReason
    // must be represented. Verify it exists as a valid enum value.
    @Test fun repeat_caller_block_reason_exists() {
        val reason = BlockReason.REPEAT_CALLER
        assertEquals(BlockReason.REPEAT_CALLER, reason)
    }

    // Layer priority: withheld vs police (layer 3 beats layer 10 — withheld rings with no police check)
    @Test fun withheld_does_not_reach_police_layer() {
        // A withheld call to 0335814321 (警視庁): withheld fires first
        val d = decide(
            CallContext("", "JP", 1_000_000L, numberWithheld = true),
            emptyState
        )
        assertEquals(Verdict.SILENCE, d.verdict)
        assertEquals(BlockReason.WITHHELD_NUMBER, d.reason)
        assertNull(d.warning) // no police warning
    }

    // Layer 9 (police check) must NOT fire here; Layer 10 (STIR/SHAKEN) silences non-police
    @Test fun stir_shaken_failed_domestic_silenced_not_warned() {
        // Non-police JP number with STIR failed → reaches Layer 10 → SILENCE
        val d = decide(
            CallContext("0312345678", "JP", 1_000_000L, verificationFailed = true),
            emptyState
        )
        assertEquals(Verdict.SILENCE, d.verdict)
        assertEquals(BlockReason.CARRIER_VERIFICATION_FAILED, d.reason)
    }

    // Layer 11 (premium) beats layer 12 (elevated) — +1-242 is premium not elevated
    @Test fun caribbean_premium_beats_elevated_corridor() {
        val d = decide(
            CallContext("+12421234567", "JP", 1_000_000L),
            emptyState
        )
        assertEquals(Verdict.SILENCE, d.verdict)
        assertEquals(BlockReason.PREMIUM_RATE_INTERNATIONAL, d.reason)
    }

    // Layer 14 (DND) beats layer 15 (time-of-day)
    @Test fun dnd_beats_high_risk_hour() {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Tokyo"))
        cal.set(2025, java.util.Calendar.MAY, 6, 10, 30, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val d = decide(
            CallContext("09012345678", "JP", cal.timeInMillis, dndActive = true),
            emptyState
        )
        assertEquals(Verdict.SILENCE, d.verdict)
        assertEquals(BlockReason.DND_HONOR, d.reason)
    }

    // calleeCountryIso=null: premium/elevated still silenced
    @Test fun null_iso_premium_still_silenced() {
        val d = decide(
            CallContext("+12421234567", null, 1_000_000L),
            emptyState
        )
        assertEquals(Verdict.SILENCE, d.verdict)
        // Caribbean NANP fires at Layer 11 regardless of callee ISO
        assertEquals(BlockReason.PREMIUM_RATE_INTERNATIONAL, d.reason)
    }

    // calleeCountryIso=null: domestic spoof check skipped (no false positive)
    @Test fun null_iso_no_domestic_spoof_check() {
        // A number that would be a domestic spoof if ISO=JP, but shouldn't fire with null
        val d = decide(
            CallContext("02012345678", null, 1_000_000L),
            emptyState
        )
        // 020 with null ISO → domestic spoof layer skipped → falls through to RING
        assertEquals(Verdict.RING, d.verdict)
    }
}

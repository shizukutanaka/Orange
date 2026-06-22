package com.orange.apple

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Documents and tests the Wangiri callback-warning logic in
 * SilentBlockerService.handleOutgoing().
 *
 * The warning fires when the user dials OUT to a number that is either:
 *   (A) in OutboundGuard  — previously blocked/warned incoming call
 *   (B) in WangiriTracker — recent short-ring candidate within 6h
 *
 * Case B closes a blind spot: if the user dials back within seconds of a
 * Wangiri short-ring, Orange has not yet seen the callback attempt (which
 * would trigger a WANGIRI_CALLBACK block and an OutboundGuard entry). A
 * purely OutboundGuard-based check misses this window entirely.
 */
class WangiriCallbackWarningTest {

    private lateinit var prefs: FakePrefs

    @Before fun setUp() { prefs = FakePrefs() }

    private val now = 1_700_000_000_000L

    // --- Case A: OutboundGuard (blocked/warned incoming) ---

    @Test fun `outbound guard entry triggers warning`() {
        OutboundGuard.record(prefs, "09012345678", now)
        val cc = "81"
        val variants = phoneVariants("09012345678", cc)
        val wangiriCandidates = WangiriTracker.snapshot(prefs, now + 1_000)
        val flagged = variants.any { v ->
            OutboundGuard.wasRecentlyFlagged(prefs, v, now + 1_000) ||
                wangiriCandidates.containsKey(v)
        }
        assertTrue("outbound guard entry must trigger warning", flagged)
    }

    // --- Case B: WangiriTracker (short-ring before Orange blocks the callback) ---

    @Test fun `wangiri short-ring entry triggers warning before callback`() {
        // User gets a 1-second ring at t0 (Wangiri bait); user calls back at t0+30s.
        // Orange has NOT yet blocked anything — OutboundGuard is empty.
        WangiriTracker.record(prefs, "09012345678", now)
        val cc = "81"
        val variants = phoneVariants("09012345678", cc)
        val wangiriCandidates = WangiriTracker.snapshot(prefs, now + 30_000)
        val outboundFlagged = variants.any { OutboundGuard.wasRecentlyFlagged(prefs, it, now + 30_000) }
        val wangiiFlagged   = variants.any { wangiriCandidates.containsKey(it) }
        assertFalse("outbound guard alone misses this case", outboundFlagged)
        assertTrue ("wangiri tracker must catch the callback", wangiiFlagged)
        assertTrue ("combined check must flag the call",  outboundFlagged || wangiiFlagged)
    }

    @Test fun `wangiri E164 variant also triggers warning`() {
        // Short-ring arrived as domestic; user dials back in E.164 form.
        WangiriTracker.record(prefs, "09012345678", now)
        val cc = "81"
        val variants = phoneVariants("+819012345678", cc)  // outgoing in E.164
        val wangiriCandidates = WangiriTracker.snapshot(prefs, now + 1_000)
        val flagged = variants.any { wangiriCandidates.containsKey(it) }
        assertTrue("E.164 outbound must match domestic short-ring via variants", flagged)
    }

    @Test fun `wangiri entry expires after 6h so old bait does not warn forever`() {
        WangiriTracker.record(prefs, "09012345678", now)
        val after6h = now + WangiriTracker.WANGIRI_WINDOW_MS + 1
        val cc = "81"
        val variants = phoneVariants("09012345678", cc)
        val wangiriCandidates = WangiriTracker.snapshot(prefs, after6h)
        val flagged = variants.any { wangiriCandidates.containsKey(it) }
        assertFalse("expired wangiri entry must not trigger warning", flagged)
    }

    @Test fun `unrelated number does not trigger warning`() {
        WangiriTracker.record(prefs, "09012345678", now)
        OutboundGuard.record(prefs, "09088888888", now)
        val cc = "81"
        val calledNumber = "09099999999"
        val variants = phoneVariants(calledNumber, cc)
        val wangiriCandidates = WangiriTracker.snapshot(prefs, now + 1_000)
        val flagged = variants.any { v ->
            OutboundGuard.wasRecentlyFlagged(prefs, v, now + 1_000) ||
                wangiriCandidates.containsKey(v)
        }
        assertFalse("unrelated number must not be flagged", flagged)
    }
}

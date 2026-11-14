package com.orange.apple

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Layer 6b (user prefix rules, ported from Ring) inside decide().
 *
 * The placement is the contract under test: trust layers and the
 * exact-match spam cache outrank a prefix rule; every heuristic layer
 * loses to it. See CallDecision.kt's Layer 6b comment.
 */
class PrefixRuleDecisionTest {

    private val baseState = CallState(
        outboundKnown = emptySet(),
        isSpamCached = false,
        knownBusinesses = emptySet(),
        pausedUntilMillis = 0L,
        wangiriRingAt = null,
    )

    private fun call(number: String, iso: String? = "JP", now: Long = 1_700_000_000_000L) =
        CallContext(number = number, calleeCountryIso = iso, nowMillis = now)

    private fun stateWith(vararg rules: PrefixRule) =
        baseState.copy(prefixRules = rules.toList())

    private val tollfreeRule = PrefixRule("0800", PrefixRule.SCOPE_NATIONAL)
    private val jpMobileRule = PrefixRule("8190", PrefixRule.SCOPE_E164)

    @Test fun `national rule silences matching domestic number`() {
        val d = decide(call("08001234567"), stateWith(tollfreeRule))
        assertEquals(Verdict.SILENCE, d.verdict)
        assertEquals(BlockReason.PREFIX_RULE, d.reason)
    }

    @Test fun `e164 rule silences domestic delivery`() {
        val d = decide(call("09012345678"), stateWith(jpMobileRule))
        assertEquals(Verdict.SILENCE, d.verdict)
        assertEquals(BlockReason.PREFIX_RULE, d.reason)
    }

    @Test fun `e164 rule silences international caller before foreign layers`() {
        val rule = PrefixRule("4420", PrefixRule.SCOPE_E164)
        val d = decide(call("+442071234567"), stateWith(rule))
        assertEquals(Verdict.SILENCE, d.verdict)
        assertEquals(BlockReason.PREFIX_RULE, d.reason)
    }

    @Test fun `non-matching number falls through and rings`() {
        val d = decide(call("09012345678"), stateWith(tollfreeRule))
        assertEquals(Verdict.RING, d.verdict)
    }

    @Test fun `family number beats prefix rule`() {
        val state = stateWith(jpMobileRule).copy(outboundKnown = setOf("09012345678"))
        assertEquals(Verdict.RING, decide(call("09012345678"), state).verdict)
    }

    @Test fun `bundled business beats prefix rule`() {
        val state = stateWith(jpMobileRule).copy(knownBusinesses = setOf("09012345678"))
        assertEquals(Verdict.RING, decide(call("09012345678"), state).verdict)
    }

    @Test fun `pause beats prefix rule`() {
        val state = stateWith(tollfreeRule).copy(pausedUntilMillis = Long.MAX_VALUE)
        assertEquals(Verdict.RING, decide(call("08001234567", now = 100), state).verdict)
    }

    @Test fun `spam cache reason wins over prefix rule`() {
        val state = stateWith(tollfreeRule).copy(isSpamCached = true)
        val d = decide(call("08001234567"), state)
        assertEquals(Verdict.SILENCE, d.verdict)
        assertEquals(BlockReason.SPAM_CACHE, d.reason)
    }

    @Test fun `withheld number keeps withheld reason`() {
        val ctx = CallContext(
            number = "", calleeCountryIso = "JP",
            nowMillis = 1_700_000_000_000L, numberWithheld = true,
        )
        val d = decide(ctx, stateWith(tollfreeRule))
        assertEquals(Verdict.SILENCE, d.verdict)
        assertEquals(BlockReason.WITHHELD_NUMBER, d.reason)
    }

    @Test fun `emergency rings even when a rule matches its digits`() {
        val state = stateWith(PrefixRule("811", PrefixRule.SCOPE_E164))
        assertEquals(Verdict.RING, decide(call("+81110"), state).verdict)
    }

    @Test fun `prefix silence is never cached`() {
        assertFalse(isCacheableSilence(BlockReason.PREFIX_RULE))
        assertFalse(isExpiringSilence(BlockReason.PREFIX_RULE))
    }

    @Test fun `no rules reproduces pre-port behaviour`() {
        val d = decide(call("08001234567"), baseState)
        assertEquals(Verdict.RING, d.verdict)
    }
}

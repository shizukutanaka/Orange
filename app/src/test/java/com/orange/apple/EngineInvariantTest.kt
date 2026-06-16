package com.orange.apple

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Property-style invariant tests.
 *
 * Instead of pulling in a property-based testing dependency (Kotest/jqwik),
 * which would bloat the build for a zero-dependency project, we generate a
 * large, deterministic input space in plain Kotlin and assert invariants over
 * it. This catches whole classes of bugs that hand-picked examples miss, while
 * keeping the test suite pure JUnit with no extra libraries.
 *
 * The invariants encode promises the engine must keep for EVERY input, not
 * just the examples we thought of.
 */
class EngineInvariantTest {

    private val isoOptions = listOf("JP", "US", null)

    private fun baseState() = CallState(
        outboundKnown = emptySet(),
        isSpamCached = false,
        knownBusinesses = emptySet(),
        pausedUntilMillis = 0L,
        recentShortRings = emptyMap(),
    )

    /** Generate a spread of representative numbers across all JP prefix classes. */
    private fun sampleNumbers(): List<String> {
        val out = mutableListOf<String>()
        // mobiles, landlines, special, international, malformed
        for (p in listOf("090", "080", "070", "060")) {
            out += "${p}12345678"        // 11-digit valid
            out += "${p}1234567"         // 10-digit invalid
        }
        for (area in listOf("03", "06", "022", "052", "075", "092")) {
            out += "${area}${"1".repeat(10 - area.length)}"
        }
        out += listOf("0120123456", "08001234567", "0570123456", "0990123456", "05012345678")
        out += listOf("+12125551234", "+447911123456", "+8613800138000", "+819012345678")
        out += listOf("", "1", "00", "0", "+", "abcd", "0".repeat(20))
        return out
    }

    // INVARIANT 1: decide() is deterministic — same input, same output.
    @Test fun decide_is_deterministic() {
        for (num in sampleNumbers()) {
            for (iso in isoOptions) {
                val ctx = CallContext(num, iso, 1_700_000_000_000L)
                val a = decide(ctx, baseState())
                val b = decide(ctx, baseState())
                assertEquals("non-deterministic for '$num'/$iso", a.verdict, b.verdict)
                assertEquals(a.reason, b.reason)
                assertEquals(a.warning, b.warning)
            }
        }
    }

    // INVARIANT 2: emergency numbers ALWAYS ring, regardless of state.
    @Test fun emergency_always_rings_under_any_state() {
        val emergencies = listOf(
            "110", "119", "118", "911", "112", "999", "000",
            "189", "171",
            "+81110", "+81119", "+81118", "+81189", "+81171", "+61000"
        )
        val hostileStates = listOf(
            baseState().copy(isSpamCached = true),
            // pausedUntilMillis must be > nowMillis (1_700_000_000_000L) to actually
            // activate the pause path. 0L is epoch (past), identical to baseState().
            baseState().copy(pausedUntilMillis = Long.MAX_VALUE),
            baseState().copy(recentShortRings = mapOf("110" to 1L)),
        )
        for (num in emergencies) {
            for (st in hostileStates) {
                for (iso in isoOptions) {
                    val d = decide(CallContext(num, iso, 1_700_000_000_000L), st)
                    assertEquals("emergency $num must ring", Verdict.RING, d.verdict)
                }
            }
        }
    }

    // INVARIANT 3: a SILENCE verdict always carries a reason; a warning never
    // accompanies a SILENCE (warnings are for calls that ring).
    @Test fun silence_has_reason_and_no_warning() {
        for (num in sampleNumbers()) {
            for (iso in isoOptions) {
                val d = decide(CallContext(num, iso, 1_700_000_000_000L), baseState())
                if (d.verdict == Verdict.SILENCE) {
                    assertTrue("SILENCE without reason for '$num'", d.reason != null)
                    assertTrue("SILENCE with warning for '$num'", d.warning == null)
                }
            }
        }
    }

    // INVARIANT 4: outbound-known numbers always ring (unless withheld/empty).
    @Test fun outbound_known_always_rings() {
        for (num in sampleNumbers().filter { it.isNotEmpty() }) {
            val st = baseState().copy(outboundKnown = setOf(num))
            val d = decide(CallContext(num, "JP", 1_700_000_000_000L), st)
            // Withheld is handled before outbound; here numberWithheld=false.
            assertEquals("outbound-known '$num' must ring", Verdict.RING, d.verdict)
        }
    }

    // INVARIANT 5: normalize is idempotent.
    @Test fun normalize_is_idempotent() {
        val raws = listOf("+81 (3) 3581-4321", "090-1234-5678", "  03 1234 5678 ",
                          "+1.212.555.1234", "abc123", "", "＋８１", "110")
        for (r in raws) {
            val once = PhoneNumbers.normalize(r)
            val twice = PhoneNumbers.normalize(once)
            assertEquals("normalize not idempotent for '$r'", once, twice)
        }
    }

    // INVARIANT 6: salted hash never equals plaintext and is stable per prefs.
    @Test fun salted_hash_properties() {
        val p = FakePrefs()
        for (num in sampleNumbers().filter { it.isNotEmpty() }) {
            val h = SpamCache.hash(p, num)
            assertNotEquals("hash equals plaintext for '$num'", num, h)
            assertEquals("hash length", 64, h.length)
            assertEquals("hash unstable for '$num'", h, SpamCache.hash(p, num))
        }
    }
}

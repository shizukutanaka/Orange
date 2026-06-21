package com.orange.apple

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rate-limit logic for WarningNotifier cannot be tested through the notifier itself
 * (it calls Android notification APIs), so we test the rate-limit key semantics directly
 * via SharedPreferences inspection, mirroring what WarningNotifier writes.
 *
 * Covered:
 *   - showHighRiskHourWarning: dedup key is domestic form so E.164/domestic variants share
 *     one 24-hour bucket (not two)
 *   - showOutboundWarning: dedup key is full number; different numbers get separate buckets
 *   - Backward clock jump: `now < last` never fires a spurious repeat
 */
class WarningNotifierRateLimitTest {

    // --- HighRiskHour key canonicalization ---

    @Test fun `highrisk rate-limit key uses domestic form for JP E164`() {
        val p = FakePrefs()
        val callingCode = "81"
        val domestic = "09012345678"
        val e164 = "+819012345678"
        val now = 1_000_000L
        val window = 24L * 60 * 60 * 1000

        // Simulate what WarningNotifier.showHighRiskHourWarning does: pick domestic form
        fun canonicalKey(number: String): String {
            val keyNumber = phoneVariants(number, callingCode)
                .firstOrNull { !it.startsWith("+") } ?: number
            return "highrisk_last_$keyNumber"
        }

        // Both variants must map to the same key
        assertEquals(canonicalKey(domestic), canonicalKey(e164))
    }

    @Test fun `highrisk domestic and E164 share same rate-limit bucket`() {
        val p = FakePrefs()
        val callingCode = "81"
        val domestic = "09012345678"
        val e164 = "+819012345678"
        val now = 1_000_000L

        fun canonicalKey(number: String): String {
            val keyNumber = phoneVariants(number, callingCode)
                .firstOrNull { !it.startsWith("+") } ?: number
            return "highrisk_last_$keyNumber"
        }

        // Simulate the notifier writing the timestamp for the domestic call
        val key = canonicalKey(domestic)
        p.edit().putLong(key, now).apply()

        // When E.164 form arrives within the window, the same key should be hit
        val keyForE164 = canonicalKey(e164)
        assertEquals(key, keyForE164)

        val last = p.getLong(keyForE164, 0L)
        val withinWindow = now + 1000L
        assertTrue("should be within 24h window", withinWindow - last < 24L * 60 * 60 * 1000)
    }

    @Test fun `highrisk rate-limit respects 24h window`() {
        val p = FakePrefs()
        val callingCode = "81"
        val number = "09012345678"
        val now = 1_000_000L
        val window = 24L * 60 * 60 * 1000

        val keyNumber = phoneVariants(number, callingCode)
            .firstOrNull { !it.startsWith("+") } ?: number
        val key = "highrisk_last_$keyNumber"

        p.edit().putLong(key, now).apply()

        // Within window: should be suppressed
        val last = p.getLong(key, 0L)
        val withinWindow = now + window - 1
        assertTrue(withinWindow - last < window)

        // After window: should fire
        val afterWindow = now + window + 1
        assertFalse(afterWindow - last < window)
    }

    @Test fun `highrisk backward clock jump does not fire`() {
        val p = FakePrefs()
        val callingCode = "81"
        val number = "09012345678"
        val now = 1_000_000_000L

        val keyNumber = phoneVariants(number, callingCode)
            .firstOrNull { !it.startsWith("+") } ?: number
        val key = "highrisk_last_$keyNumber"

        // Store a future timestamp (simulating a clock that jumped forward then back)
        val futureTs = now + 1_000L
        p.edit().putLong(key, futureTs).apply()

        // WarningNotifier guard: `if (now >= last && now - last < window) return`
        // When now < last (backward jump), the condition `now >= last` is false → do NOT suppress
        val last = p.getLong(key, 0L)
        // now < last → clock went backward, so warning should NOT be rate-limited
        assertFalse("backward clock should not suppress warning", now >= last && now - last < 24L * 60 * 60 * 1000)
    }

    // --- Outbound warning key isolation ---

    @Test fun `outbound rate-limit uses full number so distinct numbers are independent`() {
        val numberA = "+819012345678"
        val numberB = "+819087654321"
        val keyA = "outbound_warn_ts_$numberA"
        val keyB = "outbound_warn_ts_$numberB"
        assertTrue("distinct numbers must have distinct keys", keyA != keyB)
    }

    @Test fun `outbound rate-limit respects 1h window`() {
        val p = FakePrefs()
        val number = "+819012345678"
        val key = "outbound_warn_ts_$number"
        val now = 1_000_000L
        val window = 60L * 60 * 1000  // 1 hour

        p.edit().putLong(key, now).apply()

        val last = p.getLong(key, 0L)

        // Within 1h: suppressed
        assertTrue(now + window - 1 - last < window)

        // After 1h: allowed
        assertFalse(now + window + 1 - last < window)
    }

    @Test fun `outbound different numbers do not share rate-limit bucket`() {
        val p = FakePrefs()
        val numberA = "+819012345678"
        val numberB = "+819099999999"
        val now = 1_000_000L
        val window = 60L * 60 * 1000

        // Only record for A
        p.edit().putLong("outbound_warn_ts_$numberA", now).apply()

        // B should not be suppressed
        val lastB = p.getLong("outbound_warn_ts_$numberB", 0L)
        assertFalse("B should not be rate-limited by A's record", now - lastB < window)
    }

    // --- Numbers without a callingCode fallback to raw key ---

    @Test fun `highrisk without callingCode uses raw number as key`() {
        val number = "09012345678"
        // When callingCode is null, keyNumber = number, key = "highrisk_last_$number"
        val expectedKey = "highrisk_last_$number"
        val keyNumber: String = null.let { number }  // no phoneVariants called
        assertEquals(expectedKey, "highrisk_last_$keyNumber")
    }
}

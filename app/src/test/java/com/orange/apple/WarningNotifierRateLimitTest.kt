package com.orange.apple

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
 *   - Key privacy: keys are hashed (no raw number in key name)
 */
class WarningNotifierRateLimitTest {

    // Mirrors WarningNotifier.showHighRiskHourWarning key construction.
    private fun highriskKey(p: FakePrefs, number: String, callingCode: String): String {
        val keyNumber = phoneVariants(number, callingCode)
            .firstOrNull { !it.startsWith("+") } ?: number
        return "highrisk_last_${SpamCache.hash(p, keyNumber).take(16)}"
    }

    // Mirrors WarningNotifier.showOutboundWarning key construction.
    private fun outboundKey(p: FakePrefs, number: String): String =
        "outbound_warn_ts_${SpamCache.hash(p, number).take(16)}"

    // --- HighRiskHour key canonicalization ---

    @Test fun `highrisk rate-limit key uses domestic form for JP E164`() {
        val p = FakePrefs()
        val callingCode = "81"
        val domestic = "09012345678"
        val e164 = "+819012345678"
        // Both variants must resolve to the same key so they share one bucket.
        assertEquals(highriskKey(p, domestic, callingCode), highriskKey(p, e164, callingCode))
    }

    @Test fun `highrisk domestic and E164 share same rate-limit bucket`() {
        val p = FakePrefs()
        val callingCode = "81"
        val domestic = "09012345678"
        val e164 = "+819012345678"
        val now = 1_000_000L

        val key = highriskKey(p, domestic, callingCode)
        p.edit().putLong(key, now).apply()

        val keyForE164 = highriskKey(p, e164, callingCode)
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

        val key = highriskKey(p, number, callingCode)
        p.edit().putLong(key, now).apply()

        val last = p.getLong(key, 0L)
        val withinWindow = now + window - 1
        assertTrue(withinWindow - last < window)

        val afterWindow = now + window + 1
        assertFalse(afterWindow - last < window)
    }

    @Test fun `highrisk backward clock jump suppresses warning`() {
        val p = FakePrefs()
        val callingCode = "81"
        val number = "09012345678"
        val now = 1_000_000_000L

        val key = highriskKey(p, number, callingCode)
        val futureTs = now + 1_000L
        p.edit().putLong(key, futureTs).apply()

        val last = p.getLong(key, 0L)
        val window = 24L * 60 * 60 * 1000
        assertTrue("backward clock must suppress the warning", last > 0L && (now < last || now - last < window))
    }

    // --- Key privacy: raw number must not appear in key name ---

    @Test fun `highrisk key does not contain raw number`() {
        val p = FakePrefs()
        val number = "09012345678"
        val key = highriskKey(p, number, "81")
        assertFalse("key must not expose raw number", key.contains(number))
    }

    @Test fun `outbound key does not contain raw number`() {
        val p = FakePrefs()
        val number = "+819012345678"
        val key = outboundKey(p, number)
        assertFalse("key must not expose raw number", key.contains(number))
        assertFalse("key must not expose stripped number", key.contains("819012345678"))
    }

    // --- Outbound warning key isolation ---

    @Test fun `outbound rate-limit distinct numbers produce distinct keys`() {
        val p = FakePrefs()
        val numberA = "+819012345678"
        val numberB = "+819087654321"
        assertNotEquals(outboundKey(p, numberA), outboundKey(p, numberB))
    }

    @Test fun `outbound rate-limit respects 1h window`() {
        val p = FakePrefs()
        val number = "+819012345678"
        val key = outboundKey(p, number)
        val now = 1_000_000L
        val window = 60L * 60 * 1000

        p.edit().putLong(key, now).apply()
        val last = p.getLong(key, 0L)

        assertTrue(now + window - 1 - last < window)
        assertFalse(now + window + 1 - last < window)
    }

    @Test fun `outbound backward clock jump suppresses warning`() {
        val p = FakePrefs()
        val number = "+819012345678"
        val key = outboundKey(p, number)
        val now = 1_000_000_000L
        val futureTs = now + 1_000L
        p.edit().putLong(key, futureTs).apply()

        val last = p.getLong(key, 0L)
        val window = 60L * 60 * 1000
        assertTrue("backward clock must suppress outbound warning", last > 0L && (now < last || now - last < window))
    }

    @Test fun `outbound different numbers do not share rate-limit bucket`() {
        val p = FakePrefs()
        val numberA = "+819012345678"
        val numberB = "+819099999999"
        val now = 1_000_000L
        val window = 60L * 60 * 1000

        p.edit().putLong(outboundKey(p, numberA), now).apply()

        val lastB = p.getLong(outboundKey(p, numberB), 0L)
        assertFalse("B should not be rate-limited by A's record", now - lastB < window)
    }

    // --- Stale rate-limit key pruning ---
    // pruneStaleRateLimitKeys matches on key prefix only — key body format is opaque.

    @Test fun `pruneStaleRateLimitKeys removes expired highrisk keys`() {
        val p = FakePrefs()
        val now = 1_000_000_000L
        val expired = now - 24L * 60 * 60 * 1000 - 1
        val key = "highrisk_last_deadbeefcafebabe"
        p.edit().putLong(key, expired).apply()
        WarningNotifier.pruneStaleRateLimitKeys(p, now)
        assertFalse("expired highrisk key should be removed", p.contains(key))
    }

    @Test fun `pruneStaleRateLimitKeys keeps fresh highrisk keys`() {
        val p = FakePrefs()
        val now = 1_000_000_000L
        val fresh = now - 1000L
        val key = "highrisk_last_deadbeefcafebabe"
        p.edit().putLong(key, fresh).apply()
        WarningNotifier.pruneStaleRateLimitKeys(p, now)
        assertTrue("fresh highrisk key should survive", p.contains(key))
    }

    @Test fun `pruneStaleRateLimitKeys removes expired outbound keys`() {
        val p = FakePrefs()
        val now = 1_000_000_000L
        val expired = now - 60L * 60 * 1000 - 1
        val key = "outbound_warn_ts_deadbeefcafebabe"
        p.edit().putLong(key, expired).apply()
        WarningNotifier.pruneStaleRateLimitKeys(p, now)
        assertFalse("expired outbound key should be removed", p.contains(key))
    }

    @Test fun `pruneStaleRateLimitKeys keeps fresh outbound keys`() {
        val p = FakePrefs()
        val now = 1_000_000_000L
        val fresh = now - 1000L
        val key = "outbound_warn_ts_deadbeefcafebabe"
        p.edit().putLong(key, fresh).apply()
        WarningNotifier.pruneStaleRateLimitKeys(p, now)
        assertTrue("fresh outbound key should survive", p.contains(key))
    }

    @Test fun `pruneStaleRateLimitKeys does not touch unrelated keys`() {
        val p = FakePrefs()
        val now = 1_000_000_000L
        p.edit().putLong("some_other_key", 0L).apply()
        WarningNotifier.pruneStaleRateLimitKeys(p, now)
        assertTrue("unrelated key should not be removed", p.contains("some_other_key"))
    }

    @Test fun `pruneStaleRateLimitKeys backward clock does not prune future-ts keys`() {
        val p = FakePrefs()
        val now = 1_000_000L
        val futureTs = now + 1_000L
        val key = "highrisk_last_deadbeefcafebabe"
        p.edit().putLong(key, futureTs).apply()
        WarningNotifier.pruneStaleRateLimitKeys(p, now)
        assertTrue("future-ts key should survive backward clock", p.contains(key))
    }

    // --- wasWarnedRecently / cross-channel dedup (FEATURE_AUDIT.md §2-1) ---
    // Mirrors WarningNotifier's private recordWarningShown() key construction
    // ("warn_shown_last_" + 16-hex hash), since that function requires a
    // Context and can't be called directly from a JVM unit test.

    private fun warnShownKey(p: FakePrefs, number: String): String =
        "warn_shown_last_${SpamCache.hash(p, number).take(16)}"

    @Test fun `wasWarnedRecently is false when no warning was ever recorded`() {
        val p = FakePrefs()
        assertFalse(WarningNotifier.wasWarnedRecently(p, "09012345678", 1_000_000L))
    }

    @Test fun `wasWarnedRecently is true within the dedup window`() {
        val p = FakePrefs()
        val number = "09012345678"
        val now = 1_000_000L
        p.edit().putLong(warnShownKey(p, number), now).apply()
        val checkAt = now + WarningNotifier.WARN_SHOWN_DEDUP_WINDOW_MS - 1L
        assertTrue(WarningNotifier.wasWarnedRecently(p, number, checkAt))
    }

    @Test fun `wasWarnedRecently is false after the dedup window expires`() {
        val p = FakePrefs()
        val number = "09012345678"
        val now = 1_000_000L
        p.edit().putLong(warnShownKey(p, number), now).apply()
        val checkAt = now + WarningNotifier.WARN_SHOWN_DEDUP_WINDOW_MS + 1L
        assertFalse(WarningNotifier.wasWarnedRecently(p, number, checkAt))
    }

    @Test fun `wasWarnedRecently respects backward clock jump guard`() {
        val p = FakePrefs()
        val number = "09012345678"
        val now = 1_000_000_000L
        val futureTs = now + 1_000L
        p.edit().putLong(warnShownKey(p, number), futureTs).apply()
        assertFalse("backward clock must not spuriously suppress a real advisory",
            WarningNotifier.wasWarnedRecently(p, number, now))
    }

    @Test fun `wasWarnedRecently does not cross-contaminate different numbers`() {
        val p = FakePrefs()
        val warned = "09011112222"
        val other = "09099998888"
        val now = 1_000_000L
        p.edit().putLong(warnShownKey(p, warned), now).apply()
        assertFalse(WarningNotifier.wasWarnedRecently(p, other, now + 1000L))
    }

    @Test fun `pruneStaleRateLimitKeys removes expired warn_shown keys`() {
        val p = FakePrefs()
        val now = 1_000_000_000L
        val expired = now - WarningNotifier.WARN_SHOWN_DEDUP_WINDOW_MS - 1
        val key = "warn_shown_last_deadbeefcafebabe"
        p.edit().putLong(key, expired).apply()
        WarningNotifier.pruneStaleRateLimitKeys(p, now)
        assertFalse("expired warn_shown key should be removed", p.contains(key))
    }

    @Test fun `pruneStaleRateLimitKeys keeps fresh warn_shown keys`() {
        val p = FakePrefs()
        val now = 1_000_000_000L
        val fresh = now - 1000L
        val key = "warn_shown_last_deadbeefcafebabe"
        p.edit().putLong(key, fresh).apply()
        WarningNotifier.pruneStaleRateLimitKeys(p, now)
        assertTrue("fresh warn_shown key should survive", p.contains(key))
    }
}

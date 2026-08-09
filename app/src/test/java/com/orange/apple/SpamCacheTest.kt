package com.orange.apple

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpamCacheTest {

    @Test fun add_new_number_returns_true() {
        val p = FakePrefs()
        assertTrue(SpamCache.add(p, "+111"))
    }

    @Test fun add_existing_number_returns_false() {
        val p = FakePrefs()
        SpamCache.add(p, "+111")
        assertFalse(SpamCache.add(p, "+111"))
    }

    @Test fun contains_reflects_adds() {
        val p = FakePrefs()
        SpamCache.add(p, "+111")
        SpamCache.add(p, "+222")
        assertTrue(SpamCache.contains(p, "+111"))
        assertTrue(SpamCache.contains(p, "+222"))
        assertFalse(SpamCache.contains(p, "+333"))
    }

    @Test fun remove_works() {
        val p = FakePrefs()
        SpamCache.add(p, "+111")
        assertTrue(SpamCache.remove(p, "+111"))
        assertFalse(SpamCache.contains(p, "+111"))
    }

    @Test fun stored_values_are_hashed_not_plaintext() {
        // Privacy guarantee: the plaintext number must NEVER appear on disk.
        val p = FakePrefs()
        SpamCache.add(p, "+819012345678")
        val stored = SpamCache.snapshot(p)
        assertFalse("plaintext number leaked", "+819012345678" in stored)
        // The stored value is the salted SHA-256 hash (64 hex chars)
        val h = SpamCache.hash(p, "+819012345678")
        assertTrue(h in stored)
        assertEquals(64, h.length)
        assertNotEquals("+819012345678", h)
    }

    @Test fun hash_is_deterministic_within_install() {
        val p = FakePrefs()
        assertEquals(SpamCache.hash(p, "+111"), SpamCache.hash(p, "+111"))
        assertNotEquals(SpamCache.hash(p, "+111"), SpamCache.hash(p, "+222"))
    }

    @Test fun salt_differs_across_installs() {
        // Two independent installs (separate prefs) salt differently, so the
        // same number hashes to different values — defeats precomputation.
        val a = FakePrefs()
        val b = FakePrefs()
        assertNotEquals(SpamCache.hash(a, "+819012345678"),
                        SpamCache.hash(b, "+819012345678"))
    }

    @Test fun empty_number_not_added() {
        val p = FakePrefs()
        assertFalse(SpamCache.add(p, ""))
        assertFalse(SpamCache.contains(p, ""))
    }

    @Test fun exceeds_max_evicts_oldest() {
        val p = FakePrefs()
        repeat(SpamCache.MAX_ENTRIES + 5) { i ->
            SpamCache.add(p, "+$i")
        }
        assertEquals(SpamCache.MAX_ENTRIES, SpamCache.snapshot(p).size)
        // Oldest 5 should have been evicted
        assertFalse(SpamCache.contains(p, "+0"))
        assertFalse(SpamCache.contains(p, "+4"))
        assertTrue(SpamCache.contains(p, "+${SpamCache.MAX_ENTRIES + 4}"))
    }

    @Test fun contains_is_format_sensitive_hash_depends_on_exact_string() {
        // SpamCache hashes the exact number string. A number blocked in domestic form
        // ("09012345678") produces a different hash than its E.164 form ("+819012345678").
        // Callers (screenIncoming) must check all phoneVariants() to catch cross-format hits.
        val p = FakePrefs()
        SpamCache.add(p, "09012345678")            // blocked as domestic
        assertTrue(SpamCache.contains(p, "09012345678"))   // same form: hit
        assertFalse(SpamCache.contains(p, "+819012345678")) // E.164 form: miss (different hash)
        // The adapter fixes this by adding ALL variants to the cache on block,
        // and by checking ALL variants on lookup. Verify that adding the E.164 variant
        // also causes the domestic form to be a hit.
        SpamCache.add(p, "+819012345678")           // add E.164 variant too
        assertTrue(SpamCache.contains(p, "+819012345678")) // now both forms hit
        assertTrue(SpamCache.contains(p, "09012345678"))
    }

    @Test fun concurrent_add_does_not_corrupt_cache() {
        // Verifies that @Synchronized prevents lost-update races when two
        // threads add different numbers simultaneously.  Without the annotation
        // both threads could read the same snapshot, produce divergent writes,
        // and the second write would silently clobber the first.
        val p = FakePrefs()
        val numbers = (1..20).map { "+$it" }
        val threads = numbers.map { n ->
            Thread { SpamCache.add(p, n) }.also { it.start() }
        }
        threads.forEach { it.join(2_000) }

        // All 20 distinct numbers must be present (none lost to a race).
        numbers.forEach { n ->
            assertTrue("$n missing after concurrent adds", SpamCache.contains(p, n))
        }
    }

    // --- Expiry (FEATURE_AUDIT §1-8) -----------------------------------------
    // Situational judgements expire so a reassigned number stops being silenced
    // for its new owner; permanent properties and explicit user intent do not.

    private val t0 = 1_700_000_000_000L

    @Test fun expiring_entry_still_matches_before_its_ttl() {
        val p = FakePrefs()
        SpamCache.add(p, "+819012345678", expiring = true, nowMs = t0)
        val justBefore = t0 + SpamCache.DEFAULT_TTL_MS - 1
        assertTrue(SpamCache.contains(p, "+819012345678", justBefore))
    }

    @Test fun expiring_entry_stops_matching_after_its_ttl() {
        val p = FakePrefs()
        SpamCache.add(p, "+819012345678", expiring = true, nowMs = t0)
        val after = t0 + SpamCache.DEFAULT_TTL_MS + 1
        assertFalse(SpamCache.contains(p, "+819012345678", after))
    }

    @Test fun non_expiring_entry_survives_far_beyond_the_ttl() {
        // DOMESTIC_SPOOF / user intent: a number that violates the numbering plan
        // still violates it next year, so it must never age out.
        val p = FakePrefs()
        SpamCache.add(p, "+819012345678", expiring = false, nowMs = t0)
        val muchLater = t0 + SpamCache.DEFAULT_TTL_MS * 10
        assertTrue(SpamCache.contains(p, "+819012345678", muchLater))
    }

    @Test fun default_add_is_permanent() {
        // The no-arg overload must keep the historical never-expires behaviour,
        // so existing call sites are unchanged by the new parameter.
        val p = FakePrefs()
        SpamCache.add(p, "+819012345678")
        assertTrue(SpamCache.contains(p, "+819012345678", t0 + SpamCache.DEFAULT_TTL_MS * 10))
    }

    @Test fun expiry_prunes_the_entry_from_the_stored_set() {
        // Expiry must actually shrink the file, not just hide the hit — that is
        // half the point (FEATURE_AUDIT §1-10: cache size drives the hot-path cost).
        val p = FakePrefs()
        SpamCache.add(p, "+819012345678", expiring = true, nowMs = t0)
        assertEquals(1, SpamCache.snapshot(p).size)
        SpamCache.contains(p, "+81900000000", t0 + SpamCache.DEFAULT_TTL_MS + 1)
        assertEquals(0, SpamCache.snapshot(p).size)
    }

    @Test fun pruning_keeps_permanent_entries_and_drops_only_expired_ones() {
        val p = FakePrefs()
        SpamCache.add(p, "+81permanent", expiring = false, nowMs = t0)
        SpamCache.add(p, "+81expiring", expiring = true, nowMs = t0)
        val after = t0 + SpamCache.DEFAULT_TTL_MS + 1
        assertFalse(SpamCache.contains(p, "+81expiring", after))
        assertTrue(SpamCache.contains(p, "+81permanent", after))
        assertEquals(1, SpamCache.snapshot(p).size)
    }

    @Test fun backward_clock_does_not_expire_an_entry_early() {
        // Safe direction: keep silencing rather than un-blocking a scammer
        // because the device clock moved backwards.
        val p = FakePrefs()
        SpamCache.add(p, "+819012345678", expiring = true, nowMs = t0)
        assertTrue(SpamCache.contains(p, "+819012345678", t0 - 1_000_000))
    }

    @Test fun remove_works_on_an_expiring_entry() {
        // Restore must clear the entry regardless of which storage form it uses;
        // the token carries an expiry suffix, so a raw-hash match would miss it.
        val p = FakePrefs()
        SpamCache.add(p, "+819012345678", expiring = true, nowMs = t0)
        assertTrue(SpamCache.remove(p, "+819012345678", t0))
        assertFalse(SpamCache.contains(p, "+819012345678", t0))
        assertEquals(0, SpamCache.snapshot(p).size)
    }

    @Test fun legacy_entries_without_an_expiry_stamp_stay_permanent() {
        // Upgrade path: an install that predates expiry support has bare hashes
        // in KEY_ORDER. Those must keep working and must never be treated as
        // expired (no migration step, no silent data loss).
        val p = FakePrefs()
        val h = SpamCache.hash(p, "+819012345678")
        p.edit().putStringSet("spam", mutableSetOf(h)).putString("spam_order", h).apply()
        assertTrue(SpamCache.contains(p, "+819012345678", t0 + SpamCache.DEFAULT_TTL_MS * 10))
    }
}

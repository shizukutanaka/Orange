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
}

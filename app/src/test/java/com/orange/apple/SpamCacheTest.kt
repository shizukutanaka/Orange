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
}

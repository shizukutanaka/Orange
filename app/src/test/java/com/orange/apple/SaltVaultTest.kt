package com.orange.apple

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for SaltVault's fallback path.
 *
 * NOTE: a pure-JVM test host has no AndroidKeyStore, so these tests exercise
 * the graceful-degradation (plaintext fallback) branch. The Keystore-encrypted
 * branch runs only on a real device/emulator and is covered by manual release
 * verification, not here. What we CAN guarantee in JVM:
 *   - a salt is always produced
 *   - it is stable within an install (same prefs → same salt)
 *   - it differs across installs (different prefs → different salt)
 *   - it is a 32-hex-char (128-bit) value
 */
class SaltVaultTest {

    @Test fun salt_is_produced() {
        val p = FakePrefs()
        val s = SaltVault.salt(p)
        assertTrue("salt empty", s.isNotEmpty())
    }

    @Test fun salt_is_128_bit_hex() {
        val p = FakePrefs()
        val s = SaltVault.salt(p)
        assertEquals("expected 16 bytes = 32 hex chars", 32, s.length)
        assertTrue("not hex", s.all { it in "0123456789abcdef" })
    }

    @Test fun salt_is_stable_within_install() {
        val p = FakePrefs()
        assertEquals(SaltVault.salt(p), SaltVault.salt(p))
    }

    @Test fun salt_differs_across_installs() {
        val a = FakePrefs()
        val b = FakePrefs()
        assertNotEquals(SaltVault.salt(a), SaltVault.salt(b))
    }

    @Test fun salt_survives_repeated_reads() {
        val p = FakePrefs()
        val first = SaltVault.salt(p)
        repeat(5) { assertEquals(first, SaltVault.salt(p)) }
    }
}

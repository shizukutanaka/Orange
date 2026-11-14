package com.orange.apple

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Store behaviour for the ported prefix rules (see PrefixRules.kt). */
class PrefixBlockStoreTest {

    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        prefs = FakePrefs()
    }

    private val national = PrefixRule("0800", PrefixRule.SCOPE_NATIONAL)
    private val e164 = PrefixRule("8190", PrefixRule.SCOPE_E164)

    @Test fun `add and list round trip`() {
        assertTrue(PrefixBlockStore.add(prefs, national))
        assertTrue(PrefixBlockStore.add(prefs, e164))
        assertEquals(listOf(national, e164), PrefixBlockStore.listForEngine(prefs))
        // Display order is newest first.
        assertEquals(listOf(e164, national), PrefixBlockStore.list(prefs))
    }

    @Test fun `duplicate add is idempotent`() {
        assertTrue(PrefixBlockStore.add(prefs, national))
        assertTrue(PrefixBlockStore.add(prefs, national))
        assertEquals(listOf(national), PrefixBlockStore.listForEngine(prefs))
    }

    @Test fun `same digits different scope are distinct rules`() {
        val asNational = PrefixRule("0800", PrefixRule.SCOPE_NATIONAL)
        val asE164 = PrefixRule("0800", PrefixRule.SCOPE_E164)
        PrefixBlockStore.add(prefs, asNational)
        PrefixBlockStore.add(prefs, asE164)
        assertEquals(2, PrefixBlockStore.listForEngine(prefs).size)
    }

    @Test fun `remove deletes only the named rule`() {
        PrefixBlockStore.add(prefs, national)
        PrefixBlockStore.add(prefs, e164)
        PrefixBlockStore.remove(prefs, national)
        assertEquals(listOf(e164), PrefixBlockStore.listForEngine(prefs))
    }

    @Test fun `remove of absent rule is a no-op`() {
        PrefixBlockStore.remove(prefs, national)
        assertEquals(emptyList<PrefixRule>(), PrefixBlockStore.listForEngine(prefs))
    }

    @Test fun `add returns false at the cap instead of evicting`() {
        // Fill to the cap with distinct rules (national scope, 4 digits).
        for (i in 0 until 200) {
            val rule = PrefixRule("0%03d".format(i), PrefixRule.SCOPE_NATIONAL)
            assertTrue(PrefixBlockStore.add(prefs, rule))
        }
        assertFalse(PrefixBlockStore.add(prefs, PrefixRule("0999", PrefixRule.SCOPE_NATIONAL)))
        assertEquals(200, PrefixBlockStore.listForEngine(prefs).size)
    }
}

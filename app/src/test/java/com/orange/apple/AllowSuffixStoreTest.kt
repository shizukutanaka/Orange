package com.orange.apple

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AllowSuffixStoreTest {

    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        prefs = FakePrefs()
    }

    @Test
    fun `allowed number matches by suffix`() {
        AllowSuffixStore.allow(prefs, "****5678")
        assertTrue(AllowSuffixStore.isAllowed(prefs, "09012345678"))
    }

    @Test
    fun `non-allowed number returns false`() {
        AllowSuffixStore.allow(prefs, "****5678")
        assertFalse(AllowSuffixStore.isAllowed(prefs, "09012349999"))
    }

    @Test
    fun `short number not allowed`() {
        assertFalse(AllowSuffixStore.isAllowed(prefs, "110"))
    }

    @Test
    fun `multiple allows work independently`() {
        AllowSuffixStore.allow(prefs, "****1111")
        AllowSuffixStore.allow(prefs, "****2222")
        assertTrue(AllowSuffixStore.isAllowed(prefs, "09011111111"))
        assertTrue(AllowSuffixStore.isAllowed(prefs, "09022222222"))
        assertFalse(AllowSuffixStore.isAllowed(prefs, "09033333333"))
    }

    @Test
    fun `overflow evicts oldest, keeps newest`() {
        // Fill to MAX with suffixes 0000..0099 (100 entries)
        for (i in 0 until 100) {
            AllowSuffixStore.allow(prefs, "****%04d".format(i))
        }
        // Adding a 101st entry should evict the oldest (0000), keep all others.
        AllowSuffixStore.allow(prefs, "****9999")
        assertFalse("oldest should be evicted", AllowSuffixStore.isAllowed(prefs, "09010000"))
        assertTrue("newest must survive", AllowSuffixStore.isAllowed(prefs, "09019999"))
        assertTrue("recent entry must survive", AllowSuffixStore.isAllowed(prefs, "09010099"))
    }

    @Test
    fun `duplicate allow is idempotent and does not grow list`() {
        AllowSuffixStore.allow(prefs, "****1234")
        AllowSuffixStore.allow(prefs, "****1234")
        assertTrue(AllowSuffixStore.isAllowed(prefs, "09012341234"))
    }

    @Test
    fun `allow with masked short number is a silent no-op`() {
        // BlockHistoryStore masks numbers ≤4 digits as "****" (no digit suffix).
        // allow("****") must not store anything — the suffix would be empty and
        // every 4-digit-or-shorter number would match, which is too broad.
        AllowSuffixStore.allow(prefs, "****")
        assertFalse(AllowSuffixStore.isAllowed(prefs, "0110"))
        assertFalse(AllowSuffixStore.isAllowed(prefs, "09012345678"))
    }
}

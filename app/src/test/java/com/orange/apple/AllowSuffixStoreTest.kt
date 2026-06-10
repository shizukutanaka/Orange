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
}

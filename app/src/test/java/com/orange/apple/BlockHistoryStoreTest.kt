package com.orange.apple

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BlockHistoryStoreTest {

    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        prefs = FakePrefs()
    }

    @Test
    fun `record stores entry with masked number`() {
        val now = 1_700_000_000_000L
        BlockHistoryStore.record(prefs, "09012345678", BlockReason.SPAM_CACHE, now)
        val entries = BlockHistoryStore.load(prefs, now)
        assertEquals(1, entries.size)
        assertEquals("****5678", entries[0].maskedNumber)
        assertEquals(BlockReason.SPAM_CACHE, entries[0].reason)
        assertEquals(now, entries[0].timestampMs)
    }

    @Test
    fun `load drops entries older than 30 days`() {
        val now = 1_700_000_000_000L
        val old = now - (31L * 24 * 60 * 60 * 1000)
        BlockHistoryStore.record(prefs, "09011112222", BlockReason.WITHHELD_NUMBER, old)
        val entries = BlockHistoryStore.load(prefs, now)
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `record respects MAX_ENTRIES limit`() {
        val now = 1_700_000_000_000L
        repeat(BlockHistoryStore.MAX_ENTRIES + 5) { i ->
            BlockHistoryStore.record(prefs, "090000000${i % 10}", BlockReason.REPEAT_CALLER, now + i)
        }
        val entries = BlockHistoryStore.load(prefs, now + 100)
        assertTrue(entries.size <= BlockHistoryStore.MAX_ENTRIES)
    }

    @Test
    fun `short number masked correctly`() {
        val now = 1_700_000_000_000L
        BlockHistoryStore.record(prefs, "110", BlockReason.SPAM_CACHE, now)
        val entries = BlockHistoryStore.load(prefs, now)
        assertEquals("****", entries[0].maskedNumber)
    }

    @Test
    fun `most recent entry appears first`() {
        val now = 1_700_000_000_000L
        BlockHistoryStore.record(prefs, "09011111111", BlockReason.SPAM_CACHE, now)
        BlockHistoryStore.record(prefs, "09022222222", BlockReason.REPEAT_CALLER, now + 1000)
        val entries = BlockHistoryStore.load(prefs, now + 2000)
        assertEquals(BlockReason.REPEAT_CALLER, entries[0].reason)
    }

    @Test
    fun `remove deletes entry from persistent storage`() {
        val now = 1_700_000_000_000L
        BlockHistoryStore.record(prefs, "09012345678", BlockReason.SPAM_CACHE, now)
        BlockHistoryStore.record(prefs, "08087654321", BlockReason.REPEAT_CALLER, now + 1000)
        val entries = BlockHistoryStore.load(prefs, now + 2000)
        assertEquals(2, entries.size)
        // Pass synthetic nowMs so the internal load() inside remove() uses the same
        // clock as the test — without this, System.currentTimeMillis() (~2026) is
        // years past the hardcoded 2023 timestamps, TTL evicts everything, and
        // remove() silently no-ops while the assertion still sees 2 entries.
        BlockHistoryStore.remove(prefs, entries[0], nowMs = now + 2000)
        val afterRemove = BlockHistoryStore.load(prefs, now + 2000)
        assertEquals(1, afterRemove.size)
        assertEquals(entries[1], afterRemove[0])
    }

    @Test
    fun `stored entry never contains plaintext full number`() {
        val now = 1_700_000_000_000L
        val number = "09012345678"
        BlockHistoryStore.record(prefs, number, BlockReason.SPAM_CACHE, now)
        // Read the raw SharedPreferences string — the full number must not appear.
        val raw = prefs.getString("block_history", "") ?: ""
        assertFalse("full number must not be stored in prefs", raw.contains(number))
        // The stored representation must only contain the last-4-digit mask.
        assertTrue("masked form must be present", raw.contains("5678"))
        assertFalse("digits 0–6 of number must not appear verbatim", raw.contains("090123"))
    }

    @Test
    fun `remove of absent entry is a no-op`() {
        val now = 1_700_000_000_000L
        BlockHistoryStore.record(prefs, "09012345678", BlockReason.SPAM_CACHE, now)
        val phantom = BlockHistoryStore.Entry("****9999", now, BlockReason.FOREIGN_GENERIC)
        BlockHistoryStore.remove(prefs, phantom, nowMs = now + 1000)
        val entries = BlockHistoryStore.load(prefs, now + 1000)
        assertEquals(1, entries.size)
    }
}

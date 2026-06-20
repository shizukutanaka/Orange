package com.orange.apple

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Lightweight block history for false-positive recovery.
 *
 * SpamCache stores hashes (privacy-preserving, permanent). This store
 * is different: it keeps the last MAX_ENTRIES blocks with masked numbers
 * and timestamps so the user can review and restore false positives via
 * HistoryActivity.
 *
 * Privacy trade-offs (deliberate):
 * - Numbers are masked: last 4 digits only ("****1234"). Full number is
 *   NOT stored — the restore action uses the hash to clear the spam cache,
 *   not plaintext. This means history is display-only; it cannot be used
 *   to reconstruct caller identity.
 * - TTL: entries older than 30 days are dropped on next access.
 * - Bound: MAX_ENTRIES = 50. Oldest entries are evicted first.
 */
internal object BlockHistoryStore {

    const val MAX_ENTRIES = 50
    private const val TTL_MS = 30L * 24 * 60 * 60 * 1000
    private const val KEY = "block_history"

    data class Entry(
        val maskedNumber: String,
        val timestampMs: Long,
        val reason: BlockReason,
    )

    @Synchronized
    fun record(prefs: SharedPreferences, number: String, reason: BlockReason, nowMs: Long) {
        val entries = load(prefs, nowMs).toMutableList()
        entries.add(0, Entry(PhoneNumbers.mask(number), nowMs, reason))
        if (entries.size > MAX_ENTRIES) entries.subList(MAX_ENTRIES, entries.size).clear()
        save(prefs, entries)
    }

    @Synchronized
    fun load(prefs: SharedPreferences, nowMs: Long): List<Entry> {
        val raw = prefs.getString(KEY, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split('\n')
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size != 3) return@mapNotNull null
                val ts = parts[1].toLongOrNull() ?: return@mapNotNull null
                if (nowMs < ts || nowMs - ts > TTL_MS) return@mapNotNull null
                val reason = runCatching { BlockReason.valueOf(parts[2]) }.getOrNull()
                    ?: return@mapNotNull null
                Entry(parts[0], ts, reason)
            }
    }

    @Synchronized
    fun remove(prefs: SharedPreferences, entry: Entry, nowMs: Long = System.currentTimeMillis()) {
        // nowMs defaults to wall clock for production callers; inject in tests so hardcoded
        // historical timestamps are not evicted by the TTL check inside load().
        val entries = load(prefs, nowMs).toMutableList()
        if (entries.remove(entry)) save(prefs, entries)
    }

    private fun save(prefs: SharedPreferences, entries: List<Entry>) {
        val raw = entries.joinToString("\n") { "${it.maskedNumber}\t${it.timestampMs}\t${it.reason}" }
        prefs.edit { putString(KEY, raw) }
    }

}

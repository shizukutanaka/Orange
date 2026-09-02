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
 * - Numbers are masked: last 4 digits only ("****1234"). Neither the full
 *   number NOR its hash is stored, so history is display-only and cannot
 *   reconstruct caller identity.
 *
 *   That has a direct consequence for recovery, and the two undo paths are
 *   not equivalent because of it (see FEATURE_AUDIT §2-2):
 *     - Restore (from the per-block notification) has the full number in the
 *       Intent, so it can hash it and clear the exact SpamCache entry.
 *     - Allow (from HistoryActivity) has only what is stored here — the
 *       masked suffix — so it cannot address a SpamCache entry at all. It
 *       instead writes the 4-digit suffix to AllowSuffixStore, which
 *       SilentBlockerService checks before the engine runs.
 *   Storing a hash here would make Allow exact, at the cost of this
 *   display-only property. That trade is recorded in §2-2, not taken.
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
                if (nowMs < ts || nowMs - ts >= TTL_MS) return@mapNotNull null
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

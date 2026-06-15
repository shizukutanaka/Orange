package com.orange.apple

import android.content.SharedPreferences
import androidx.core.content.edit
import java.security.MessageDigest

/**
 * Bounded, hashed spam cache with LRU eviction.
 *
 * PRIVACY DESIGN (arXiv:2304.02810, on-device blocklisting):
 * Numbers the user has blocked are sensitive — a blocklist reveals who the
 * user refuses to talk to. Storing them in plaintext means malware with
 * SharedPreferences access, or a forensic image of the device, can read the
 * user's block history. We instead store SHA-256 hashes: membership queries
 * still work (hash the incoming number, check set membership), but the
 * plaintext numbers never touch disk. This satisfies CLAUDE.md I5 (PII
 * minimisation — hash when storage is necessary).
 *
 * A phone number is low-entropy (≈10^10 JP numbers), so a determined attacker
 * with the on-disk hash set could brute-force it. This is NOT defense against
 * a global adversary; it is defense-in-depth that removes a trivially-readable
 * plaintext PII store. For Orange's threat model (casual malware, lost device,
 * backup leakage) it is a meaningful improvement at near-zero cost.
 *
 * The cache is bounded at MAX_ENTRIES with FIFO eviction; everything that can
 * grow is bounded (Carmack rule).
 */
internal object SpamCache {

    const val MAX_ENTRIES = 10_000

    private const val KEY_SET = SilentBlockerService.KEY_SPAM
    private const val KEY_ORDER = "spam_order"  // space-separated insertion order of hashes

    /**
     * Per-install salt. Delegated to SaltVault, which encrypts it with an
     * Android Keystore key (non-exportable, hardware-backed where available)
     * so a forensic image of /data cannot recover it. See ADR 006 / KeyDroid
     * (arXiv:2507.07927). Falls back to plaintext on non-Android test hosts.
     */
    @Synchronized
    private fun salt(prefs: SharedPreferences): String = SaltVault.salt(prefs)

    /** Salted SHA-256 hex of a normalized phone number. */
    internal fun hash(prefs: SharedPreferences, number: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest((salt(prefs) + number).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** Set of stored salted hashes. */
    @Synchronized
    fun snapshot(prefs: SharedPreferences): Set<String> =
        prefs.getStringSet(KEY_SET, emptySet()) ?: emptySet()

    /** True if [number] is in the cache (salted-hashes internally). */
    @Synchronized
    fun contains(prefs: SharedPreferences, number: String): Boolean =
        number.isNotEmpty() && hash(prefs, number) in snapshot(prefs)

    /**
     * Add a number (stored as its hash). Returns true if newly added.
     * FIFO eviction once MAX_ENTRIES is exceeded.
     */
    @Synchronized
    fun add(prefs: SharedPreferences, number: String): Boolean {
        if (number.isEmpty()) return false
        val h = hash(prefs, number)
        val current = prefs.getStringSet(KEY_SET, emptySet()).orEmpty().toMutableSet()
        if (h in current) return false

        val order = orderList(prefs)
        order.addLast(h)
        current.add(h)

        while (order.size > MAX_ENTRIES) {
            val oldest = order.removeFirst()
            current.remove(oldest)
        }

        prefs.edit {
            putStringSet(KEY_SET, current)
            putString(KEY_ORDER, order.joinToString(" "))
        }
        return true
    }

    /** Remove a number (used by RestoreReceiver). Returns true if present. */
    @Synchronized
    fun remove(prefs: SharedPreferences, number: String): Boolean {
        if (number.isEmpty()) return false
        val h = hash(prefs, number)
        val current = prefs.getStringSet(KEY_SET, emptySet()).orEmpty().toMutableSet()
        if (!current.remove(h)) return false
        val order = orderList(prefs)
        order.remove(h)
        prefs.edit {
            putStringSet(KEY_SET, current)
            putString(KEY_ORDER, order.joinToString(" "))
        }
        return true
    }

    private fun orderList(prefs: SharedPreferences): ArrayDeque<String> {
        val raw = prefs.getString(KEY_ORDER, "") ?: ""
        if (raw.isEmpty()) return ArrayDeque()
        // Cross-reference with KEY_SET to drop any stale hashes that survived a partial
        // write (e.g., process kill between putStringSet and putString on an older OS).
        val known = prefs.getStringSet(KEY_SET, emptySet()).orEmpty()
        return ArrayDeque(raw.split(' ').filter { it.isNotBlank() && it in known })
    }
}

package com.orange.apple

import android.content.SharedPreferences
import androidx.core.content.edit
import java.security.MessageDigest

/**
 * Bounded, hashed spam cache with FIFO eviction.
 * (FIFO, not LRU: a re-blocked number's position in the eviction order is NOT
 * refreshed — add() returns early when the hash is already present. At
 * MAX_ENTRIES = 10,000 the distinction is inconsequential for real call
 * volumes, but the label should match what the code does.)
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
     *
     * Cached in memory after first load: the salt is generated once at install
     * and never changes, so a Keystore round-trip (10–50 ms) on every incoming
     * call — which is in Android's latency-sensitive screening callback — is
     * unnecessary.
     *
     * Cache is keyed by the plaintext prefs sentinel so that JVM unit tests
     * using separate FakePrefs instances each get their own salt (each FakePrefs
     * has a distinct KEY_PLAIN_SALT value after its first SaltVault.salt() call).
     * On a real device there is only one SharedPreferences instance per app,
     * so the cache key never changes after first initialization.
     */
    @Volatile private var cachedSalt: String? = null
    @Volatile private var cachedSaltKey: String? = null  // prefs sentinel used to populate cache

    @Synchronized
    private fun salt(prefs: SharedPreferences): String {
        // Read the prefs sentinel that identifies which install this salt belongs to.
        // SaltVault stores plaintext fallback under "spam_salt"; encrypted form under "spam_salt_enc".
        val prefsKey = prefs.getString("spam_salt", null) ?: prefs.getString("spam_salt_enc", null)
        if (cachedSalt != null && prefsKey != null && prefsKey == cachedSaltKey) return cachedSalt!!
        return SaltVault.salt(prefs).also { s ->
            cachedSalt = s
            cachedSaltKey = prefs.getString("spam_salt", null) ?: prefs.getString("spam_salt_enc", null)
        }
    }

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

    /**
     * True if [number] is in the cache (salted-hashes internally).
     *
     * Prunes expired entries first, so an expired judgement never produces a
     * hit and the file does not accumulate dead entries. Pruning is a no-op
     * (read-only) unless something actually expired.
     */
    @Synchronized
    @JvmOverloads
    fun contains(
        prefs: SharedPreferences,
        number: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (number.isEmpty()) return false
        pruneExpired(prefs, nowMs)
        return hash(prefs, number) in snapshot(prefs)
    }

    /**
     * Default lifetime for an entry added with [expiring] = true.
     *
     * 180 days. Chosen to sit above the realistic number-reassignment window
     * without being so long that a stale judgement outlives its usefulness:
     * JP carriers have been reported reusing cancelled numbers in as little as a
     * few months, and 総務省 targets ~3 years for genuinely unused ranges. Six
     * months keeps the fast path effective for a scammer who keeps working a
     * number, while guaranteeing that a number they abandoned stops being
     * silenced for its next owner. See docs/FEATURE_AUDIT.md §1-8.
     */
    const val DEFAULT_TTL_MS = 180L * 24 * 60 * 60 * 1000

    /**
     * Add a number (stored as its hash). Returns true if newly added.
     * FIFO eviction once MAX_ENTRIES is exceeded.
     *
     * @param expiring when true the entry is written with an expiry stamp of
     *   [nowMs] + [ttlMs] and stops matching after that; when false (the
     *   default) it is permanent, exactly as before. Callers decide via
     *   [isExpiringSilence]; entries created from explicit user intent stay
     *   permanent.
     */
    @Synchronized
    @JvmOverloads
    fun add(
        prefs: SharedPreferences,
        number: String,
        expiring: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
        ttlMs: Long = DEFAULT_TTL_MS,
    ): Boolean {
        if (number.isEmpty()) return false
        val h = hash(prefs, number)
        val current = prefs.getStringSet(KEY_SET, emptySet()).orEmpty().toMutableSet()
        if (h in current) return false

        val order = orderList(prefs, nowMs)
        order.addLast(if (expiring) "$h$EXPIRY_SEP${nowMs + ttlMs}" else h)
        current.add(h)

        while (order.size > MAX_ENTRIES) {
            val oldest = order.removeFirst()
            current.remove(oldest.substringBefore(EXPIRY_SEP))
        }

        prefs.edit {
            putStringSet(KEY_SET, current)
            putString(KEY_ORDER, order.joinToString(" "))
        }
        return true
    }

    /** Remove a number (used by RestoreReceiver). Returns true if present. */
    @Synchronized
    @JvmOverloads
    fun remove(
        prefs: SharedPreferences,
        number: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (number.isEmpty()) return false
        val h = hash(prefs, number)
        val current = prefs.getStringSet(KEY_SET, emptySet()).orEmpty().toMutableSet()
        if (!current.remove(h)) return false
        val order = orderList(prefs, nowMs)
        // Match on the hash portion: the token may carry an expiry suffix.
        order.removeAll { hashOf(it) == h }
        prefs.edit {
            putStringSet(KEY_SET, current)
            putString(KEY_ORDER, order.joinToString(" "))
        }
        return true
    }

    /**
     * Entries are stored in KEY_ORDER as either `hash` (permanent, the original
     * format) or `hash|expiryEpochMs`. Reading tolerates both, so an install
     * that predates expiry support keeps working and its existing entries stay
     * permanent — no migration step, no data loss.
     */
    private const val EXPIRY_SEP = '|'

    /** Hash portion of a KEY_ORDER token, with or without an expiry suffix. */
    private fun hashOf(token: String): String = token.substringBefore(EXPIRY_SEP)

    /**
     * True if [token] carries an expiry stamp that is at or before [nowMs].
     * A backward clock (nowMs < stamp) simply means "not yet expired", which is
     * the safe direction: we keep silencing rather than suddenly un-blocking a
     * scammer because the clock moved.
     */
    private fun isExpired(token: String, nowMs: Long): Boolean {
        val sep = token.indexOf(EXPIRY_SEP)
        if (sep < 0) return false                       // permanent entry
        val stamp = token.substring(sep + 1).toLongOrNull() ?: return false
        return nowMs >= stamp
    }

    private fun orderList(prefs: SharedPreferences, nowMs: Long): ArrayDeque<String> {
        val raw = prefs.getString(KEY_ORDER, "") ?: ""
        if (raw.isEmpty()) return ArrayDeque()
        // Cross-reference with KEY_SET to drop any stale hashes that survived a partial
        // write (e.g., process kill between putStringSet and putString on an older OS).
        val known = prefs.getStringSet(KEY_SET, emptySet()).orEmpty()
        return ArrayDeque(
            raw.split(' ').filter {
                it.isNotBlank() && hashOf(it) in known && !isExpired(it, nowMs)
            }
        )
    }

    /**
     * Drop expired entries from both KEY_SET and KEY_ORDER. Called from
     * [contains] on the screening hot path, which is also the only place that
     * reliably runs often enough to keep the file from carrying dead weight.
     * Writes only when something actually expired, so the common case is a
     * read-only pass.
     */
    @Synchronized
    private fun pruneExpired(prefs: SharedPreferences, nowMs: Long) {
        val raw = prefs.getString(KEY_ORDER, "") ?: ""
        if (raw.isEmpty() || EXPIRY_SEP !in raw) return  // nothing can expire
        val tokens = raw.split(' ').filter { it.isNotBlank() }
        val live = tokens.filterNot { isExpired(it, nowMs) }
        if (live.size == tokens.size) return             // nothing expired
        val liveHashes = live.map { hashOf(it) }.toMutableSet()
        prefs.edit {
            putStringSet(KEY_SET, liveHashes)
            putString(KEY_ORDER, live.joinToString(" "))
        }
    }
}

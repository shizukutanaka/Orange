package com.orange.apple

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * Cold-start optimization via a ContentProvider initializer pattern.
 *
 * Problem (audit M2): CallScreeningService has a 5-second response deadline.
 * On low-end Android Go devices (BASIO / らくらくスマートフォン), first-call
 * cold start that synchronously loads business_directory.csv + police station
 * directory can exceed 5s → call auto-rings without screening.
 *
 * Fix: this zero-authority ContentProvider runs at Application.onCreate() time
 * (before any service bind). It pre-warms both in-memory caches so the first
 * onScreenCall() is a pure in-memory lookup.
 *
 * Why ContentProvider instead of Application subclass or AndroidX Startup:
 *   - No Application subclass needed → one fewer class to maintain
 *   - No Jetpack dependency added → keeps dependency count at Compose + Core KTX
 *   - ContentProvider.onCreate() is guaranteed to run before any Service.bind()
 *   - Standard pattern used by major Android libraries internally
 *
 * Cost: ~50ms on modern hardware, ~200ms on Android Go. Acceptable because
 * app launch is already a one-time event (the app self-destructs from the
 * task list after onboarding).
 */
class EngineWarmup : ContentProvider() {

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        // Pre-load caches. Both are lazy-init singletons; calling load() here
        // ensures subsequent reads are O(1) HashMap lookups.
        BusinessDirectoryBundle.load(ctx)
        // PoliceStationDirectory and TaxAgencyDirectory are static Maps — already
        // initialized. Touch both here to force class loading at app start, not
        // at first call (TaxAgencyDirectory was added after this warmup path and
        // was missed the first time; Layer 9b in CallDecision.kt depends on it
        // being loaded just as much as Layer 9 depends on PoliceStationDirectory).
        PoliceStationDirectory.entries.size
        TaxAgencyDirectory.entries.size

        // Warm the two remaining synchronous costs on the screening path
        // (FEATURE_AUDIT §1-10). Neither was covered above, so the first call
        // after every process start was still paying them inside the 5-second
        // window:
        //
        //  1. The Keystore round-trip. SpamCache caches the decrypted salt in
        //     memory, but that cache dies with the process, so the first
        //     SpamCache.hash() after a cold start goes to SaltVault ->
        //     AndroidKeyStore + an AES-GCM decrypt. SpamCache's own KDoc puts
        //     this at 10-50 ms and calls paying it per-call unnecessary — yet
        //     the first call did pay it.
        //  2. The SharedPreferences load, which parses the whole XML file
        //     synchronously on first touch. That file holds the spam cache and
        //     outbound set, so it is the largest thing Orange reads.
        //
        // hash("") is a real hash of the empty string: it forces both the prefs
        // load and the salt decrypt, writes nothing, and cannot collide with a
        // stored entry because callers reject empty numbers before hashing.
        //
        // Wrapped because a corrupted or unavailable Keystore must not stop app
        // startup — SaltVault already degrades to a plaintext salt, and warmup
        // is an optimization, never a correctness requirement.
        try {
            SpamCache.hash(ctx.getSharedPreferences(
                SilentBlockerService.PREFS, android.content.Context.MODE_PRIVATE), "")
        } catch (_: Throwable) {
            // Warmup is best-effort; the first call will pay the cost instead.
        }
        return true
    }

    // Required overrides — this provider serves no content.
    override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun getType(u: Uri): String? = null
    override fun insert(u: Uri, v: ContentValues?): Uri? = null
    override fun delete(u: Uri, s: String?, a: Array<String>?): Int = 0
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<String>?): Int = 0
}

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
        val ctx = context ?: return true
        // Pre-load caches. Both are lazy-init singletons; calling load() here
        // ensures subsequent reads are O(1) HashMap lookups.
        BusinessDirectoryBundle.load(ctx)
        // PoliceStationDirectory is a static Map — already initialized.
        // Touch it here to force class loading at app start, not at first call.
        PoliceStationDirectory.entries.size
        return true
    }

    // Required overrides — this provider serves no content.
    override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun getType(u: Uri): String? = null
    override fun insert(u: Uri, v: ContentValues?): Uri? = null
    override fun delete(u: Uri, s: String?, a: Array<String>?): Int = 0
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<String>?): Int = 0
}

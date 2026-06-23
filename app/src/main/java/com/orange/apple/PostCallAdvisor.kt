package com.orange.apple

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.content.edit

/**
 * Post-call safety advisor.
 *
 * After answering an unknown number for >30 seconds, show a low-priority
 * notification with official JP reporting/advice hotlines:
 *
 *   警察相談    #9110
 *   消費者      188
 *   国際電話不取扱受付  0120-210-364
 *   でんわんセンター    03-6162-1111 (weekdays 10:00-17:00)
 *
 * This operationalizes the consistent advice from 警察庁・国民生活センター:
 *   「一旦電話を切り、ご家族や警察（#9110）に相談を」
 *
 * Design:
 *   - LOW priority (no sound, no vibration, doesn't interrupt the user)
 *   - Auto-cancel on tap
 *   - One notification per number per 24 hours (rate-limited)
 *   - Only fires if the number was NOT in outbound-known or business-bundle
 *     (those are trusted callers; advice is not needed)
 *
 * Called from CallStateObserver.onIdle() when:
 *   - The call was answered (OFFHOOK was observed)
 *   - Duration > THRESHOLD_MS (30 seconds)
 *   - The number was unknown (not outbound-known, not in business bundle)
 */
object PostCallAdvisor {

    const val THRESHOLD_MS = 30_000L
    private const val CHANNEL = "orange_postcall"
    private const val WINDOW_MS = 24L * 60 * 60 * 1000

    @Synchronized
    fun maybeShow(ctx: Context, number: String, durationMs: Long) {
        if (durationMs < THRESHOLD_MS) return
        if (number.isEmpty()) return

        val prefs = ctx.getSharedPreferences(SilentBlockerService.PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()

        // Prune stale rate-limit keys to prevent unbounded SharedPreferences growth.
        // Each unique unknown call answered for >30s writes one "postcall_last_*" key.
        // Without pruning, the prefs file grows indefinitely. Drop any such key whose
        // 24h window has already expired — it will be re-created if needed.
        pruneStaleRateKeys(prefs, now)

        // Rate-limit key: prefix + 16-hex hash of the number.  Raw number in the key name
        // would expose PII to backup/forensic readers; hash is sufficient for dedup.
        val rateKey = "postcall_last_${SpamCache.hash(prefs, number).take(16)}"
        val lastShown = prefs.getLong(rateKey, 0L)
        // Rate-limit: suppress if shown within the last 24h.
        // Guard now >= lastShown before subtracting to prevent a backward-clock
        // scenario (now < lastShown) from bypassing the rate-limit entirely.
        if (lastShown > 0L && (now < lastShown || now - lastShown < WINDOW_MS)) return

        // Only fire for genuinely unknown callers. Trusted callers — numbers the
        // user dialed, registered family members, or bundled legitimate businesses
        // — never need a scam-advice sheet; showing one would be a false alarm on
        // grandma's bank or her own daughter. Check every domestic↔E.164 variant
        // so a callback delivered in E.164 still matches a domestic-stored number.
        val cc = simCallingCode(ctx)
        val variants = phoneVariants(number, cc)
        val outbound = prefs.getStringSet(SilentBlockerService.KEY_OUTBOUND, emptySet()).orEmpty()
        val family = FamilyCallback.getNumbers(ctx).map { PhoneNumbers.normalize(it) }.toSet()
        val businesses = BusinessDirectoryBundle.load(ctx).keys
        if (variants.any { SpamCache.hash(prefs, it) in outbound || it in family || it in businesses }) return

        prefs.edit { putLong(rateKey, now) }
        show(ctx, number)
    }

    /**
     * Home-country ITU calling code from the SIM, for E.164 variant matching.
     * On dual-SIM devices this returns the default (SIM1) slot's country only.
     * A number stored in domestic form via SIM2's country may not match its
     * E.164 variant — accepted limitation; exact-number restore via block history
     * remains available.
     */
    private fun simCallingCode(ctx: Context): String? {
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE)
            as? android.telephony.TelephonyManager ?: return null
        val iso = tm.simCountryIso?.takeIf { it.isNotEmpty() }
            ?: tm.networkCountryIso?.takeIf { it.isNotEmpty() }
        return callingCodeOf(iso?.uppercase(java.util.Locale.ROOT))
    }

    internal fun pruneStaleRateKeys(prefs: android.content.SharedPreferences, now: Long) {
        val stale = prefs.all.entries
            // Guard now >= ts before subtracting: a backward clock jump makes
            // now - ts negative which wraps to a huge Long, incorrectly pruning
            // a rate-limit key that should still be in effect.
            .filter { (k, v) -> k.startsWith("postcall_last_") && v is Long && now >= (v as Long) && now - v >= WINDOW_MS }
            .map { it.key }
        if (stale.isNotEmpty()) {
            prefs.edit { stale.forEach { remove(it) } }
        }
    }

    private fun show(ctx: Context, number: String) {
        val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        if (mgr.getNotificationChannel(CHANNEL) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    ctx.getString(R.string.notif_channel_postcall),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                }
            )
        }

        // Primary action: dial #9110 (police consultation line)
        val dial9110 = PendingIntent.getActivity(
            ctx, 0,
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:%239110")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(ctx.getString(R.string.postcall_title))
            .setContentText(ctx.getString(R.string.postcall_body))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(ctx.getString(R.string.postcall_body_big))
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(dial9110)
            .addAction(0, ctx.getString(R.string.postcall_action_9110), dial9110)
            .build()

        mgr.notify(TrustNotifier.notifIdFor(number) xor 0x09110, notif)
    }
}

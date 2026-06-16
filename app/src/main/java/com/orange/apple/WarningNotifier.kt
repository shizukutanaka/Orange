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
 * Centralized warning notification builder.
 *
 * Extracted from SilentBlockerService (which was 240 LOC and growing).
 * Carmack rule: the screening service should decide and dispatch;
 * the notification builder should format and display. Mixing them
 * couples Android UI details into a latency-sensitive callback.
 *
 * All notifications follow the same pattern:
 *   - High-priority heads-up
 *   - Optional "家族に連絡" action (if FamilyCallback is configured)
 *   - Auto-cancel
 */
internal object WarningNotifier {

    private const val CHANNEL_POLICE = "orange_police_warn"
    private const val CHANNEL_HIGHRISK = "orange_highrisk"
    private const val CHANNEL_OUTBOUND = "orange_outbound_warn"

    /** Police HQ impersonation warning. Call RINGS but user sees heads-up.
     *  @param highSeverity true when STIR/SHAKEN also reports FAILED — escalated 🚨 alert. */
    fun showPoliceWarning(ctx: Context, number: String, hqName: String, highSeverity: Boolean = false) {
        val mgr = notifManager(ctx) ?: return
        ensureChannel(ctx, mgr, CHANNEL_POLICE,
            ctx.getString(R.string.notif_channel_police_warn),
            NotificationManager.IMPORTANCE_HIGH)

        val title = if (highSeverity)
            ctx.getString(R.string.police_warn_title_high, hqName)
        else
            ctx.getString(R.string.police_warn_title, hqName)

        val builder = NotificationCompat.Builder(ctx, CHANNEL_POLICE)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(ctx.getString(R.string.police_warn_body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)

        addFamilyAction(ctx, builder)
        mgr.notify(TrustNotifier.notifIdFor(number) xor 0x0BADE, builder.build())
    }

    /** Soft warning for unknown domestic mobile during アポ電 peak hours. */
    fun showHighRiskHourWarning(ctx: Context, number: String) {
        // Rate-limit: once per 24 h per number — same philosophy as PostCallAdvisor.
        val prefs = ctx.getSharedPreferences(SilentBlockerService.PREFS, Context.MODE_PRIVATE)
        val key = "highrisk_last_$number"
        val now = System.currentTimeMillis()
        val last = prefs.getLong(key, 0L)
        if (now >= last && now - last < 24L * 60 * 60 * 1000) return
        prefs.edit { putLong(key, now) }  // putLong overwrites in place — no need to remove first

        val mgr = notifManager(ctx) ?: return
        ensureChannel(ctx, mgr, CHANNEL_HIGHRISK,
            ctx.getString(R.string.notif_channel_highrisk),
            NotificationManager.IMPORTANCE_DEFAULT)

        val builder = NotificationCompat.Builder(ctx, CHANNEL_HIGHRISK)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(ctx.getString(R.string.highrisk_warn_title))
            .setContentText(ctx.getString(R.string.highrisk_warn_body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        addFamilyAction(ctx, builder)
        mgr.notify(TrustNotifier.notifIdFor(number) xor 0x0A70E, builder.build())
    }

    /**
     * Warning when user dials a recently-blocked number.
     *
     * Deduplicated per-number with a 1-hour window: the OutboundGuard window is
     * 24 hours, meaning repeated dials to the same number within a day would
     * generate a new heads-up notification each time. One warning per hour is
     * enough — the user has already been told.
     */
    fun showOutboundWarning(ctx: Context, number: String) {
        val prefs = ctx.getSharedPreferences(SilentBlockerService.PREFS, Context.MODE_PRIVATE)
        // Key includes the full number so numbers that differ only in early digits don't
        // share a rate-limit bucket. takeLast(8) caused collisions (e.g., "+8190123456789"
        // and "+8190987656789" both end in "56789..." — different numbers, same key).
        val rateKey = "outbound_warn_ts_$number"
        val now = System.currentTimeMillis()
        val last = prefs.getLong(rateKey, 0L)
        if (now >= last && now - last < OUTBOUND_WARN_WINDOW_MS) return
        // Lazy cleanup: expired key removed so numbers that are never dialled again
        // don't leave stale "outbound_warn_ts_*" keys accumulating in prefs.
        if (last > 0L) prefs.edit { remove(rateKey) }
        prefs.edit { putLong(rateKey, now) }

        val mgr = notifManager(ctx) ?: return
        ensureChannel(ctx, mgr, CHANNEL_OUTBOUND,
            ctx.getString(R.string.notif_channel_outbound_warn),
            NotificationManager.IMPORTANCE_HIGH)

        val builder = NotificationCompat.Builder(ctx, CHANNEL_OUTBOUND)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(ctx.getString(R.string.outbound_warn_title))
            .setContentText(ctx.getString(R.string.outbound_warn_body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        addFamilyAction(ctx, builder)
        mgr.notify(TrustNotifier.notifIdFor(number) xor 0x0CAFE, builder.build())
    }

    private const val OUTBOUND_WARN_WINDOW_MS = 60L * 60 * 1000  // 1 hour dedup window

    private fun addFamilyAction(ctx: Context, builder: NotificationCompat.Builder) {
        val familyNum = FamilyCallback.primaryNumber(ctx) ?: return
        val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$familyNum"))
        val pi = PendingIntent.getActivity(
            ctx, 0x0FAB1, dialIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(0, ctx.getString(R.string.police_warn_family), pi)
    }

    private fun ensureChannel(
        ctx: Context, mgr: NotificationManager,
        id: String, name: String, importance: Int
    ) {
        if (mgr.getNotificationChannel(id) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(id, name, importance).apply {
                    enableVibration(true)
                    setSound(null, null)
                }
            )
        }
    }

    private fun notifManager(ctx: Context): NotificationManager? =
        ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
}

package com.orange.apple

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.edit

/**
 * Apple philosophy: trust is earned, then invisible.
 *
 * Days 1–7: every block shows a silent notification with one action — "Restore."
 *           Tap = number added to outbound-known set, future calls allowed.
 * Day 8+:   minimal drawer-only notification (no sound, no heads-up) with
 *           "Restore" action; collapses into a group summary after 3 blocks.
 *           A weekly digest fires Sunday 09:00 local: "Orange silenced 12
 *           unwanted calls this week." After 8 weeks, switches to monthly
 *           (first Sunday of each month) — never stops, because fraud
 *           awareness decays in 6–8 week cycles (LY Corp 2023).
 *
 * This mirrors how AirPods pair: flashy the first time, invisible after.
 *
 * Prior version had:
 *  - Per-block expandable notification with country flag, confidence %, block reason
 *  - "Report to community" button
 *  - Sound + vibration options in settings
 * All of that undermined the core promise ("your phone stops ringing").
 * A blocked call that still notifies you is just a quieter ring.
 */
object TrustNotifier {

    private const val CHANNEL_TRUST = "orange_trust"
    private const val CHANNEL_ONGOING = "orange_ongoing"
    const val TRUST_PERIOD_MS = 7L * 24 * 60 * 60 * 1000
    const val KEY_INSTALL_TS = "install_ts"

    fun maybeNotify(ctx: Context, blockedNumber: String) {
        val prefs = ctx.getSharedPreferences(SilentBlockerService.PREFS, Context.MODE_PRIVATE)
        val installTs = prefs.getLong(KEY_INSTALL_TS, 0L).let {
            if (it == 0L) System.currentTimeMillis().also { now ->
                prefs.edit { putLong(KEY_INSTALL_TS, now) }
            } else it
        }

        val withinTrustWindow = System.currentTimeMillis() - installTs < TRUST_PERIOD_MS
        if (!withinTrustWindow) {
            // After the trust window: show a minimal background notification with
            // a "Restore" action so users can still recover false positives.
            // No sound, no vibration, no heads-up — just drawer presence.
            maybeNotifyPostTrust(ctx, blockedNumber)
            return
        }

        ensureChannel(ctx, CHANNEL_TRUST, ctx.getString(R.string.notif_channel_trust),
            NotificationManager.IMPORTANCE_LOW)

        val restoreIntent = Intent(ctx, RestoreReceiver::class.java).apply {
            putExtra(EXTRA_NUMBER, blockedNumber)
        }
        val restorePi = PendingIntent.getBroadcast(
            ctx, blockedNumber.hashCode(), restoreIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(ctx, CHANNEL_TRUST)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call_forward)
            .setContentTitle(ctx.getString(R.string.notif_title))
            .setContentText(mask(blockedNumber))
            .addAction(0, ctx.getString(R.string.notif_action_restore), restorePi)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        mgr.notify(blockedNumber.hashCode(), notif)
    }

    /**
     * Post-trust-window block notification.
     * Silent, no heads-up. Collapses into a single "N calls silenced — tap to review" summary
     * after 3 blocks to avoid notification spam. Always has a "Restore" action and a
     * "Review history" content-tap that opens HistoryActivity.
     */
    private fun maybeNotifyPostTrust(ctx: Context, blockedNumber: String) {
        ensureChannel(ctx, CHANNEL_ONGOING, ctx.getString(R.string.notif_channel_ongoing),
            NotificationManager.IMPORTANCE_MIN)

        val historyIntent = Intent(ctx, HistoryActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val historyPi = PendingIntent.getActivity(
            ctx, 0x015700, historyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val restoreIntent = Intent(ctx, RestoreReceiver::class.java).apply {
            putExtra(EXTRA_NUMBER, blockedNumber)
        }
        val restorePi = PendingIntent.getBroadcast(
            ctx, blockedNumber.hashCode(), restoreIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(ctx, CHANNEL_ONGOING)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call_forward)
            .setContentTitle(ctx.getString(R.string.notif_title))
            .setContentText(mask(blockedNumber))
            .setContentIntent(historyPi)
            .addAction(0, ctx.getString(R.string.notif_action_restore), restorePi)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setGroup("orange_blocks")
            .build()

        val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        mgr.notify(blockedNumber.hashCode(), notif)
        postGroupSummary(ctx, mgr, historyPi)
    }

    /**
     * Android requires an explicit group-summary notification for the group to
     * visually collapse in the notification shade. Without it, setGroup() is a
     * no-op from the user's perspective — each block appears as a separate card.
     * This posts (or updates) a summary every time a new post-trust block fires.
     */
    private fun postGroupSummary(
        ctx: Context,
        mgr: NotificationManager,
        historyPi: PendingIntent
    ) {
        // notification.group is the raw group key without Android's package prefix
        // (groupKey includes the package name, making endsWith() fragile).
        val count = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            mgr.activeNotifications.count { it.notification.group == "orange_blocks" }
        } else 0
        val summaryText = ctx.getString(R.string.notif_summary_text, count.coerceAtLeast(1))
        val summary = NotificationCompat.Builder(ctx, CHANNEL_ONGOING)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call_forward)
            .setContentTitle(ctx.getString(R.string.app_name))
            .setContentText(summaryText)
            .setContentIntent(historyPi)
            .setAutoCancel(false)
            .setGroup("orange_blocks")
            .setGroupSummary(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        mgr.notify(NOTIF_ID_SUMMARY, summary)
    }

    private fun mask(n: String): String =
        if (n.length <= 4) "****" else "****" + n.takeLast(4)

    private fun ensureChannel(ctx: Context, id: String, name: String, importance: Int) {
        val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (mgr.getNotificationChannel(id) == null) {
            mgr.createNotificationChannel(NotificationChannel(id, name, importance).apply {
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            })
        }
    }

    const val EXTRA_NUMBER = "num"
    private const val NOTIF_ID_SUMMARY = 0x0B10C5  // "BLOCKS" mnemonic
}

/**
 * Restore action: user says "that wasn't spam."
 * We don't argue. We don't ask for feedback. We just trust them forever.
 * Number moves into the outbound-known set so it always rings going forward.
 */
class RestoreReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val n = intent.getStringExtra(TrustNotifier.EXTRA_NUMBER) ?: return
        val prefs = ctx.getSharedPreferences(SilentBlockerService.PREFS, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(SilentBlockerService.KEY_OUTBOUND, emptySet()).orEmpty().toMutableSet()
        set.add(n)
        prefs.edit { putStringSet(SilentBlockerService.KEY_OUTBOUND, set) }
        // SpamCache stores salted SHA-256 hashes — remove via the cache API so the
        // hash (not the plaintext string) is actually erased. Direct KEY_SPAM removal
        // of the plaintext would always be a no-op and leave Layer 6 permanently armed.
        SpamCache.remove(prefs, n)
        // Clear the OutboundGuard entry: a Restore is explicit trust, so the
        // outbound-warning ("recently flagged") must not fire when the user calls back.
        OutboundGuard.forget(prefs, n, System.currentTimeMillis())
        val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        mgr.cancel(n.hashCode())

        // Confirmation toast — closes the loop competitors (Hiya, Whoscall)
        // leave open. User tapped Restore; they should see the receipt.
        android.widget.Toast.makeText(
            ctx,
            ctx.getString(R.string.toast_restored),
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }
}

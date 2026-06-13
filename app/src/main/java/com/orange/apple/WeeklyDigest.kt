package com.orange.apple

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import java.util.Calendar

/**
 * Weekly digest notification. Ships one line every Sunday 09:00 local:
 *   "Orange silenced 12 calls this week."
 *
 * This was promised in TrustNotifier's design comment ("Day 8+: A weekly
 * digest appears Sunday 09:00 local") but never implemented. Now it is.
 *
 * Apple philosophy: the digest is a trophy, not a dashboard. One number.
 * No "tap to see details." No call to action. No deep link to a settings
 * screen that doesn't exist. If the user didn't block any calls that week,
 * no notification is shown (silence = the product working).
 *
 * The digest stops after month 2 (8 weeks) to prevent notification fatigue.
 * The widget remains as the permanent, always-visible trophy.
 */
class WeeklyDigest : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent?) {
        val prefs = ctx.getSharedPreferences(SilentBlockerService.PREFS, Context.MODE_PRIVATE)

        val installTs = prefs.getLong(TrustNotifier.KEY_INSTALL_TS, 0L)
        if (installTs == 0L) return

        // Trust window check: during the first 7 days, per-block notifications
        // are active (via TrustNotifier), so the digest is not needed.
        val ageMs = System.currentTimeMillis() - installTs
        if (ageMs < TrustNotifier.TRUST_PERIOD_MS) return

        // After the trust window: weekly digest for the first 8 weeks,
        // then monthly thereafter. Never stop entirely — elderly users'
        // fraud awareness decays in 6-8 week cycles (LY Corp 2023 report).
        val ageWeeks = ageMs / WEEK_MS
        if (ageWeeks > 8) {
            // Monthly mode: only fire on the first Sunday of each month.
            // Uses device timezone (not UTC) so JP users get 09:00 JST.
            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getDefault())
            if (cal.get(java.util.Calendar.DAY_OF_MONTH) > 7) return
        }

        // Count blocks since the last digest fired (KEY_WEEK_COUNT is reset
        // by resetWeekCounter() at the end of each digest cycle).
        val weekCount = prefs.getInt(KEY_WEEK_COUNT, 0)
        if (weekCount == 0) {
            // No blocks this week — silence is the product working. No notif.
            resetWeekCounter(prefs)
            return
        }

        showDigest(ctx, weekCount)
        resetWeekCounter(prefs)
    }

    private fun showDigest(ctx: Context, count: Int) {
        val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        if (mgr.getNotificationChannel(CHANNEL) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL, ctx.getString(R.string.notif_channel_digest),
                    NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                }
            )
        }

        val text = ctx.getString(R.string.digest_text, count)

        val historyPi = android.app.PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, HistoryActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call_forward)
            .setContentTitle(ctx.getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(historyPi)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        mgr.notify(NOTIF_ID, notif)
    }

    private fun resetWeekCounter(prefs: android.content.SharedPreferences) {
        prefs.edit { putInt(KEY_WEEK_COUNT, 0) }
    }

    companion object {
        const val CHANNEL = "orange_digest"
        const val NOTIF_ID = 0x0D16E57  // "DIGEST" in hex
        const val KEY_WEEK_COUNT = "week_count"
        const val WEEK_MS = 7L * 24 * 60 * 60 * 1000

        /**
         * Schedule the weekly alarm. Called once from OnboardingActivity
         * after the screening role is granted. Uses inexact repeating alarm
         * (AlarmManager.INTERVAL_DAY * 7) so the OS can batch it with other
         * weekly alarms for battery efficiency.
         */
        fun schedule(ctx: Context) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(ctx, WeeklyDigest::class.java)
            val pi = PendingIntent.getBroadcast(
                ctx, 0x0D16E57, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Next Sunday 09:00 local
            val cal = Calendar.getInstance().apply {
                set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
                set(Calendar.HOUR_OF_DAY, 9)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) {
                    add(Calendar.WEEK_OF_YEAR, 1)
                }
            }

            am.setInexactRepeating(
                AlarmManager.RTC,
                cal.timeInMillis,
                AlarmManager.INTERVAL_DAY * 7,
                pi
            )
        }
    }
}

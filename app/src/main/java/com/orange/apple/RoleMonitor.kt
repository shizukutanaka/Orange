package com.orange.apple

import android.app.role.RoleManager
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Detects when the system revokes Orange's Call Screening role.
 *
 * Failure mode being closed: Android allows the user to grant the screening
 * role to a different app (or to none) at any time via Settings → Default
 * apps → Caller ID & spam app. When that happens, Orange's screening service
 * still exists but no longer receives calls. The user has no way to know
 * — they just notice "scam calls started ringing again."
 *
 * Competitor failure: Hiya users complained loudly that disabling Hiya was
 * unintuitive AND that re-enabling after an OS update was even less obvious.
 * Orange goes the other way: silently watches, and on next launch (or via
 * the widget tap) tells the user "open this to fix" — never harasses them
 * with notifications.
 *
 * Detection strategy:
 *   - On boot, on package replace, and on first widget render after
 *     a known role-loss event, check RoleManager.isRoleHeld()
 *   - When role is lost: set a flag in prefs and update the widget so
 *     its number is replaced with a single character "·" (a quiet
 *     unobtrusive break in the visual pattern that prompts the user to
 *     open the app)
 *   - When the user opens Onboarding while role is missing, re-request it
 *
 * No notification is fired. The widget glyph change is the only signal.
 * If the user never re-grants, Orange degrades silently to the OS default
 * (everything rings) — which is annoying but not unsafe. Rams #5
 * (unobtrusive) at all costs.
 */
internal object RoleMonitor {

    const val KEY_ROLE_HELD = "role_held"

    fun isRoleHeld(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        val rm = ctx.getSystemService(RoleManager::class.java) ?: return true
        return rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
    }

    /** Refreshes the prefs flag and triggers a widget update if state changed. */
    fun refresh(ctx: Context) {
        val held = isRoleHeld(ctx)
        val prefs = ctx.getSharedPreferences(SilentBlockerService.PREFS, Context.MODE_PRIVATE)
        val previously = prefs.getBoolean(KEY_ROLE_HELD, true)
        if (held != previously) {
            prefs.edit().putBoolean(KEY_ROLE_HELD, held).apply()
            // Nudge the widget to redraw. ACTION_APPWIDGET_UPDATE is only
            // delivered to onUpdate() when EXTRA_APPWIDGET_IDS is included —
            // without it, AppWidgetProvider.onReceive() drops the intent.
            val awm = AppWidgetManager.getInstance(ctx)
            val ids = awm.getAppWidgetIds(ComponentName(ctx, OrangeWidget::class.java))
            if (ids.isNotEmpty()) {
                ctx.sendBroadcast(Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).apply {
                    setPackage(ctx.packageName)
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                    component = ComponentName(ctx, OrangeWidget::class.java)
                })
            }
        }
    }
}

/**
 * Re-checks role on package replacement and on boot. Both events are
 * common moments for an OS-level config to silently change Orange's
 * effective state.
 */
class RoleMonitorReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                RoleMonitor.refresh(ctx)
                // AlarmManager clears all alarms on reboot. Reschedule the weekly
                // digest so it survives a power cycle. setInexactRepeating is
                // idempotent (FLAG_UPDATE_CURRENT replaces the existing alarm on
                // package-replace, where the alarm is still live).
                WeeklyDigest.schedule(ctx)
            }
        }
    }
}

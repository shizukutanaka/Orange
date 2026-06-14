package com.orange.apple

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews

/**
 * Apple philosophy: the widget is a trophy, not a dashboard.
 *
 * One number. Big. Centered. "247 silenced."
 * That is the whole UI. Tapping does nothing. Long-press (via app info)
 * is the only way to uninstall, and that's a feature: the app respects
 * the user enough to stay out of their way.
 *
 * Prior version had:
 *  - 4x1 widget with toggle, stats, recent call, settings shortcut
 *  - Tap-to-open main activity
 *  - Configuration screen to pick colors
 * All deleted. A widget with a button invites fidgeting. A widget with
 * a number invites calm.
 *
 * The pause-for-one-hour mechanism lives in the Quick Settings tile, not here.
 * Quick Settings is where toggles belong. Home screen is where identity lives.
 */
class OrangeWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        // Refresh role status before each render so a silent revocation
        // becomes visible the next time the widget redraws.
        RoleMonitor.refresh(ctx)

        val prefs = ctx.getSharedPreferences(SilentBlockerService.PREFS, Context.MODE_PRIVATE)
        val count = prefs.getInt(SilentBlockerService.KEY_COUNT, 0)
        val held = prefs.getBoolean(RoleMonitor.KEY_ROLE_HELD, true)

        ids.forEach { id ->
            val views = buildViews(ctx, count, held)
            mgr.updateAppWidget(id, views)
        }
    }

    private fun buildViews(ctx: Context, count: Int, roleHeld: Boolean): RemoteViews {
        val views = RemoteViews(ctx.packageName, R.layout.widget_orange)

        if (roleHeld) {
            views.setTextViewText(R.id.w_number, count.toString())
            views.setTextViewText(R.id.w_caption, ctx.getString(R.string.widget_caption))
            views.setContentDescription(R.id.w_number,
                "$count ${ctx.getString(R.string.widget_caption)}")
        } else {
            views.setTextViewText(R.id.w_number, "·")
            views.setTextViewText(R.id.w_caption, "")
            views.setContentDescription(R.id.w_number,
                ctx.getString(R.string.widget_a11y_role_lost))
        }

        // Role held → tap opens block history. Role lost → tap re-grants role.
        val openTarget = if (roleHeld) HistoryActivity::class.java else OnboardingActivity::class.java
        val openIntent = android.content.Intent(ctx, openTarget).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pi = android.app.PendingIntent.getActivity(
            ctx, 0x0BABE, openIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                android.app.PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, pi)

        views.setTextColor(R.id.w_number, Color.WHITE)
        views.setTextColor(R.id.w_caption, Color.parseColor("#FFE4CC"))
        return views
    }

    companion object {
        /**
         * Trigger immediate widget update. Called from SilentBlockerService
         * whenever the block count changes, so the widget reflects changes
         * immediately instead of waiting for the 30-minute refresh timer.
         */
        fun notifyUpdate(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx) ?: return
            val cn = ComponentName(ctx, OrangeWidget::class.java)
            val ids = mgr.getAppWidgetIds(cn).takeIf { it.isNotEmpty() } ?: return
            mgr.notifyAppWidgetUpdate(ids)
        }
    }
}

/*
Required res/layout/widget_orange.xml (place alongside this file's res/):

<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="#FF8C42"
    android:padding="16dp">

    <LinearLayout
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:layout_gravity="center">

        <TextView android:id="@+id/w_number"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textSize="48sp"
            android:textStyle="bold"
            android:layout_gravity="center_horizontal"/>

        <TextView android:id="@+id/w_caption"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textSize="12sp"
            android:layout_gravity="center_horizontal"
            android:layout_marginTop="-4dp"/>
    </LinearLayout>
</FrameLayout>

Required res/xml/widget_orange_info.xml:

<appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
    android:minWidth="110dp"
    android:minHeight="110dp"
    android:updatePeriodMillis="1800000"
    android:initialLayout="@layout/widget_orange"
    android:resizeMode="none"
    android:widgetCategory="home_screen"/>
*/

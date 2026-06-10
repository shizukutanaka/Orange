package com.orange.apple

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
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
        val layoutId = ctx.resources.getIdentifier(
            "widget_orange", "layout", ctx.packageName
        )
        val views = RemoteViews(ctx.packageName, layoutId)
        val numberId = ctx.resources.getIdentifier("w_number", "id", ctx.packageName)
        val captionId = ctx.resources.getIdentifier("w_caption", "id", ctx.packageName)

        if (roleHeld) {
            views.setTextViewText(numberId, count.toString())
            views.setTextViewText(captionId, ctx.getString(R.string.widget_caption))
            views.setContentDescription(numberId,
                "$count ${ctx.getString(R.string.widget_caption)}")
        } else {
            views.setTextViewText(numberId, "·")
            views.setTextViewText(captionId, "")
            views.setContentDescription(numberId,
                ctx.getString(R.string.widget_a11y_role_lost))
        }

        // Make the widget tappable: routes back to onboarding for re-grant.
        val openIntent = android.content.Intent(ctx, OnboardingActivity::class.java)
        val pi = android.app.PendingIntent.getActivity(
            ctx, 0, openIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val rootId = ctx.resources.getIdentifier("widget_root", "id", ctx.packageName)
        if (rootId != 0) views.setOnClickPendingIntent(rootId, pi)

        views.setTextColor(numberId, Color.WHITE)
        views.setTextColor(captionId, Color.parseColor("#FFE4CC"))
        return views
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

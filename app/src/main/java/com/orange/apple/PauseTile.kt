package com.orange.apple

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.edit

/**
 * Apple philosophy: controls belong in control centers, not in apps.
 *
 * The only way to pause Orange is a Quick Settings tile — pull down, tap.
 * Pause lasts exactly 1 hour, then auto-resumes. No "pause forever" option:
 * forever-pause is called uninstall, and users already know how to do that.
 *
 * No settings screen exists in the app. This tile is the entire control surface.
 * If a user wants to change behavior, they toggle this tile or delete the app.
 * Two affordances. That's the whole product surface.
 */
class PauseTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val prefs = getSharedPreferences(SilentBlockerService.PREFS, MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val pausedUntil = prefs.getLong(KEY_PAUSED_UNTIL, 0L)

        if (pausedUntil > now && pausedUntil - now <= MAX_PAUSE_MS) {
            // Resume immediately on tap.
            prefs.edit { putLong(KEY_PAUSED_UNTIL, 0L) }
        } else {
            // Pause for exactly one hour.
            prefs.edit { putLong(KEY_PAUSED_UNTIL, now + 60 * 60 * 1000L) }
        }
        refresh()
    }

    private fun refresh() {
        val prefs = getSharedPreferences(SilentBlockerService.PREFS, MODE_PRIVATE)
        val paused = isPaused(prefs)
        qsTile?.apply {
            state = if (paused) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
            label = getString(
                if (paused) R.string.tile_label_paused else R.string.tile_label_active
            )
            contentDescription = label
            updateTile()
        }
    }

    companion object {
        const val KEY_PAUSED_UNTIL = "paused_until"

        private const val MAX_PAUSE_MS = 2L * 60 * 60 * 1000 // 2× the nominal 1-hour pause

        /** Called from SilentBlockerService before each screening decision. */
        fun isPaused(prefs: android.content.SharedPreferences): Boolean {
            val now = System.currentTimeMillis()
            val pausedUntil = prefs.getLong(KEY_PAUSED_UNTIL, 0L)
            // Cap: if pausedUntil is more than 2h in the future the clock was
            // set backward after the pause was set — treat as expired to avoid
            // a permanent-pause deadlock where the clock never catches up.
            return pausedUntil > now && pausedUntil - now <= MAX_PAUSE_MS
        }
    }
}

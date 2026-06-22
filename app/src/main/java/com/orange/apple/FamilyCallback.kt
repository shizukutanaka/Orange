package com.orange.apple

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.service.quicksettings.TileService
import androidx.core.content.edit

/**
 * 2025 NPA/IPA/全消費者庁の推奨行動:
 *   「一度電話を切って、家族の番号にかけ直す」
 *
 * 全競合がこれを未実装。Orange が先に出す。
 *
 * 設計: Quick Settings タイル2枚目。
 *   - 長押し: プリセット番号を登録 (最大3件)
 *   - タップ: 1番目のプリセット番号に即発信
 *
 * READ_CONTACTS不要。番号は手入力でSharedPreferencesに保存。
 * Onboarding完了後、初回widget表示時に「家族の番号を登録しませんか」
 * トーストを1度だけ表示 (通知ではなくトースト = 非侵入的)。
 */
object FamilyCallback {

    private const val KEY_PREFIX = "family_"
    const val MAX_SLOTS = 3

    /** Returns the pre-set numbers (1-indexed, sparse). */
    fun getNumbers(ctx: Context): List<String> {
        val prefs = ctx.getSharedPreferences(SilentBlockerService.PREFS, Context.MODE_PRIVATE)
        return (1..MAX_SLOTS).mapNotNull { i ->
            prefs.getString("$KEY_PREFIX$i", null)?.takeIf { it.isNotBlank() }
        }
    }

    /** Set a pre-set number at slot (1-based). Rejects empty, non-phone, or out-of-range slot. */
    fun setNumber(ctx: Context, slot: Int, number: String): Boolean {
        if (slot !in 1..MAX_SLOTS) return false
        val cleaned = normalizeAndValidate(number) ?: return false
        ctx.getSharedPreferences(SilentBlockerService.PREFS, Context.MODE_PRIVATE)
            .edit { putString("$KEY_PREFIX$slot", cleaned) }
        return true
    }

    /**
     * Pure validation: normalize [number] and return the cleaned string if valid,
     * or null if the number is structurally unusable as a family contact.
     *
     * Exposed as internal so unit tests can call this without a Context.
     */
    internal fun normalizeAndValidate(number: String): String? {
        // Normalize first (folds full-width digits, strips spaces/hyphens/parens)
        // so full-width input "０９０…" is safely stored as ASCII "090…".
        val cleaned = PhoneNumbers.normalize(number)
        // Count digits only (the '+' prefix is not a digit).
        // Minimum: 3 digits (short codes like 110/119).
        // Maximum: 15 digits (ITU-T E.164 hard cap).
        val digitCount = cleaned.count { it.isDigit() }
        if (digitCount !in 3..15) return null
        return cleaned
    }

    /** Remove a slot. No-op for out-of-range slot. */
    fun clearNumber(ctx: Context, slot: Int) {
        if (slot !in 1..MAX_SLOTS) return
        ctx.getSharedPreferences(SilentBlockerService.PREFS, Context.MODE_PRIVATE)
            .edit { remove("$KEY_PREFIX$slot") }
    }

    /**
     * Returns the first configured number across all slots, or null if none are set.
     * Scans slots 1..MAX_SLOTS in order so a user who skipped slot 1 but filled slot 2
     * still gets a "Call Family" action in WarningNotifier — consistent with dialPrimary().
     * The old slot-1-only implementation returned null for sparse configurations even
     * when dialPrimary() would have succeeded.
     */
    fun primaryNumber(ctx: Context): String? {
        val prefs = ctx.getSharedPreferences(SilentBlockerService.PREFS, Context.MODE_PRIVATE)
        return (1..MAX_SLOTS)
            .mapNotNull { i -> prefs.getString("$KEY_PREFIX$i", null)?.takeIf { it.isNotBlank() } }
            .firstOrNull()
    }

    /**
     * Launch the dialer with the first configured slot. No CALL_PHONE permission
     * needed — ACTION_DIAL opens the dialer, the user confirms.
     * Checks slots in order (1, 2, 3) and dials the first non-empty one so
     * users who skipped slot 1 but filled slot 2 can still use the tile.
     */
    fun dialPrimary(ctx: Context): Boolean {
        val prefs = ctx.getSharedPreferences(SilentBlockerService.PREFS, Context.MODE_PRIVATE)
        val num = (1..MAX_SLOTS)
            .mapNotNull { i -> prefs.getString("$KEY_PREFIX$i", null)?.takeIf { it.isNotBlank() } }
            .firstOrNull() ?: return false
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$num")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
        return true
    }

    /** Has the user been prompted to register at least once? */
    fun hasBeenPrompted(ctx: Context): Boolean =
        ctx.getSharedPreferences(SilentBlockerService.PREFS, Context.MODE_PRIVATE)
            .getBoolean("family_prompted", false)

    fun markPrompted(ctx: Context) {
        ctx.getSharedPreferences(SilentBlockerService.PREFS, Context.MODE_PRIVATE)
            .edit { putBoolean("family_prompted", true) }
    }
}

/**
 * Quick Settings tile: "家族に連絡"
 * Tap = dial primary family number.
 * Long-press = open Onboarding (which now doubles as the number-entry point).
 */
class FamilyCallbackTile : TileService() {
    override fun onClick() {
        super.onClick()
        if (!FamilyCallback.dialPrimary(this)) {
            // No number configured — open Settings for family number entry.
            val intent = Intent(this, SettingsActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pi = PendingIntent.getActivity(
                    this, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pi)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
    }
}

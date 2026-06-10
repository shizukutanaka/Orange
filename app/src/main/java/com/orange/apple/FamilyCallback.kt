package com.orange.apple

import android.content.Context
import android.content.Intent
import android.net.Uri
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

    /** Set a pre-set number at slot (1-based). Rejects empty or non-phone input. */
    fun setNumber(ctx: Context, slot: Int, number: String): Boolean {
        require(slot in 1..MAX_SLOTS)
        val cleaned = number.filter { it.isDigit() || it == '+' }
        // Minimum: 3 digits (short codes like 110/119).
        // Maximum: 15 digits (ITU-T E.164 maximum).
        if (cleaned.length !in 3..15) return false
        // Must contain at least one digit (not just "+")
        if (cleaned.count { it.isDigit() } == 0) return false
        ctx.getSharedPreferences(SilentBlockerService.PREFS, Context.MODE_PRIVATE)
            .edit { putString("$KEY_PREFIX$slot", cleaned) }
        return true
    }

    /** Remove a slot. */
    fun clearNumber(ctx: Context, slot: Int) {
        require(slot in 1..MAX_SLOTS)
        ctx.getSharedPreferences(SilentBlockerService.PREFS, Context.MODE_PRIVATE)
            .edit { remove("$KEY_PREFIX$slot") }
    }

    /** Returns the primary (slot 1) number, or null. */
    fun primaryNumber(ctx: Context): String? =
        ctx.getSharedPreferences(SilentBlockerService.PREFS, Context.MODE_PRIVATE)
            .getString("${KEY_PREFIX}1", null)?.takeIf { it.isNotBlank() }

    /** Launch the dialer with the primary number. No CALL_PHONE permission
     *  needed — ACTION_DIAL opens the dialer, the user confirms. */
    fun dialPrimary(ctx: Context): Boolean {
        val num = primaryNumber(ctx) ?: return false
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
            // No number configured — open Onboarding for setup.
            val intent = Intent(this, OnboardingActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("show_family_setup", true)
            }
            startActivityAndCollapse(intent)
        }
    }
}

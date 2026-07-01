package com.orange.apple

import android.content.Context

/**
 * Manual "block this number" entry point.
 *
 * Every other block in Orange is automatic — derived from one of the 16
 * decision-engine layers matching a known pattern (spam cache, Wangiri,
 * spoofing, foreign-unsolicited, etc.). None of those layers can catch a
 * genuinely novel scam number a user learns about some other way (a news
 * report, a family member's warning, a call that rang through because it
 * matched no seeded pattern). Until this, Orange had no way for the user
 * to teach it anything — the one asymmetry competitors always cover with
 * a manual block/report action, which Orange intentionally never made
 * community-sourced (privacy stance) but had also never made *personal*.
 *
 * This is purely local: it writes directly into SpamCache (the same store
 * every automatic SILENCE decision populates), so a manually-blocked number
 * is indistinguishable from an automatically-blocked one on the next call —
 * Layer 6 (isSpamCached) fires exactly the same way.
 */
internal object ManualBlock {

    /**
     * Pure validation: normalize [number] and return the cleaned string if it's
     * safe to add to the spam cache, or null if it isn't.
     *
     * Rejects:
     *  - empty / non-phone input
     *  - emergency numbers (110/119/etc.) — blocking one would be catastrophic
     *    if the user later needs it and Orange's own Layer 1 check still passes
     *    (Layer 1 always wins regardless of spam cache, so this is defense in
     *    depth, not the only guard — but presenting it as "blocked" in the UI
     *    would be misleading since it can never actually be silenced)
     *
     * Exposed as internal so unit tests can call this without a Context.
     */
    internal fun normalizeAndValidate(number: String): String? {
        val cleaned = PhoneNumbers.normalize(number)
        val digitCount = cleaned.count { it.isDigit() }
        if (digitCount !in 3..15) return null
        if (EmergencyWhitelist.isEmergency(cleaned)) return null
        return cleaned
    }

    /**
     * Validates and blocks [number]. Returns true if the number was added
     * (or already present), false if it failed validation.
     *
     * Adds every domestic↔E.164 variant, same as SilentBlockerService does for
     * an automatic SILENCE — otherwise a manually-blocked domestic-form number
     * would not match a later call delivered in E.164 form, or vice versa.
     *
     * Also records a BlockHistoryStore entry immediately (reason MANUAL_BLOCK),
     * unlike waiting for the number to actually call again. Every OTHER block
     * in this app gets an immediate, visible, undoable History entry the moment
     * it happens; a manual block that only became visible after the number
     * happened to call back again would be the one silent, unrecoverable action
     * in an otherwise fully auditable product — a mistaken block (wrong number,
     * a family member's number typo'd) would have no trail until it was too late.
     */
    fun block(ctx: Context, number: String): Boolean {
        val cleaned = normalizeAndValidate(number) ?: return false
        val prefs = ctx.getSharedPreferences(SilentBlockerService.PREFS, Context.MODE_PRIVATE)
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
        val iso = tm?.simCountryIso?.takeIf { it.isNotEmpty() }
            ?: tm?.networkCountryIso?.takeIf { it.isNotEmpty() }
        val cc = callingCodeOf(iso?.uppercase(java.util.Locale.ROOT))
        phoneVariants(cleaned, cc).forEach { v -> SpamCache.add(prefs, v) }
        BlockHistoryStore.record(prefs, cleaned, BlockReason.MANUAL_BLOCK, System.currentTimeMillis())
        return true
    }
}

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

    /** Outcome of a block attempt — lets the UI show an accurate, specific message. */
    enum class Result {
        /** Added to SpamCache; will be silenced on the next call. */
        BLOCKED,
        /** Empty/malformed input, or an emergency number. */
        INVALID,
        /**
         * The number already bypasses Layer 6 (spam cache) via an earlier,
         * higher-priority trust layer — family, bundled business, or a number
         * the user has dialed before (SilentBlockerService.screenIncoming
         * checks these, in that exact order, before ever reaching the spam
         * cache). Blocking it would be a silent no-op: SpamCache.add() would
         * "succeed" but the number would keep ringing through every future
         * call, since decide()'s Layer 4/5 always wins over Layer 6.
         */
        ALREADY_TRUSTED,
    }

    /**
     * Pure validation: normalize [number] and return the cleaned string if it's
     * structurally safe to add to the spam cache, or null if it isn't.
     *
     * Rejects:
     *  - empty / non-phone input
     *  - emergency numbers (110/119/etc.) — blocking one would be catastrophic
     *    if the user later needs it and Orange's own Layer 1 check still passes
     *    (Layer 1 always wins regardless of spam cache, so this is defense in
     *    depth, not the only guard — but presenting it as "blocked" in the UI
     *    would be misleading since it can never actually be silenced)
     *
     * Does NOT check trust-set membership (family/business/outbound) — that
     * requires a Context to read the trusted sets. See classify().
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
     * Pure classification: given a number's domestic↔E.164 [variants], decide
     * whether blocking it would actually take effect or silently no-op against
     * a higher-priority trust layer.
     *
     * Mirrors the exact bypass check SilentBlockerService.screenIncoming performs
     * before ever consulting the spam cache (family + outbound-known + bundled
     * business, each checked across every variant) — same precedence, same
     * variant-expansion rationale (a number trusted in domestic form must not be
     * blockable via its E.164 form, or vice versa).
     *
     * [hashOf] lets tests inject a fake hash function; production passes
     * SpamCache.hash bound to a real SharedPreferences.
     *
     * Exposed as internal so unit tests can call this without a Context.
     */
    internal fun classify(
        variants: Set<String>,
        familyNumbers: Set<String>,
        businessNumbers: Set<String>,
        outboundHashes: Set<String>,
        hashOf: (String) -> String,
    ): Result {
        val trusted = variants.any {
            hashOf(it) in outboundHashes || it in familyNumbers || it in businessNumbers
        }
        return if (trusted) Result.ALREADY_TRUSTED else Result.BLOCKED
    }

    /**
     * Validates and blocks [number]. Returns the outcome so the UI can show an
     * accurate message instead of a blanket "Blocked" that would be misleading
     * for a number that can never actually be silenced (see Result.ALREADY_TRUSTED).
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
     * Only written for Result.BLOCKED — an ALREADY_TRUSTED "block" changes
     * nothing, so recording it would create a phantom History entry for an
     * action that had no effect.
     */
    fun block(ctx: Context, number: String): Result {
        val cleaned = normalizeAndValidate(number) ?: return Result.INVALID
        val prefs = ctx.getSharedPreferences(SilentBlockerService.PREFS, Context.MODE_PRIVATE)
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
        val iso = tm?.simCountryIso?.takeIf { it.isNotEmpty() }
            ?: tm?.networkCountryIso?.takeIf { it.isNotEmpty() }
        val cc = callingCodeOf(iso?.uppercase(java.util.Locale.ROOT))
        val variants = phoneVariants(cleaned, cc)

        val outboundHashes = prefs.getStringSet(SilentBlockerService.KEY_OUTBOUND, emptySet()).orEmpty()
        val familyNumbers = (1..FamilyCallback.MAX_SLOTS).mapNotNull { i ->
            prefs.getString("${FamilyCallback.KEY_PREFIX}$i", null)?.takeIf { it.isNotBlank() }
                ?.let { PhoneNumbers.normalize(it) }?.takeIf { it.isNotEmpty() }
        }.toSet()
        val businessNumbers = BusinessDirectoryBundle.load(ctx).keys

        val result = classify(variants, familyNumbers, businessNumbers, outboundHashes) { v -> SpamCache.hash(prefs, v) }
        if (result != Result.BLOCKED) return result

        variants.forEach { v -> SpamCache.add(prefs, v) }
        BlockHistoryStore.record(prefs, cleaned, BlockReason.MANUAL_BLOCK, System.currentTimeMillis())
        return Result.BLOCKED
    }
}

package com.orange.apple

/**
 * Pure call-screening decision engine.
 *
 * Carmack rule: the hot path is a pure function. No Android types, no
 * Context, no reflection, no file I/O, no clock reads. Everything the
 * engine needs is passed in; everything it produces is in the return
 * value. This has three consequences:
 *
 *  1. Unit-testable without Robolectric, without mocks, without reflection.
 *     Tests construct a CallContext and read a Verdict. That's it.
 *
 *  2. Audit-able. An external reviewer can read 200 lines and know every
 *     branch that could cause a call to ring or not ring.
 *
 *  3. Evolvable. New detection rules are new fields on CallContext and new
 *     branches in decide(). The outer SilentBlockerService adapter barely
 *     changes.
 *
 * The adapter (SilentBlockerService) is responsible for:
 *   - extracting the phone number from Call.Details
 *   - reading SharedPreferences into a CallState
 *   - reading TelephonyManager for callee country
 *   - calling decide()
 *   - writing the Verdict back (respondToCall) and updating state
 *
 * Nothing in THIS file touches any of that. That's the whole point.
 */

/** What the engine decides. Mutual exclusion is deliberate. No "maybe". */
enum class Verdict {
    /** Let the call ring. Also used for emergencies, outbound returns, trusted businesses. */
    RING,

    /** Silence the call. Also increments the block counter and triggers notification. */
    SILENCE,
}

/** Why a call was silenced — used for trust notifications and threat model auditing. */
enum class BlockReason {
    SPAM_CACHE,
    FOREIGN_ELEVATED,
    FOREIGN_GENERIC,
    DOMESTIC_SPOOF,
    WANGIRI_CALLBACK,
    CARRIER_VERIFICATION_FAILED,
    WITHHELD_NUMBER,
    PREMIUM_RATE_INTERNATIONAL,
    /** Device DND is active; unknown domestic caller silenced. */
    DND_HONOR,
    /** Repeat-caller velocity: same number called N+ times in T minutes. */
    REPEAT_CALLER,
}

/** Everything the engine learns about ONE incoming call. */
data class CallContext(
    /** E.164-normalized, e.g. "+81901234567" or domestic "09012345678" or short "110". */
    val number: String,

    /** ISO country of the user's SIM, uppercased. "JP"/"US"/null. */
    val calleeCountryIso: String?,

    /** Wall-clock time, injected for deterministic tests. */
    val nowMillis: Long,

    /**
     * True when the carrier's STIR/SHAKEN verification has explicitly FAILED.
     * API 30+ only; defaults to false on older SDKs.
     */
    val verificationFailed: Boolean = false,

    /**
     * True when caller ID is withheld (非通知). Android delivers these as
     * empty or "anonymous" handles. 2025 NPA: ~75.5% of special-fraud
     * first-contacts are phone, withheld-number calls are a common
     * vector for 還付金詐欺 and 架空料金請求詐欺.
     */
    val numberWithheld: Boolean = false,

    /**
     * True when the device DND (Do Not Disturb) filter is TOTAL SILENCE,
     * PRIORITY, or ALARMS ONLY. Injected from NotificationManager.getCurrentInterruptionFilter()
     * in SilentBlockerService. Allows Orange to be more aggressive during
     * user-intentional quiet hours.
     */
    val dndActive: Boolean = false,
)

/** Everything the engine learns about the user's HISTORY. Pure snapshot. */
data class CallState(
    val outboundKnown: Set<String>,
    /**
     * Whether THIS call's number is in the (hashed) spam cache. Resolved by
     * the adapter via SpamCache.contains() so the engine stays pure and never
     * needs to know how the cache is stored (plaintext vs. hashed). See
     * SpamCache for the privacy rationale (arXiv:2304.02810).
     */
    val isSpamCached: Boolean,
    val knownBusinesses: Set<String>,

    /** Pause-tile expiry in wall-clock millis. 0 = not paused. */
    val pausedUntilMillis: Long,

    /**
     * Timestamp of the most recent short ring from the current caller, or null.
     * Pre-computed by SilentBlockerService (which has SharedPreferences access for
     * hash lookups) so the pure decision engine never needs prefs.
     */
    val wangiriRingAt: Long?,
)

/** Engine result: what to do, and why. */
data class Decision(
    val verdict: Verdict,
    val reason: BlockReason? = null,
    /**
     * Non-null when the call should RING but the user should see a
     * post-pickup warning.
     */
    val warning: WarnReason? = null,
    /**
     * Optional string payload for the warning notification.
     * For POLICE_IMPERSONATION*: the HQ display name (e.g. "警視庁").
     * Avoids a second map lookup in the adapter.
     */
    val warnPayload: String? = null,
)

/** Post-pickup warning reasons. The call rings, but Orange alerts the user. */
enum class WarnReason {
    POLICE_IMPERSONATION,
    POLICE_IMPERSONATION_HIGH,
    /** Unknown domestic mobile during アポ電 peak hours (09-12, 13-16 weekday JST). */
    HIGH_RISK_HOUR_DOMESTIC,
}

/**
 * The one function that decides every call. Read in order — first match wins.
 */
fun decide(ctx: CallContext, state: CallState): Decision {
    // Layer 1: Emergency. Absolutely always rings.
    if (EmergencyWhitelist.isEmergency(ctx.number)) return Decision(Verdict.RING)

    // Layer 2: Paused. User asked for silence to stop — respect it for ALL
    // calls including withheld. If pause didn't override withheld, a user
    // expecting a callback from a hospital (restricted ID) who paused Orange
    // would still have that call silenced. Pause means "everything rings."
    if (ctx.nowMillis < state.pausedUntilMillis) return Decision(Verdict.RING)

    // Layer 3: Withheld caller ID (非通知). Placed AFTER pause so the user
    // can temporarily allow withheld calls by tapping the Quick Settings tile.
    // An empty number must not reach set-membership layers below (outbound-known
    // or spam-cached would produce false matches on ""), so this layer also
    // acts as a defensive guard.
    if (ctx.numberWithheld) {
        return Decision(Verdict.SILENCE, BlockReason.WITHHELD_NUMBER)
    }

    // Defensive: never let an empty number reach the set-membership layers.
    if (ctx.number.isEmpty()) return Decision(Verdict.RING)

    // Layer 4: Outbound-known. User previously dialed this.
    if (ctx.number in state.outboundKnown) return Decision(Verdict.RING)

    // Layer 5: Bundled business. Known-legit, auto-allowed.
    if (ctx.number in state.knownBusinesses) return Decision(Verdict.RING)

    // Layer 6: User-marked spam. Always block on subsequent attempts.
    if (state.isSpamCached) return Decision(Verdict.SILENCE, BlockReason.SPAM_CACHE)

    // Layer 7: Wangiri callback. SilentBlockerService pre-computed whether any
    // phone variant of the current caller matches a recent short ring.
    val recentRingAt = state.wangiriRingAt
    if (recentRingAt != null && ctx.nowMillis >= recentRingAt && ctx.nowMillis - recentRingAt < WANGIRI_WINDOW_MS) {
        return Decision(Verdict.SILENCE, BlockReason.WANGIRI_CALLBACK)
    }

    // Layer 8: Domestic spoofing. JP number that can't exist in the JP plan.
    if (ctx.calleeCountryIso == "JP" && DomesticSpoofDetector.isImpossibleJpNumber(ctx.number)) {
        return Decision(Verdict.SILENCE, BlockReason.DOMESTIC_SPOOF)
    }

    // Layer 9: Police HQ number impersonation detection.
    // Police numbers MUST ring even when STIR/SHAKEN reports FAILED — a real
    // officer calling from their HQ phone must not be silenced by Layer 10.
    // This layer runs BEFORE the STIR/SHAKEN check so that the verification
    // failure can be used to escalate the warning severity rather than block
    // the call. If Layer 10 ran first and silenced on verificationFailed, a
    // real police HQ call with spoofed carrier data would be blocked silently.
    //
    // 2025 ニセ警察詐欺 (9,642件, ¥831.9億円): scammers spoof real police
    // HQ representative numbers. We RING with a post-pickup warning instead.
    // The old 0110-tail heuristic was too broad; directory exact-match is correct.
    // Single lookup — result goes into warnPayload so the adapter
    // does not need a second map traversal.
    if (ctx.calleeCountryIso == "JP") {
        val hqName = PoliceStationDirectory.lookup(ctx.number)
        if (hqName != null) {
            val warnSeverity = if (ctx.verificationFailed)
                WarnReason.POLICE_IMPERSONATION_HIGH
            else
                WarnReason.POLICE_IMPERSONATION
            return Decision(Verdict.RING, warning = warnSeverity, warnPayload = hqName)
        }
    }

    // Layer 10: STIR/SHAKEN carrier verification failed.
    // Reached only for non-emergency, non-paused, non-withheld, non-outbound,
    // non-business, non-spam, non-wangiri, non-spoof, non-police numbers.
    if (ctx.verificationFailed) {
        return Decision(Verdict.SILENCE, BlockReason.CARRIER_VERIFICATION_FAILED)
    }

    // Layer 11: International premium-rate and network numbers.
    if (ctx.number.startsWith("+")) {
        val prefix = ctx.number.removePrefix("+")
        if (prefix.startsWith("800") || prefix.startsWith("979") ||
            prefix.startsWith("882") || prefix.startsWith("883")) {
            return Decision(Verdict.SILENCE, BlockReason.PREMIUM_RATE_INTERNATIONAL)
        }
        // Caribbean/Atlantic NANP premium area codes (+1-242, +1-876 etc.).
        if (CaribbeanPremiumNANP.isPremiumNANP(ctx.number)) {
            return Decision(Verdict.SILENCE, BlockReason.PREMIUM_RATE_INTERNATIONAL)
        }
    }

    // Layer 12: International + elevated-risk corridor.
    if (ctx.calleeCountryIso == "JP" && ctx.number.startsWith("+")) {
        val cc = ScamPrefixSeed.countryCodeOf(ctx.number)
        if (cc != null && cc in ScamPrefixSeed.elevatedRiskCountryCodes) {
            return Decision(Verdict.SILENCE, BlockReason.FOREIGN_ELEVATED)
        }
    }

    // Layer 13: International + foreign-unsolicited.
    // Fix: compare caller's E.164 country code directly against the callee's
    // calling code, rather than mapping through ISO strings.  The old
    // isoOfCountryCode() bridge only covered 16 countries, so calls from
    // Brazil (+55), Thailand (+66), Indonesia (+62), etc. silently rang
    // through. ScamPrefixSeed.countryCodeOf() covers 150+ country codes,
    // so this check now fires for almost any international call.
    if (ctx.number.startsWith("+")) {
        val callerCc = ScamPrefixSeed.countryCodeOf(ctx.number)
        val calleeCc = callingCodeOf(ctx.calleeCountryIso)
        if (callerCc != null && calleeCc != null && callerCc != calleeCc) {
            return Decision(Verdict.SILENCE, BlockReason.FOREIGN_GENERIC)
        }
    }

    // Layer 14: DND honor mode. When the device is in total/priority DND and
    // the call is from an unknown domestic number, silence it. The user has
    // explicitly asked for no interruptions; Orange respects that. Outbound-known
    // and business-bundle layers (above) have already passed legitimate numbers.
    if (ctx.dndActive) {
        return Decision(Verdict.SILENCE, BlockReason.DND_HONOR)
    }

    // Layer 15: Time-of-day risk multiplier for unknown domestic mobile numbers.
    // アポ電 (scam reconnaissance calls) peak in 09:00-12:00 and 13:00-16:00 JST
    // on weekdays, per prefectural police advisory logs (e.g., 大阪狭山市, 愛知県警).
    // Unknown 090/080/070/060 numbers during these windows get a soft warning.
    // No new permissions; nowMillis is already injected.
    if (isHighRiskHour(ctx.nowMillis) && isUnknownDomesticMobile(ctx.number)) {
        return Decision(Verdict.RING, warning = WarnReason.HIGH_RISK_HOUR_DOMESTIC)
    }

    // Layer 16: Allow. Silence-on-uncertainty is how users lose trust.
    return Decision(Verdict.RING)
}

internal val WANGIRI_WINDOW_MS get() = WangiriTracker.WANGIRI_WINDOW_MS

/**
 * Returns true during アポ電 peak hours: Mon-Fri 09:00-12:00 and 13:00-16:00 JST.
 * Source: prefectural police advisory logs (大阪狭山市, 愛知県警, etc.) show
 * scam reconnaissance calls clustered in business hours when elderly are home.
 */
internal fun isHighRiskHour(nowMillis: Long): Boolean {
    val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Tokyo"))
    cal.timeInMillis = nowMillis
    val dow = cal.get(java.util.Calendar.DAY_OF_WEEK)
    if (dow == java.util.Calendar.SATURDAY || dow == java.util.Calendar.SUNDAY) return false
    val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
    // 09:00-12:59 (hours 9-12) and 13:00-16:59 (hours 13-16) per police advisory logs.
    return hour in 9..12 || hour in 13..16
}

/**
 * Returns true for unknown domestic mobile-origin numbers.
 * Prefix check only — no contact lookup (READ_CONTACTS not permitted).
 * 2025 Tobila Oct report: mobile share of scam numbers rose +11 pp MoM.
 * Covers both domestic trunk form ("090...") and E.164 form ("+8190..."),
 * since Android may deliver the same JP mobile number in either format.
 */
internal fun isUnknownDomesticMobile(number: String): Boolean {
    // Domestic trunk form: 090/080/070/060
    if (number.startsWith("090") || number.startsWith("080") ||
        number.startsWith("070") || number.startsWith("060")) return true
    // E.164 JP mobile: +8190/+8180/+8170/+8160
    if (number.startsWith("+8190") || number.startsWith("+8180") ||
        number.startsWith("+8170") || number.startsWith("+8160")) return true
    // Carrier-mangled E.164: some carriers keep the domestic leading zero after "+81",
    // delivering "+81090..." instead of "+8190...". Convert to domestic first.
    if (number.startsWith("+810")) {
        val rest = number.removePrefix("+81")  // "+81090..." → "090..."
        if (rest.startsWith("090") || rest.startsWith("080") ||
            rest.startsWith("070") || rest.startsWith("060")) return true
    }
    return false
}

/**
 * Maps a callee's ISO country code to the ITU calling code for that country.
 * Used only to determine whether an incoming international call is from the
 * callee's own country. Keeping this small (homebase countries only) is
 * intentional — for exotic callee countries the layer simply doesn't fire,
 * which is the conservative safe default.
 */
internal fun callingCodeOf(iso: String?): String? = when (iso) {
    "JP" -> "81"; "US" -> "1"; "KR" -> "82"; "CN" -> "86"
    "GB" -> "44"; "DE" -> "49"; "FR" -> "33"; "AU" -> "61"
    "IN" -> "91"; "BR" -> "55"; "TH" -> "66"; "ID" -> "62"
    else -> null
}

/**
 * Whether a SILENCE for this reason should be remembered in the spam cache so a
 * repeat call from the same number is silenced instantly by the cheap Layer-6
 * lookup, rather than re-derived through every layer.
 *
 * CONTEXTUAL silences are excluded — their block condition is temporary and may
 * clear on its own without user action:
 *
 *  - DND_HONOR: silenced because the device was in Do Not Disturb. Caching
 *    would keep the number silenced after the user turns DND off.
 *
 *  - REPEAT_CALLER: silenced because the same number called N+ times in 60 min.
 *    The 60-minute window expires naturally. Caching would permanently block a
 *    legitimate urgent caller (e.g. an elderly parent who couldn't get through)
 *    even after the velocity window clears; the user would have to Restore manually.
 *
 * All other reasons reflect a PERSISTENT property of the number and are safe to cache.
 */
internal fun isCacheableSilence(reason: BlockReason): Boolean = when (reason) {
    // Contextual silences — must NOT be cached (see block comment above).
    BlockReason.DND_HONOR       -> false
    BlockReason.REPEAT_CALLER   -> false
    // Persistent-property silences — safe to cache so repeat calls are fast-pathed.
    BlockReason.SPAM_CACHE                  -> true
    BlockReason.FOREIGN_ELEVATED            -> true
    BlockReason.FOREIGN_GENERIC             -> true
    BlockReason.DOMESTIC_SPOOF              -> true
    BlockReason.WANGIRI_CALLBACK            -> true
    BlockReason.CARRIER_VERIFICATION_FAILED -> true
    // WITHHELD_NUMBER: number is always "" for withheld calls, and the guard
    // `number.isNotEmpty()` in SilentBlockerService already prevents caching.
    // If that guard were ever relaxed, all withheld calls would hash identically
    // (SHA-256 of just the salt), permanently blocking ALL withheld numbers after
    // one false-positive Restore. Explicitly false to make the invariant visible.
    BlockReason.WITHHELD_NUMBER             -> false
    BlockReason.PREMIUM_RATE_INTERNATIONAL  -> true
    // NOTE: adding a new BlockReason enum entry will cause a compile error here,
    // forcing the author to decide whether it should be cached or not.
}

/**
 * Returns the set of equivalent forms of a phone number: the number itself
 * plus its domestic↔E.164 counterpart, given the home country's calling code.
 *
 * Why this exists: a user dials (or registers) a number in domestic form
 * ("09012345678"), but Android delivers the matching INCOMING call in E.164
 * ("+819012345678"). Without variant matching, the outbound-known / family
 * trusted-set lookup misses, and a number the user explicitly trusts gets
 * silenced. By checking every variant of the incoming number against the
 * trusted set, either stored form matches.
 *
 * Only the home calling code is expanded — a "0"-led number is treated as a
 * domestic trunk-prefix number for that country. For null callingCode (unknown
 * SIM country) only the number itself is returned, the conservative default.
 */
internal fun phoneVariants(number: String, callingCode: String?): Set<String> {
    if (number.isEmpty()) return emptySet()
    val out = linkedSetOf(number)
    if (callingCode != null) {
        when {
            // domestic trunk form → E.164: "09012345678" → "+819012345678"
            number.startsWith("0") && !number.startsWith("+") ->
                out.add("+$callingCode${number.substring(1)}")
            // E.164 → domestic trunk form: "+819012345678" → "09012345678".
            // Some carriers deliver "+810XXXXXXXXX" (leading zero kept after +81).
            // In that case removePrefix("+81") already starts with "0", so we must
            // not prepend another "0" — mirror the logic in PoliceStationDirectory.lookup().
            number.startsWith("+$callingCode") -> {
                val rest = number.removePrefix("+$callingCode")
                out.add(if (rest.startsWith("0")) rest else "0$rest")
            }
        }
    }
    return out
}

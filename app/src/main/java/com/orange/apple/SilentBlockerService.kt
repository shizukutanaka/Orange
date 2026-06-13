package com.orange.apple

import android.content.SharedPreferences
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telephony.TelephonyManager
import androidx.core.content.edit
import java.util.Locale

/**
 * Thin adapter between Android's Telecom framework and the pure decision
 * engine in CallDecision.kt.
 *
 * Everything interesting has been lifted into pure functions so this file
 * does three mechanical things:
 *
 *   1. Translate Call.Details → CallContext
 *   2. Read SharedPreferences → CallState
 *   3. Translate Decision → respondToCall(...) + side-effects (counter,
 *      spam cache on explicit user mark, notification request)
 *
 * The screener is a latency-sensitive callback (Android gives it a short
 * deadline before auto-allowing). Every branch below is O(1) against the
 * cached state, and none performs network or disk I/O beyond the single
 * SharedPreferences read.
 */
class SilentBlockerService : CallScreeningService() {

    override fun onScreenCall(details: Call.Details) {
        val raw = details.handle?.schemeSpecificPart
        val now = System.currentTimeMillis()
        val p = prefs

        // Withheld (非通知) detection. Carriers return various strings for
        // hidden caller ID — cover all known variants across JP MNOs/MVNOs.
        val withheld = raw.isNullOrEmpty()
            || raw == "-1"
            || raw.equals("anonymous", ignoreCase = true)
            || raw.equals("restricted", ignoreCase = true)
            || raw.equals("private", ignoreCase = true)
            || raw.equals("unavailable", ignoreCase = true)
            || raw.equals("unknown", ignoreCase = true)
            || raw.equals("withheld", ignoreCase = true)
        val number = if (withheld) "" else normalize(raw)

        // --- OUTGOING: record + guard ---
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
            details.callDirection == Call.Details.DIRECTION_OUTGOING) {
            handleOutgoing(p, number, now)
            return respond(details, ring = true)
        }

        // --- INCOMING: decide + act ---
        val decision = screenIncoming(p, number, now, withheld, details)
        handleDecision(p, decision, number, now)
        respond(details, ring = decision.verdict == Verdict.RING)
    }

    private fun handleOutgoing(p: SharedPreferences, number: String, now: Long) {
        addToOutbound(p, number)
        if (number.isNotEmpty() && OutboundGuard.wasRecentlyFlagged(p, number, now)) {
            WarningNotifier.showOutboundWarning(this, number)
        }
    }

    private fun screenIncoming(
        p: SharedPreferences, number: String, now: Long,
        withheld: Boolean, details: Call.Details
    ): Decision {
        // Allow-suffix override: user explicitly allowed this number from history.
        if (number.isNotEmpty() && AllowSuffixStore.isAllowed(p, number)) {
            return Decision(Verdict.RING)
        }

        // Build trusted sets early. Family numbers + outbound-known must never
        // accumulate toward the repeat-caller threshold and must bypass it entirely.
        // Family numbers are stored in domestic format by the user but arrive as
        // E.164 from Android — load both forms so they always match.
        val outbound = p.getStringSet(KEY_OUTBOUND, emptySet()).orEmpty()
        val family = familyNumberSet(p, simCountryIso())
        val trusted = outbound + family
        if (number.isNotEmpty() && number in trusted) {
            return Decision(Verdict.RING)
        }

        // Record repeat-caller only for untrusted numbers so family members
        // who call multiple times are never silenced by velocity heuristics.
        if (number.isNotEmpty()) RepeatCallerTracker.record(p, number, now)

        // Check repeat-caller velocity BEFORE building full state (state is read-only).
        val isRepeat = RepeatCallerTracker.isRepeatOffender(p, number, now)
        if (isRepeat && number.isNotEmpty()) {
            return Decision(Verdict.SILENCE, BlockReason.REPEAT_CALLER)
        }

        val state = CallState(
            outboundKnown     = trusted,
            isSpamCached      = SpamCache.contains(p, number),
            knownBusinesses   = BusinessDirectoryBundle.load(this).keys,
            pausedUntilMillis = p.getLong(PauseTile.KEY_PAUSED_UNTIL, 0L),
            recentShortRings  = WangiriTracker.snapshot(p, now),
        )

        // DND state: read once per call, inject into context.
        val dndActive = isDndActive()

        val context = CallContext(
            number = number,
            calleeCountryIso = simCountryIso(),
            nowMillis = now,
            verificationFailed = isVerificationFailed(details),
            numberWithheld = withheld,
            dndActive = dndActive,
        )
        return decide(context, state)
    }

    private fun isDndActive(): Boolean {
        val mgr = getSystemService(android.app.NotificationManager::class.java) ?: return false
        val filter = mgr.currentInterruptionFilter
        return filter == android.app.NotificationManager.INTERRUPTION_FILTER_NONE ||
               filter == android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY
    }

    private fun handleDecision(
        p: SharedPreferences, decision: Decision, number: String, now: Long
    ) {
        if (decision.verdict == Verdict.SILENCE) {
            recordBlock(number)
            if (number.isNotEmpty()) OutboundGuard.record(p, number, now)
            if (decision.reason == BlockReason.WANGIRI_CALLBACK) {
                WangiriTracker.forget(p, number)
            }
            decision.reason?.let { BlockHistoryStore.record(p, number, it, now) }
            if (NotificationRateLimiter.shouldNotify(p, number, now)) {
                TrustNotifier.maybeNotify(this, number)
            }
            // Refresh Quick Settings tiles after block (requestListeningState).
            refreshTiles()
        }
        if (decision.warning == WarnReason.POLICE_IMPERSONATION ||
            decision.warning == WarnReason.POLICE_IMPERSONATION_HIGH) {
            val hqName = decision.warnPayload ?: "警察"
            if (number.isNotEmpty()) OutboundGuard.record(p, number, now)
            WarningNotifier.showPoliceWarning(this, number, hqName,
                highSeverity = decision.warning == WarnReason.POLICE_IMPERSONATION_HIGH)
        }
        if (decision.warning == WarnReason.HIGH_RISK_HOUR_DOMESTIC) {
            WarningNotifier.showHighRiskHourWarning(this, number)
        }
    }

    private fun refreshTiles() {
        android.service.quicksettings.TileService.requestListeningState(
            this, android.content.ComponentName(this, PauseTile::class.java))
        android.service.quicksettings.TileService.requestListeningState(
            this, android.content.ComponentName(this, FamilyCallbackTile::class.java))
    }

    private fun respond(details: Call.Details, ring: Boolean) {
        val response = CallScreeningService.CallResponse.Builder()
            .setDisallowCall(!ring)
            .setRejectCall(!ring)
            .setSkipCallLog(false)
            .setSkipNotification(!ring)
            .build()
        respondToCall(details, response)
    }

    private fun isVerificationFailed(details: Call.Details): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return false
        return details.callerNumberVerificationStatus ==
            android.telecom.Connection.VERIFICATION_STATUS_FAILED
    }

    private fun addToOutbound(p: SharedPreferences, number: String) {
        val set = p.getStringSet(KEY_OUTBOUND, emptySet()).orEmpty().toMutableSet()
        if (set.add(number)) {
            if (set.size > SpamCache.MAX_ENTRIES) {
                val iter = set.iterator()
                repeat(set.size - SpamCache.MAX_ENTRIES) { iter.next(); iter.remove() }
            }
            p.edit { putStringSet(KEY_OUTBOUND, set) }
        }
    }

    /**
     * Load registered family numbers and expand each to both its stored form
     * AND the E.164 equivalent (and vice versa), so a number saved as
     * "09012345678" matches an incoming "+819012345678" and vice versa.
     *
     * Without this, a JP user who stores "09012345678" but hasn't yet dialed
     * that number would have mom's call silenced — Android delivers incoming
     * calls in E.164 form, not domestic form.
     */
    private fun familyNumberSet(p: SharedPreferences, simIso: String?): Set<String> {
        val stored = (1..FamilyCallback.MAX_SLOTS).mapNotNull { i ->
            p.getString("family_$i", null)?.takeIf { it.isNotBlank() }
                ?.let { PhoneNumbers.normalize(it) }
                ?.takeIf { it.isNotEmpty() }
        }
        if (stored.isEmpty()) return emptySet()
        val cc = callingCodeOf(simIso) ?: return stored.toSet()
        val result = mutableSetOf<String>()
        for (num in stored) {
            result.add(num)
            when {
                // domestic → E.164: "09012345678" → "+819012345678"
                num.startsWith("0") && !num.startsWith("+") ->
                    result.add("+$cc${num.substring(1)}")
                // E.164 → domestic: "+819012345678" → "09012345678"
                num.startsWith("+$cc") ->
                    result.add("0${num.removePrefix("+$cc")}")
            }
        }
        return result
    }

    private fun simCountryIso(): String? {
        val tm = getSystemService(TelephonyManager::class.java) ?: return null
        // Use simCountryIso (SIM card's home country) rather than networkCountryIso
        // (serving network). While roaming, networkCountryIso returns the visited
        // country; a JP SIM in the US would return "US", causing all JP calls to be
        // silenced as FOREIGN_GENERIC. simCountryIso is always the home country.
        val iso = tm.simCountryIso.takeIf { it.isNotEmpty() }
            ?: tm.networkCountryIso.takeIf { it.isNotEmpty() }  // fallback for eSIM edge cases
        return iso?.uppercase(Locale.ROOT)
    }

    private fun normalize(raw: String): String = PhoneNumbers.normalize(raw)

    private fun recordBlock(n: String) {
        val p = prefs
        val count = p.getInt(KEY_COUNT, 0) + 1
        val weekCount = p.getInt(WeeklyDigest.KEY_WEEK_COUNT, 0) + 1
        p.edit {
            putInt(KEY_COUNT, count)
            putInt(WeeklyDigest.KEY_WEEK_COUNT, weekCount)
            putLong(KEY_LAST_TS, System.currentTimeMillis())
            putString(KEY_LAST_NUM, n)
        }
    }

    private val prefs: SharedPreferences
        get() = getSharedPreferences(PREFS, MODE_PRIVATE)

    companion object {
        const val PREFS = "orange_apple"
        const val KEY_OUTBOUND = "outbound"
        const val KEY_SPAM = "spam"
        const val KEY_COUNT = "count"
        const val KEY_LAST_TS = "last_ts"
        const val KEY_LAST_NUM = "last_num"
    }
}

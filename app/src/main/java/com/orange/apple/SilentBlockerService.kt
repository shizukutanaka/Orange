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
        // Never record emergency numbers (110, 119, etc.) as outbound-known. A user
        // who calls 110 to verify a police number should still get the spoofing warning
        // on a subsequent call from someone impersonating police. If we record 110 as
        // outbound-known, all future police calls ring with no warning.
        if (EmergencyWhitelist.isEmergency(number)) return

        addToOutbound(p, number)
        // Expand domestic↔E.164 variants: incoming blocked calls may have been stored
        // in a different format than what the outgoing dial delivers.
        if (number.isNotEmpty()) {
            val cc = callingCodeOf(simCountryIso())
            val variants = phoneVariants(number, cc)
            // Warn if the number was previously blocked/warned (OutboundGuard, 24h), OR
            // if it matches a recent short-ring (WangiriTracker, 6h). The Wangiri check
            // covers the case where the user dials back a bait number before Orange sees
            // the callback — e.g., a 1-second ring at 10:00, user calls back at 10:01.
            // OutboundGuard only fires after Orange has formally blocked something; without
            // the WangiriTracker check, this callback window is a silent blind spot.
            val wangiriCandidates = WangiriTracker.snapshot(p, now)
            val flagged = variants.any { v ->
                OutboundGuard.wasRecentlyFlagged(p, v, now) || wangiriCandidates.containsKey(SpamCache.hash(p, v))
            }
            if (flagged) WarningNotifier.showOutboundWarning(this, number)
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

        // Build trusted sets early. Family numbers + outbound-known + bundled businesses
        // must never accumulate toward the repeat-caller threshold and must bypass it.
        //
        // A number is dialed/stored in domestic form ("09012345678") but Android
        // delivers the matching INCOMING call in E.164 ("+819012345678"), and vice versa
        // for bundled businesses (CSV stores E.164 but carrier may deliver domestic form).
        // Check every variant of the incoming number against all trusted sets so either
        // stored form matches — without this, a number the user or directory trusts is
        // silenced (or triggers a false PostCallAdvisor advisory after a legit call).
        val cc = callingCodeOf(simCountryIso())
        val outbound = p.getStringSet(KEY_OUTBOUND, emptySet()).orEmpty()
        val family = familyNumbers(p)
        val businesses = BusinessDirectoryBundle.load(this).keys
        val variants = phoneVariants(number, cc)
        if (number.isNotEmpty() && variants.any {
                SpamCache.hash(p, it) in outbound || it in family || it in businesses
            }) {
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

        val wangiriSnapshot = WangiriTracker.snapshot(p, now)
        val wangiriRingAt = phoneVariants(number, cc).firstNotNullOfOrNull { variant ->
            wangiriSnapshot[SpamCache.hash(p, variant)]
        }

        val state = CallState(
            // outbound bypass is handled above via hash lookup (line 109-113).
            // outboundKnown in CallState now carries only family numbers so
            // decide() can still short-circuit for family on the post-early-return path.
            outboundKnown     = family,
            // Check all domestic↔E.164 variants against the spam cache. A number
            // blocked in domestic form ("09012345678") is hashed and stored that way;
            // when the same caller rings back in E.164 ("+819012345678"), the exact-
            // string hash misses. Checking variants ensures the Layer-6 fast-path fires
            // regardless of which format the carrier delivers this time.
            isSpamCached      = variants.any { SpamCache.contains(p, it) },
            knownBusinesses   = businesses,
            pausedUntilMillis = if (PauseTile.isPaused(p)) p.getLong(PauseTile.KEY_PAUSED_UNTIL, 0L) else 0L,
            wangiriRingAt     = wangiriRingAt,
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
               filter == android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY ||
               filter == android.app.NotificationManager.INTERRUPTION_FILTER_ALARMS
    }

    private fun handleDecision(
        p: SharedPreferences, decision: Decision, number: String, now: Long
    ) {
        if (decision.verdict == Verdict.SILENCE) {
            recordBlock()
            if (number.isNotEmpty()) OutboundGuard.record(p, number, now)
            if (decision.reason == BlockReason.WANGIRI_CALLBACK) {
                // Expand domestic↔E.164 variants: the short-ring may have been stored
                // in a different format than the callback number (carrier inconsistency).
                // Forget all variants so the stale entry doesn't linger until window expiry.
                val wfCc = callingCodeOf(simCountryIso())
                phoneVariants(number, wfCc).forEach { WangiriTracker.forget(p, it) }
            }
            // Remember this number so a repeat call is silenced instantly by the
            // Layer-6 spam-cache lookup (the cache's only writer — without this,
            // Layer 6 never fires and the Restore-removes-from-cache path is moot).
            // Contextual silences (DND_HONOR) are excluded; see isCacheableSilence.
            // Store ALL domestic↔E.164 variants so the fast-path fires regardless of
            // which format the carrier delivers on the next call from the same number.
            if (number.isNotEmpty() && decision.reason?.let(::isCacheableSilence) == true) {
                val cacheCc = callingCodeOf(simCountryIso())
                phoneVariants(number, cacheCc).forEach { v -> SpamCache.add(p, v) }
            }
            decision.reason?.let { BlockHistoryStore.record(p, number, it, now) }
            if (NotificationRateLimiter.shouldNotify(p, number, now)) {
                try {
                    TrustNotifier.maybeNotify(this, number)
                } catch (_: Exception) {
                    // Notification failure must not prevent the block from being counted,
                    // the block history from being recorded, or the widget from refreshing.
                }
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
            // Record in OutboundGuard so if the user calls back, they see the
            // outbound-warning notification — same behaviour as police warnings.
            if (number.isNotEmpty()) OutboundGuard.record(p, number, now)
            WarningNotifier.showHighRiskHourWarning(this, number, callingCodeOf(simCountryIso()))
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
        if (number.isEmpty()) return
        val hash = SpamCache.hash(p, number)
        val set = p.getStringSet(KEY_OUTBOUND, emptySet()).orEmpty().toMutableSet()
        if (set.add(hash)) {
            if (set.size > MAX_OUTBOUND_ENTRIES) {
                val iter = set.iterator()
                repeat(set.size - MAX_OUTBOUND_ENTRIES) { iter.next(); iter.remove() }
            }
            p.edit { putStringSet(KEY_OUTBOUND, set) }
        }
    }

    /**
     * Load registered family numbers in normalized (ASCII) form. Variant
     * matching (domestic ↔ E.164) is handled by phoneVariants() at the call
     * site against the incoming number, so this only needs to surface the
     * stored forms.
     */
    private fun familyNumbers(p: SharedPreferences): Set<String> =
        (1..FamilyCallback.MAX_SLOTS).mapNotNull { i ->
            p.getString("family_$i", null)?.takeIf { it.isNotBlank() }
                ?.let { PhoneNumbers.normalize(it) }
                ?.takeIf { it.isNotEmpty() }
        }.toSet()

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

    private fun recordBlock() {
        val p = prefs
        val count = p.getInt(KEY_COUNT, 0) + 1
        val weekCount = p.getInt(WeeklyDigest.KEY_WEEK_COUNT, 0) + 1
        p.edit {
            putInt(KEY_COUNT, count)
            putInt(WeeklyDigest.KEY_WEEK_COUNT, weekCount)
        }
        // Notify widget of the updated count so it reflects changes immediately
        // instead of waiting for the 30-minute automatic refresh timer.
        OrangeWidget.notifyUpdate(this)
    }

    private val prefs: SharedPreferences
        get() = getSharedPreferences(PREFS, MODE_PRIVATE)

    companion object {
        const val PREFS = "orange_apple"
        const val KEY_OUTBOUND = "outbound"
        const val KEY_SPAM = "spam"
        const val KEY_COUNT = "count"
        // Bound for the set of numbers the user has dialled (Layer 5 bypass).
        // Independent of SpamCache.MAX_ENTRIES — different data with different
        // cardinality expectations. 1,000 covers a realistic lifetime of dialled
        // contacts while preventing unbounded SharedPreferences growth.
        const val MAX_OUTBOUND_ENTRIES = 1_000
    }
}

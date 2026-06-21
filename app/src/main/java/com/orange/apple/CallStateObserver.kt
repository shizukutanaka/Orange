package com.orange.apple

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import androidx.core.content.edit

/**
 * Single broadcast receiver for Android PHONE_STATE changes.
 *
 * Handles two concerns that share the same signal source:
 *
 *   (A) **Wangiri detection** — RINGING → IDLE in <6 seconds = short-ring
 *       callback bait. Feeds WangiriTracker so the decision engine can
 *       silence the next call from the same number.
 *
 *   (B) **Outbound-call logging** — IDLE → OFFHOOK without a prior RINGING
 *       = user placed an outgoing call. Adds the number to the outbound-
 *       known set so future incoming calls from the same number always ring.
 *
 * THE BUG THIS FIXED (B): the decision engine's Layer 3 (outbound-known)
 * checked a set that was never populated from actual outgoing calls. A user
 * who called a foreign number and then received a callback would have the
 * callback silenced — the opposite of the documented behavior.
 *
 * State machine (combined):
 *
 *   IDLE → RINGING         : set wasRinging=true, capture number + timestamp
 *   RINGING → OFFHOOK      : user answered inbound; clear wangiri candidate
 *   RINGING → IDLE          : if elapsed < 6s → record wangiri candidate
 *   IDLE → OFFHOOK          : outbound call → add number to outbound-known
 *   OFFHOOK → IDLE          : call ended; clear wasRinging
 *
 * Privacy: no READ_PHONE_STATE permission. On Android 12+, the broadcast
 * carries the phone number without that permission. On earlier versions,
 * the number may be null — both features degrade gracefully (wangiri
 * tracker gets no candidate, outbound-known set doesn't grow).
 */
class CallStateObserver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            ?: intent.getStringExtra("android.intent.extra.PHONE_NUMBER")

        val prefs = ctx.getSharedPreferences(SilentBlockerService.PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> onRinging(prefs, number, now)
            TelephonyManager.EXTRA_STATE_OFFHOOK -> onOffhook(prefs, number)
            TelephonyManager.EXTRA_STATE_IDLE    -> onIdle(prefs, now, ctx)
        }
    }

    private fun onRinging(prefs: android.content.SharedPreferences, number: String?, now: Long) {
        // Clear state from any previous call that may have been orphaned by a
        // process death between OFFHOOK and IDLE: a stale KEY_ANSWER_TIME would
        // cause the next IDLE to compute a huge duration and fire a spurious
        // PostCallAdvisor notification about a call from hours/days ago.
        prefs.edit {
            putBoolean(KEY_WAS_RINGING, true)
            remove(KEY_ANSWER_TIME)
            if (!number.isNullOrEmpty()) {
                val norm = normalize(number)
                putLong(KEY_RING_START, now)
                putString(KEY_RING_NUMBER, norm)
            }
        }
    }

    private fun onOffhook(prefs: android.content.SharedPreferences, number: String?) {
        val wasRinging = prefs.getBoolean(KEY_WAS_RINGING, false)
        if (wasRinging) {
            // Prefer the number from the intent; fall back to the number captured
            // during RINGING if the OFFHOOK intent doesn't carry it (carrier-dependent).
            val rawNum = number?.takeIf { it.isNotEmpty() }
                ?: prefs.getString(KEY_RING_NUMBER, null)
            val norm = rawNum?.let { normalize(it) }
            if (norm != null) RepeatCallerTracker.clear(prefs, norm)
            val answerTime = System.currentTimeMillis()
            prefs.edit {
                putLong(KEY_ANSWER_TIME, answerTime)
                remove(KEY_RING_START)
            }
        } else if (!number.isNullOrEmpty()) {
            addToOutbound(prefs, normalize(number))
        }
    }

    private fun onIdle(prefs: android.content.SharedPreferences, now: Long, ctx: Context) {
        val ringStart  = prefs.getLong(KEY_RING_START, 0L)
        val ringNumber = prefs.getString(KEY_RING_NUMBER, null)
        val answerTime = prefs.getLong(KEY_ANSWER_TIME, 0L)

        if (ringStart > 0 && !ringNumber.isNullOrEmpty()) {
            val elapsed = now - ringStart
            if (elapsed in 1..WangiriTracker.SHORT_RING_THRESHOLD_MS) {
                WangiriTracker.record(prefs, ringNumber, now)
            }
        }

        // PostCallAdvisor: if the call was answered and lasted >30 s,
        // show the #9110 safety sheet for unknown numbers.
        if (answerTime > 0 && !ringNumber.isNullOrEmpty()) {
            val duration = now - answerTime
            PostCallAdvisor.maybeShow(ctx, ringNumber, duration)
        }

        prefs.edit {
            putBoolean(KEY_WAS_RINGING, false)
            remove(KEY_RING_START)
            remove(KEY_RING_NUMBER)
            remove(KEY_ANSWER_TIME)
        }
    }

    private fun addToOutbound(prefs: android.content.SharedPreferences, number: String) {
        if (number.isEmpty()) return
        // Same guard as SilentBlockerService.handleOutgoing(): never record emergency numbers
        // (110, 119, etc.) as outbound-known. A user who dials 110 to verify a suspicious call
        // must still see the impersonation warning if someone later spoofs that number.
        if (EmergencyWhitelist.isEmergency(number)) return
        val set = prefs.getStringSet(SilentBlockerService.KEY_OUTBOUND, emptySet())
            .orEmpty().toMutableSet()
        if (set.add(number)) {
            // Bound at MAX_OUTBOUND_ENTRIES (same cap as SilentBlockerService.addToOutbound)
            if (set.size > SilentBlockerService.MAX_OUTBOUND_ENTRIES) {
                val iter = set.iterator()
                val excess = set.size - SilentBlockerService.MAX_OUTBOUND_ENTRIES
                repeat(excess) { iter.next(); iter.remove() }
            }
            prefs.edit { putStringSet(SilentBlockerService.KEY_OUTBOUND, set) }
        }
    }

    private fun normalize(raw: String): String = PhoneNumbers.normalize(raw)

    companion object {
        internal const val KEY_WAS_RINGING = "was_ringing"
        private const val KEY_RING_START  = "ring_start"
        private const val KEY_RING_NUMBER = "ring_number"
        private const val KEY_ANSWER_TIME = "answer_time"
    }
}

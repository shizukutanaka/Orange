package com.orange.apple

/**
 * Apple philosophy: some rules must be absolute.
 *
 * Competitors (Truecaller/Whoscall/Hiya) all allow emergency numbers through
 * implicitly via their "allow contacts" path, but none of them document a
 * hard-coded bypass that survives every other rule, every pause state,
 * every user toggle. A silenced 110 is a killed user.
 *
 * This list is checked FIRST in SilentBlockerService, before pause state,
 * before spam cache, before foreign-unsolicited. It cannot be disabled,
 * there is no setting to edit it, and the app UI never mentions it.
 * The correct design for a safety-critical feature is: the user never
 * knows it's there, and it never fails.
 *
 * List source: ITU-T E.161 + national emergency directories, current as of
 * 2026-04. Expand only if a number actually falls through in the wild;
 * do not speculatively add.
 */
internal object EmergencyWhitelist {

    private val numbers: Set<String> = buildSet {
        // Japan
        add("110")  // 警察 Police
        add("119")  // 消防/救急 Fire & Ambulance
        add("118")  // 海上保安庁 Coast Guard
        add("189")  // 児童相談所虐待対応ダイヤル
        add("171")  // 災害用伝言ダイヤル

        // International common emergencies (reachable when roaming)
        add("911")  // US/Canada
        add("112")  // EU/GSM universal
        add("999")  // UK/HK/IE
        add("000")  // Australia

        // Extended-format variants the OS may deliver when roaming
        add("+81110"); add("+81119"); add("+81118")
        add("+81189"); add("+81171")   // 児童相談所 / 災害用伝言 international form
        add("+1911")                   // US 911 as dialed by some MVNO SIMs
        add("+44999")                  // UK 999 as dialed by some MVNO SIMs
        add("+61000")                  // AU 000 international form
        add("+112")                    // EU 112 bare (no national prefix)
    }

    /**
     * Returns true when the number is an emergency line and must be allowed
     * through regardless of any other rule. Null/empty input is safe (returns false)
     * since the screener falls back to allow when the handle is absent.
     */
    fun isEmergency(normalized: String): Boolean =
        normalized in numbers
}

package com.orange.apple

/**
 * Single source of truth for "as of when" every bundled, install-time-frozen
 * directory was last verified: PoliceStationDirectory, TaxAgencyDirectory,
 * BusinessDirectoryBundle (business_directory.csv), ScamPrefixSeed.
 *
 * Why this exists: Orange's core promise is "works forever, fully offline" —
 * no servers, no updates, no telemetry. That promise has a cost the user
 * never sees: every directory is frozen at whatever date the APK was built.
 * A scammer who starts spoofing a not-yet-covered number next month is
 * invisible to anyone who installed before the next app update ships.
 *
 * Rather than let "offline forever" silently imply "always current," Orange
 * shows this date in Settings so the user can judge staleness themselves —
 * the same honesty the app already applies to its own limits (see
 * CaribbeanPremiumNANP/ScamPrefixSeed KDoc: "this is a SEED, not a blocklist").
 *
 * This intentionally reflects the OLDEST verification date among the bundled
 * directories, not the newest — showing the newest date would overstate
 * freshness for data that hasn't actually been re-checked in longer.
 * As of this constant's last edit: PoliceStationDirectory/TaxAgencyDirectory/
 * business_directory.csv are 2026-06; ScamPrefixSeed/EmergencyWhitelist are
 * 2026-04 — the older of the two floors this value.
 *
 * Update this whenever any bundled directory's source data changes:
 * PoliceStationDirectory, TaxAgencyDirectory, business_directory.csv,
 * ScamPrefixSeed.elevatedRiskCountryCodes, EmergencyWhitelist.
 */
internal object ProtectionDataVersion {
    const val LAST_UPDATED = "2026-04"
}

# Orange — Privacy Manifesto

## Seven things Orange does NOT do

Competitors in this category — Truecaller, Whoscall, Hiya, and several
Japanese alternatives — all make similar promises about blocking unwanted
calls. Behind those promises, almost all of them ask for something a call
blocker doesn't actually need. Orange is defined by what it refuses to ask for.

1. **Orange does not read your contacts.**
   No READ_CONTACTS permission. Not at install. Not later. When a competitor
   asks "may we access your contacts to build a better caller-ID database,"
   what they're really asking is: "may we upload every phone number, name,
   email address, and profile photo of every person you've ever saved — none
   of whom consented — to our servers." Orange asks a different question:
   "does the number match a legitimate business from a curated, publicly
   sourced directory we ship inside the app?" Same outcome for the user, zero
   surveillance of the people the user happens to know.

2. **Orange does not talk to any server.**
   No INTERNET permission. There is no "our backend." There is no telemetry,
   no crash reports, no A/B testing, no analytics SDK, no advertising SDK,
   no push-notification server. The app you install is the entire app. It
   runs on your phone and nowhere else.

3. **Orange does not upload the numbers that called you.**
   Truecaller and similar apps grew their databases by silently recording
   which numbers dial which users and syncing that graph to their servers.
   Orange's spam cache is stored only in your device's app-local preferences
   and is never transmitted. The 2020 Truecaller incident — 47.5 million
   records offered on a dark-web marketplace for $1,000 — is impossible under
   Orange's architecture because there is no central dataset to breach.

4. **Orange does not have an account.**
   No sign-in. No email. No phone-number verification. No password. No OAuth.
   No "sync across devices." The app works the moment you grant call-screening
   role, and if you uninstall it, nothing about you remains anywhere.

5. **Orange does not monetize the user.**
   No ads. No subscription. No premium tier. No in-app purchases. No referral
   bounties. No "unlock advanced features" gate. The current build exists to
   prove the thesis that a call blocker can be free and not parasitic. If
   monetization ever happens, it will be user-paid, disclosed up front, and
   will never require new data collection.

6. **Orange does not phone home to check anything.**
   The business directory is bundled into the APK at build time. Country-code
   tables are compiled in. The scam-prefix seed is a constant. Updates ship
   through the app store, the same channel that shipped the original code,
   audited by the same signature. There is no "silent rule update" path we
   could compromise or that an attacker could compromise for us.

7. **Orange does not override emergency numbers.**
   110, 119, 118, 911, 112, 999, 000, and regional equivalents are hard-coded
   to pass through every rule, every pause state, every user action. There
   is no setting to change this because a setting to block an ambulance is
   an anti-feature. A silenced 119 is a killed user.

8. **Orange's optional DND integration reads one number, does nothing else.**
   When your device Do Not Disturb is active, Orange checks the current
   interrupt-filter level (a single integer) and silences unknown domestic
   callers in addition to DND's own rules. It does not read your DND
   priority contacts list; no contacts are accessed. Numbers you have
   previously dialled still ring through.

9. **Orange's post-call advisory is a local notification, not telemetry.**
   After you answer an unknown number for more than 30 seconds, Orange
   posts a low-priority, silent notification listing three public hotlines
   (#9110, 188, 0120-210-364). Nothing is sent to any network. The
   notification fires from a local timer, is rate-limited to once per 24
   hours per number, and can be ignored without consequence.

10. **Orange does not request READ_CALL_LOG.**
    An earlier design considered requesting it — accessing the incoming
    number from a `PHONE_STATE` broadcast can require it on some OS
    versions — but it turned out to be unnecessary. Orange's two data paths
    for a phone number both avoid it entirely:

    - **The screener itself** gets the number from `Call.Details`, supplied
      directly by the `CallScreeningService` API once you grant the
      `ROLE_CALL_SCREENING` role. No log-reading permission of any kind is
      involved.
    - **Wangiri "short-ring callback" detection** (a fraudulent call rings
      briefly and hangs up hoping you call back a premium-rate number) uses
      a separate `PHONE_STATE` broadcast receiver (`CallStateObserver`) that
      reads the number straight from the broadcast's `EXTRA_INCOMING_NUMBER`
      extra. On Android 12+ this is delivered without any extra permission;
      on earlier versions the extra may simply be absent, and Wangiri
      detection degrades gracefully (no candidate recorded) rather than
      requesting a broader permission to cover the gap.

    Either way, the only data path for a Wangiri candidate is: incoming
    number → in-memory candidate, keyed by a salted hash → discarded after
    6 hours if no callback arrives. Orange's actual permission list is
    exactly two entries — `POST_NOTIFICATIONS` and `RECEIVE_BOOT_COMPLETED`
    — and `docs/play_data_safety.json` is kept in lockstep with the
    manifest as the enforced source of truth.

## What Orange does do, once

Exactly three things touch anything outside the app:

- **At install**: Android grants the CallScreening role (user-initiated).
- **At each call**: Android delivers the incoming number to the screener.
- **At Google Play update**: the app store delivers signed new versions.

Everything else is the app thinking to itself.

## Why this is the right shape of the product

The category's biggest apps grew on a trade: "give us your social graph, we'll
tell you who's calling." That trade was acceptable in 2010 and is unacceptable
in 2026. Privacy regulation (GDPR, APPI, CCPA) has caught up; user sentiment
has caught up; enterprise customers have caught up; the opportunity is a
product that makes the trade unnecessary.

Orange is the call blocker that doesn't exist because a call blocker doesn't
need to be the thing that currently exists.

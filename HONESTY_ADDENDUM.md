# Orange — Honesty Addendum

Companion to PRIVACY_MANIFESTO.md. Rams Principle #6 (Honest): a good
product does not manipulate users with promises of features that do not
hold up. Competitors quietly claim "blocks all spam" or "stops scam
calls" and then recover the gap via unreadable support docs. Orange does
the reverse: we publish what gets through, so users can decide before
install whether our coverage matches their risk.

## What Orange catches today

Calls blocked before the ring:

1. **Withheld caller ID (非通知)** — if the caller hides their number,
   Orange silences the call. This is a major vector for 還付金詐欺 and
   架空料金請求詐欺 in Japan.
2. **International calls from non-home countries to JP users** — unless the
   number is in the user's own outbound history or the bundled business
   directory. Highest-risk corridors (675, 7, 86, 44, 212, 234, 63, 39)
   are blocked even when the callee is outside Japan.
3. **Numbers the user previously blocked** — stored only on the device,
   never transmitted.
4. **Wangiri callback pattern (classic + 2.0)** — if a number rang for
   under 15 seconds (unanswered) and calls back within 6 hours, the
   callback is silenced. The 15-second window catches both classic 1-ring
   Wangiri and "Wangiri 2.0" where a brief recorded message plays before
   disconnect.
5. **Structurally impossible JP numbers** — numbers that violate the MIC
   numbering plan (02x prefix, wrong digit count for 060/070/080/090
   mobile or 0120/0800 toll-free or 050 IP, 0990 premium outbound, 8+
   consecutive identical digits).
6. **Carrier-verified spoofed calls (STIR/SHAKEN)** — on Android 11+, if
   the carrier explicitly reports VERIFICATION_STATUS_FAILED, Orange
   silences the call. Note: Japan has not deployed STIR/SHAKEN as of 2026;
   this layer is primarily useful for calls routed via US/Canada carriers.
7. **International premium-rate and network numbers** — +800 (intl
   freephone, abused for reverse-charge scams), +979 (intl premium rate),
   +882/+883 (intl networks), and 22 Caribbean/Atlantic NANP premium area
   codes (+1-242, +1-876, +1-809, etc.) are all silenced.

Calls that ring but trigger a warning:

8. **Police HQ impersonation detection** — if the caller ID matches one of
   the 47 prefectural police HQ representative numbers, the call RINGS
   (we never block police) but the user sees a high-priority notification:
   "○○警察の番号です。偽装の可能性。一度切って #9110 にかけ直してください"
   with a "家族に連絡" button if a family number is pre-set.

Outgoing call protection:

9. **Outbound guard** — if the user dials a number that was blocked or
   warned about within the last 24 hours, a notification warns them before
   the call connects. The call is never blocked (user's agency is sacred),
   but the warning has a "家族に連絡" button.
10. **Family callback system** — up to 3 family phone numbers can be
    pre-set (entered manually, no READ_CONTACTS). A Quick Settings tile
    provides one-tap dial to the primary family number. Every warning
    notification includes a "家族に連絡" action button.

## What Orange does NOT catch

Calls that will still ring through:

1. **Domestic JP spoofing with structurally valid numbers** — a scammer
   presenting a fake JP caller ID that happens to conform to the MIC
   numbering plan (correct prefix, correct digit count, no repeating
   digits) looks identical to a legitimate JP caller. The structural
   detector catches perhaps 30–50% of domestic spoofing; the STIR/SHAKEN
   layer catches some of the rest on supporting carriers; but neither
   achieves 100% without server-side data we refuse to collect.
2. **Unknown domestic numbers that turn out to be sales/marketing calls** —
   if it's a real JP number a real JP business owns, Orange lets it ring.
   This is by design: the false-positive cost of blocking "the hospital
   rescheduling your appointment" is far higher than the false-negative
   cost of one sales call.
3. **International numbers from user's outbound history** — we trust your
   history. If your cousin called you back from Brazil yesterday, Orange
   will allow their next call even if +55 later becomes a scam corridor.
   You can explicitly mark such numbers as spam and the cache will block
   them going forward.
4. **Bundled business numbers** — the CSV of known-legit business lines
   is auto-whitelisted. If a scammer ever acquires a number we bundled,
   their call will ring. We audit the CSV before shipping each version,
   but a number that was legitimate at ship time and compromised later
   will temporarily slip through until the next app update.
5. **Carriers' own marketing calls** — MNO and MVNO operators sometimes
   call from numbers they own, which may be in the bundle. Those ring.
6. **Calls during the 1-hour pause window** — if you tapped the Quick
   Settings tile, everything rings until the hour expires.
7. **Calls from spoofed emergency numbers** — We hard-allow the
   emergency list. A scammer spoofing 110 would ring through. We accept
   this because silencing 110 to stop the 0.001% scammer would kill real
   users calling for help. Trade-off is intentional and not negotiable.
8. **Wangiri with >6h delay** — if the callback comes more than 6 hours
   after the short ring, the window has expired and the call rings. We
   accept this because a 24-hour window would produce too many false
   positives from legitimate callers with bad signal.
9. **Withheld calls during the 1-hour pause window** — Orange's pause
   tile (Layer 2) overrides the withheld-number rule (Layer 3). If you
   tap pause to allow a restricted-number callback (e.g., from a hospital),
   all withheld calls ring for that hour. This is by design: pause means
   "everything rings, no exceptions, until the hour is up."

10. **All calls when DND is active** — if you have Do Not Disturb enabled
    on your device, Orange silences unknown domestic callers as well (Layer 15).
    Outbound-known numbers and business-bundle numbers still ring through.
    Pause tile overrides DND mode as well.

11. **Known contacts who have previously rung more than 3 times in 60 minutes** —
    the repeat-caller tracker counts rings per number within a rolling 60-minute
    window. If a family member tries to reach you more than three times in an hour
    and you have not dialed their number before, the fourth attempt is silenced.
    Add the number to your outbound history by dialing them once and Orange will
    never apply the repeat-caller rule to them again.

12. **"Allow" from block history uses last-4-digit matching** — history entries
    show only the last 4 digits (e.g. ****5678). When you tap Allow, Orange
    permanently allows any future caller whose number ends in those 4 digits —
    not just the specific number. A scammer who learns your allowed suffix can
    craft a number ending in those digits and ring through. We accept this
    trade-off because storing or displaying the full number would expose a
    plaintext PII list. Exact-number Restore is available only via the per-block
    "Restore" notification during the first 7 days (full hash available then).

13. **The #9110 post-call advisory appears for all unknown callers, not just fraud** —
    after any call longer than 30 seconds from an unknown number, Orange posts a
    low-priority notification with #9110 / 188 / 0120-210-364. This fires for
    legitimate survey calls, charity solicitations, and anything else where the
    number was not in your outbound history. It is a low-priority, silent
    notification — you will not see it unless you pull down your notification
    shade.

## What we will never add to catch more

We could reach ~99% if we did any of the following. We won't:

- **Crowdsource the spam DB** — requires uploads. Violates the manifesto.
- **Voice-based AI screener** — requires mic permission and either a
  server or ~500MB of on-device LLM weights. Breaks the <200KB APK goal.
  If users want that, Google and Hiya already ship it.
- **Read contacts** — would give us a bigger personal-allow list at the
  cost of your address book's privacy. No.
- **Recording** — legally radioactive in JP (one-party consent is not
  settled), and outside the product's purpose.

## How we measure ourselves

The only metric Orange tracks locally (never transmitted):
- Number of calls blocked since install (shown on widget)

We do NOT track:
- Which numbers were blocked (not retained beyond the spam cache)
- How often Restore is tapped
- How often the app is opened
- Which locale, device, carrier, Android version

If you'd like us to be better at measurement, contribute an open PR to
add strictly local, strictly opt-in, strictly off-by-default telemetry.
The bar is high on purpose.

## Changes since last audit

- 2026-04: Initial publication. No prior versions to diff.

Future versions of this document will append dated changelog entries,
not rewrite. Rams #6 applies to the document itself: we don't get to
retroactively smooth over misses.

# Security Policy

## Reporting a vulnerability

If you believe you have found a security vulnerability in Orange, please
**do not open a public GitHub issue**. Instead, use one of:

- **GitHub private security advisory**: navigate to the repository →
  Security → Advisories → "Report a vulnerability". This creates a
  private thread between you and the maintainers.
- **Email**: find the maintainer contact in the repository's README or
  GitHub profile. Do not post your email address in this public file.

Include:

- A description of the issue
- Steps to reproduce
- The version of Orange affected (versionName from the Play Store
  "App info" page, or `versionName` in `app/build.gradle.kts`)
- Your expectations of severity

We will:

- Acknowledge receipt within 7 days
- Provide an initial assessment within 14 days
- Coordinate disclosure timing around a fix landing in a release
- Credit you in the release notes unless you prefer anonymity

## What counts as a vulnerability

High severity:
- Any path that causes Orange to silence an emergency number (110/119/911/112)
- Any path that exfiltrates user data off-device (in a product that
  claims zero network — `INTERNET` permission is not declared)
- Any path that allows an external app to read or modify Orange's
  SharedPreferences (which contains the spam cache and outbound history)

Medium severity:
- Bypasses of the privacy manifest's claims (e.g. a transitive dependency
  that opens a network socket)
- Crashes in the `onScreenCall()` callback that prevent any screening
  decision, causing all calls to ring unscreened

Low severity:
- UI glitches, i18n issues, documentation errors

## What we will fix vs. what is by design

Orange's `HONESTY_ADDENDUM.md` explicitly documents the classes of calls
that **still ring by design**: structurally-valid domestic spoofs, calls
during a user-initiated pause, and calls from numbers in the outbound-known
set. Reports about these behaviors are not security issues.

## Out of scope

- Device-level attackers with root access
- Spoofing of caller IDs that our documented rules do not claim to catch
- Social engineering that targets the user rather than the app
- Denial-of-service against the device itself (outside Orange's control)

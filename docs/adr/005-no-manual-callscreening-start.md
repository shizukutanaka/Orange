# ADR 005 — CallScreeningService must not be started manually

**Date:** 2025-05
**Status:** Accepted

## Context

`OnboardingActivity.finishToSilent()` called
`startService(Intent(this, SilentBlockerService::class.java))` after the user
granted the call-screening role. `SilentBlockerService` is a
`CallScreeningService`, which the Android Telecom framework binds
automatically once `ROLE_CALL_SCREENING` is held.

Two problems:

1. **Wrong lifecycle.** A `CallScreeningService` is bound by the system on
   demand (per incoming call). It is not a started service. Calling
   `startService()` on it does not make screening work and is semantically
   meaningless.
2. **Crash risk on Android 8+.** Starting a background service from an
   Activity that is finishing can throw `IllegalStateException` /
   `BackgroundServiceStartNotAllowedException` under the background
   execution limits introduced in Android 8 and tightened through
   Android 12–16. F-Droid/Play target is API 35 (Android 15); this code
   path was a latent crash.

## Decision

Remove the `startService()` call entirely. Granting the role is sufficient;
Telecom binds the service when the next call arrives. The onboarding flow now
only schedules the weekly digest and finishes the task.

## Consequences

- No background-start crash on modern Android.
- Screening still works: it was always the role grant, never the manual start,
  that enabled it.
- Removed the now-unused `android.content.Intent` import.
- Verified against Android 15/16 foreground-service restrictions (BOOT_COMPLETED
  receivers may not launch most FGS types; Orange launches none).

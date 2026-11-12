# Orange — F-Droid Submission: Readiness and Remaining Steps

Prepared 2026-11-12 (owner decision: F-Droid first). This file records what
is already in place in this repository, what is still missing, and the
fdroiddata submission steps that happen **outside** this repository.
Nothing here has been submitted anywhere yet.

## Already in place (verified in this repo)

- **License**: MIT (`LICENSE`, "Orange contributors"). FOSS, satisfies
  F-Droid's inclusion policy.
- **Identity**: `applicationId = "com.orange.apple"`,
  `versionName = "1.6.0"`, `versionCode = 3` in `app/build.gradle.kts`.
  The git tag `v1.6.0` exists and matches.
- **Build recipe draft**: `metadata/com.orange.apple.yml` is a complete
  fdroiddata-format metadata file (Categories, License, SourceCode,
  IssueTracker, a `Builds:` entry for 1.6.0 / code 3 at commit `v1.6.0`,
  `AutoUpdateMode: Version`, `UpdateCheckMode: Tags`).
  `tools/check_comprehensive.sh` section 12 keeps its CurrentVersion /
  CurrentVersionCode in lock-step with the Gradle build, so it cannot
  silently drift.
- **Listing text (fastlane)**: `fastlane/metadata/android/` has
  `title.txt`, `short_description.txt`, `full_description.txt` for
  **en-US** and **ja-JP**. Content is the same copy as `STORE_LISTING.md`.
- **Changelogs**: canonical copies live in
  `fastlane/metadata/android/changelogs/<versionCode>.txt` (their presence
  is asserted by `check_comprehensive.sh`). As of this change, byte-identical
  copies also live in `fastlane/metadata/android/en-US/changelogs/`, which
  is the layout F-Droid actually reads
  (`<locale>/changelogs/<versionCode>.txt`).
- **Policy fit**: the app requests no INTERNET permission at all
  (`tools/check_no_network.sh` enforces this), has no analytics or
  advertising SDKs, and depends only on AndroidX / Jetpack Compose
  (all FOSS). The release build already zeroes APK timestamps and strips
  the dependency-metadata signing block, per F-Droid requirements
  (see comments in `app/build.gradle.kts`).

## Still missing (cannot be produced without a device / design source)

1. **Screenshots** — none exist anywhere in the repo. F-Droid pulls them
   from `fastlane/metadata/android/en-US/images/phoneScreenshots/`.
   `STORE_LISTING.md` already specifies the three shots to take
   (Protect screen, block notification, widget). They must be real
   captures of the running app; do not fabricate them.
2. **512×512 icon** — the largest icon in the repo is the 192×192
   launcher PNG (`app/src/main/res/mipmap-xxxhdpi/ic_launcher.png`).
   F-Droid will extract the icon from the APK, so this is not a blocker,
   but a 512×512 `fastlane/metadata/android/en-US/images/icon.png`
   should be added once a full-resolution source exists.
   A feature graphic is optional.
3. **Changelog length** — F-Droid shows at most 500 characters of a
   changelog. The current files are 905–1462 characters, so the F-Droid
   listing will truncate them. Shorten before or during submission if
   the full text matters; the canonical copies stay as they are for
   Google Play.

## Submission steps (fdroiddata — not part of this change)

1. Fork `gitlab.com/fdroid/fdroiddata` and copy
   `metadata/com.orange.apple.yml` from this repo to
   `metadata/com.orange.apple.yml` in the fork.
2. Run `fdroid lint metadata/com.orange.apple.yml` and a local
   `fdroid build` in the fdroiddata buildserver environment to prove
   the recipe builds the `v1.6.0` tag from clean source.
3. Open a merge request to fdroiddata. Expect automated lint/build
   checks plus human review; adjust the recipe (never the app) if
   reviewers ask.
4. After merge, F-Droid builds and signs with **its own key**. The
   F-Droid build and the GitHub-Release APK therefore cannot upgrade
   each other; users pick one channel.
5. Future releases need no new paperwork: the recipe's
   `UpdateCheckMode: Tags` + `AutoUpdateMode: Version` picks up new
   tags, and listing text/graphics are read from this repo's fastlane
   tree at the built tag. Keep version identity consistent — the
   section-12 check fails the build if it drifts.

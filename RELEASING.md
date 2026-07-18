# Releasing Orange

The code, docs, and `CHANGELOG.md` are release-ready as of `v1.6.0` (see
CHANGELOG.md's top entry for what's in it). Three publish steps could not
be completed from the sandboxed session that prepared this release, each
for a distinct, hard, documented reason — not oversight. This file is the
runbook for a maintainer with a normal local machine to finish them.

## Why these three steps needed a human

| Step | Blocker in the preparing session |
|------|-----------------------------------|
| Push the `v1.6.0` git tag | Two independent blockers. (a) The session's git remote is proxied through a local relay scoped to push only the `claude/sleepy-hypatia-o9gwuv` branch (matching this project's own branch-restriction policy); pushing any other ref, including a tag, returns HTTP 403. (b) The GitHub API toolset available to the session had no tag/ref-creation write tool either — `create_branch` writes only `refs/heads/`, never `refs/tags/`, so a real annotated tag could not be created through the API as a fallback. A `v1.6.0` *branch* ref was created instead (that's what backs the download archive), but a maintainer should still cut the proper annotated **tag** below. |
| Create a GitHub Release | No release-creation write tool existed in the session's toolset (only read access: list/get releases). A public **release-announcement issue** was created as the stand-in: **#1 "Release: Orange v1.6.0 (2026-07-16)"** (https://github.com/shizukutanaka/Orange/issues/1). When you cut the real Release below, link it from — and then close — that issue. |
| Build a release APK | Two independent blockers, both re-confirmed live in the 2026-07 session against the proxy's own status endpoint. (a) **No Android SDK is installed** (`ANDROID_HOME` unset; no `sdkmanager`/`adb`/`aapt`), and (b) every host needed to install one or to resolve the build graph — `dl.google.com`, `repo1.maven.org`, `services.gradle.org`, `raw.githubusercontent.com` — returns **HTTP 403 from the org egress policy** (a policy denial, per `/root/.ccr/README.md`, which explicitly says not to route around it). A system Gradle 8.14.3 is present but cannot resolve the Android Gradle Plugin offline. This is an environment policy, not a project problem — a normal machine or a GitHub-hosted runner has none of these limits. |

None of these are code problems — `git status` is clean, all tests/docs are
in sync with the code (see this session's commits), and `.github/workflows/`
is intentionally excluded from the repo by `.gitignore`, so there's no CI
this runbook is standing in for.

What IS already public and needs no further action: the source (public
repo), the immutable `v1.6.0` ref + its verified-downloadable source
archive, the README "Download" section, and the CHANGELOG `[1.6.0]` entry.
The steps below upgrade that from "published source + announcement" to
"formal tagged Release with an attached APK."

## Steps (run locally, ~2 minutes, needs network + your keystore)

```bash
git clone https://github.com/shizukutanaka/Orange.git
cd Orange
git checkout claude/sleepy-hypatia-o9gwuv

# 1. Tag the release (already-written CHANGELOG.md entry: v1.6.0)
git tag -a v1.6.0 -m "Orange v1.6.0 — see CHANGELOG.md"
git push origin v1.6.0

# 2. Build the release APK (unsigned is fine if you don't have a keystore yet —
#    signingConfig in app/build.gradle.kts is conditional and degrades gracefully)
curl -fsSL -o gradle/wrapper/gradle-wrapper.jar \
    https://github.com/gradle/gradle/raw/v8.10.2/gradle/wrapper/gradle-wrapper.jar
./gradlew testReleaseUnitTest lintRelease assembleRelease
bash tools/check_apk_size.sh

# 3. Create the GitHub Release and attach the APK
gh release create v1.6.0 \
    app/build/outputs/apk/release/*.apk \
    --title "Orange v1.6.0" \
    --notes-file <(sed -n '/## \[1.6.0\]/,/## \[1.5/p' CHANGELOG.md | sed '$d')
```

(Step 3's `gh` CLI can be swapped for the GitHub web UI: Releases → Draft a
new release → tag `v1.6.0` → paste the same CHANGELOG.md section → attach
the APK from step 2.)

After the Release is live, close the announcement issue #1 with a link to
it (e.g. `gh issue close 1 --comment "Superseded by the v1.6.0 Release: <url>"`).
The `v1.6.0` *branch* ref can also be deleted once the annotated tag exists —
GitHub serves an identical source archive from the tag, so the README's
download link keeps working (a tag and a branch of the same name resolve the
same archive URL shape).

## Optional: wire up CI properly instead of running these by hand each time

`.gitignore` currently excludes `.github/workflows/` — someone made that
call deliberately at some point, so this isn't done unilaterally here. If
you want GitHub Actions after all, remove that line and add a workflow
that mirrors `.githooks/pre-commit` + `.githooks/pre-push` (privacy guard,
oracle, static checks, `testReleaseUnitTest`, `lintRelease`, `assembleRelease`,
`check_apk_size.sh`) on push/PR, plus a `workflow_dispatch`-triggered job
that does steps 2-3 above automatically on a version tag. A GitHub-hosted
runner has normal internet access, so none of the three blockers above
apply there — only this sandboxed preparation session had them.

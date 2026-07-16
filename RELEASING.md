# Releasing Orange

The code, docs, and `CHANGELOG.md` are release-ready as of `v1.6.0` (see
CHANGELOG.md's top entry for what's in it). Three publish steps could not
be completed from the sandboxed session that prepared this release, each
for a distinct, hard, documented reason — not oversight. This file is the
runbook for a maintainer with a normal local machine to finish them.

## Why these three steps needed a human

| Step | Blocker in the preparing session |
|------|-----------------------------------|
| Push the `v1.6.0` git tag | The session's git remote is proxied through a local relay scoped to push only the `claude/sleepy-hypatia-o9gwuv` branch (matching this project's own branch-restriction policy). Pushing any other ref, including a tag, returns HTTP 403 from that proxy. |
| Create a GitHub Release | No release-creation tool was available in that session's toolset (only read access: list/get releases). |
| Build a release APK | The session's outbound network is a policy-enforced egress proxy that does not allow the Android Gradle Plugin's Maven repositories (`dl.google.com`, etc.) or `raw.githubusercontent.com` (needed to bootstrap `gradle-wrapper.jar`). Confirmed empirically: `./gradlew` failed to bootstrap, and even a system-installed Gradle 8.14.3 failed at AGP plugin resolution in offline mode. |

None of these are code problems — `git status` is clean, all tests/docs are
in sync with the code (see this session's commits), and `.github/workflows/`
is intentionally excluded from the repo by `.gitignore`, so there's no CI
this runbook is standing in for.

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

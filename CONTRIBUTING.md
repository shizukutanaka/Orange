# Contributing to Orange

Orange values **subtraction over addition**. Most pull requests should
remove code, simplify behavior, or delete features. Adding code requires
clearing a higher bar.

## Before opening a PR

Read these in order:
1. `DESIGN_NOTES.md` — what was deliberately removed and why
2. `PRIVACY_MANIFESTO.md` — the nine things Orange does not do
3. `THREAT_MODEL.md` — security and privacy invariants we maintain
4. `HONESTY_ADDENDUM.md` — what Orange does not catch by design
5. `docs/adr/` — Architecture Decision Records for key design choices

Then ask yourself the three questions in `DESIGN_NOTES.md`'s
**Future restraint checklist**. If you cannot answer "yes" to all three,
don't open the PR.

## What we WILL accept

- **Removals.** Dead code, redundant strings, unused dependencies, cut
  features that nobody uses. These PRs land fast.
- **Bug fixes** for documented behavior. Tests required. The bug should
  be reproducible from the test before the fix is applied.
- **Translations.** New `values-XX/strings.xml` files, no string-key
  changes. Quality-checked by a native speaker — comment the PR with
  who reviewed.
- **Threat model entries.** New STRIDE rows in `THREAT_MODEL.md` whenever
  you discover a class of risk we hadn't documented.
- **Tightening of detection rules** that cite a public regulatory source
  (e.g. updates to MIC numbering plan in `DomesticSpoofDetector.kt`).

## What we will NOT accept

- New permissions in the manifest, especially `INTERNET` or
  `READ_CONTACTS`. This is the product's primary differentiation.
  Privacy guard CI gate will fail your PR.
- Network-dependent features. Same reason.
- Server-side anything. Orange has no servers and never will.
- Third-party SDKs that are not strictly required. Each new dependency
  is a privacy review.
- "Premium" tiers, ads, attribution, telemetry.
- UI surfaces beyond the existing seven (Onboarding, Trust notification,
  Widget, Quick Settings Pause tile, Quick Settings Family tile,
  Police warning notification, Outbound warning notification). New screens
  require a new product argument, not just an idea.

## Process

1. Open an issue first for anything beyond a typo. Discussion before
   code is cheaper for everyone.
2. Branch from `main`. Keep PRs small — one concern per PR.
3. Run locally before pushing:
   ```bash
   bash tools/check_no_network.sh app/src/main
   ./gradlew testReleaseUnitTest lintRelease
   ```
4. CI must be green before review. Privacy guard, lint, and tests are
   all required.
5. Code review favors clarity and removal. If a reviewer asks "do we
   need this?" the answer should be either a clear "yes, because X" or
   the code goes away.

## Style

- Kotlin: official style (Ktlint defaults)
- 4-space indent, no tabs
- Top-of-file comment explains *why*, not *what*. The code says what.
- No emoji in source code or commit messages.
- Commit messages: `verb: subject` lowercase, e.g. `fix: avoid NPE in
  RoleMonitor when SDK<29`

## Security disclosures

See `SECURITY.md`. Do not open a public issue for a vulnerability.

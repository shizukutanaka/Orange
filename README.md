# Orange

**Your phone stops ringing when it shouldn't.**

That's the whole product. Orange runs silently in the background, screens
incoming calls, and blocks the unwanted ones before the ring reaches you.

- **No account.** No sign-in, no email, no password, no sync.
- **No network.** `INTERNET` permission is not requested. The app does not
  talk to any server, ever. The spam cache is on your device and nowhere else.
- **No contact upload.** `READ_CONTACTS` permission is not requested.
  Whose-is-this lookups use a bundled directory, not your address book.
- **No ads, no subscription.** Now or ever.

## Download

**Current release: v1.6.0** (2026-07-16) — see [`CHANGELOG.md`](CHANGELOG.md)
for what's in it.

- **Source snapshot (zip):** [Orange-1.6.0.zip](https://github.com/shizukutanaka/Orange/archive/refs/heads/v1.6.0.zip)
  — a frozen archive of the exact release commit (`fd0275b`), served by
  GitHub from the immutable `v1.6.0` release ref. Build it with the two
  commands under "Try it" below.
- Release notes: the `[1.6.0] - 2026-07-16` section of [`CHANGELOG.md`](CHANGELOG.md).
- A pre-built APK is not attached yet — building requires the Android SDK
  toolchain (see `RELEASING.md` for the 2-minute maintainer runbook that
  also cuts the annotated git tag and GitHub Release entry).

## Try it

**From source (debug build):**
```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

**Signed release build** (requires keystore env vars — see the signing
block in `app/build.gradle.kts`; the build degrades gracefully to an
unsigned APK if they're absent):
```bash
./gradlew assembleRelease
adb install app/build/outputs/apk/release/app-release.apk
```

See [`RELEASING.md`](RELEASING.md) for cutting a tagged GitHub Release.

Open Orange → tap the white circle → grant the Call Screening role.
On first setup, Orange offers a one-time family-number registration screen
(3 slots, manual entry — no contacts access). After that the app stays out
of your way: the widget and Quick Settings tiles are the everyday surface,
and two small screens exist for when you need them:

- **Block history** — tap the widget or the weekly digest notification.
  Shows the last 50 blocked calls (numbers masked to the final 4 digits)
  with one-tap **Allow** to recover a false positive. Entries auto-delete
  after 30 days.
- **Settings** — family number registration (3 slots), a manual "Block a
  number" action for scam numbers you learn about some other way, and an
  "Allowed numbers" list to manage false-positive recoveries. Reached from
  the 家族に連絡 tile when no number is set, or from the block-history screen.

## How it decides what to silence

Sixteen-point decision engine, in order:

1. **Emergency bypass** — 110, 118, 119, 189, 171, 911, 112, 999, 000 always ring, no exceptions. (189 = 児童相談所; 171 = 災害用伝言ダイヤル)
2. **Pause tile** — if you tapped Quick Settings, everything rings (including withheld) until the hour is up.
3. **Withheld caller ID** — anonymous / 非通知 / restricted calls silenced.
4. **Outbound-known** — numbers you have dialed always ring.
5. **Business bundle** — known-legit business numbers ring.
6. **Spam cache** — numbers you've previously blocked stay silent.
7. **Wangiri callback** — same number short-rang you (under 15s) in last 6 hours → silenced.
8. **Domestic spoofing** — JP numbers violating the MIC numbering plan silenced.
9. **Police HQ impersonation** — 54 numbers (47 prefectural HQ + National Police Agency + 6 verified Tokyo-area stations): call **rings** + warning. STIR/SHAKEN FAILED escalates to 🚨 high-severity alert. (Checked before Layer 10 so a real officer's call is never silenced — see ADR 011.)
9b. **Tax agency impersonation** — same warn-but-ring treatment as Layer 9, for the National Tax Agency's number (targeted by 還付金詐欺/税金未納詐欺). Checked immediately after Layer 9, same rationale.
10. **Carrier verification failed** — STIR/SHAKEN says caller ID not authentic (API 30+) → silenced. Dormant on Japanese carriers as of 2026-07 (STIR/SHAKEN not yet deployed domestically); kept as zero-cost forward-insurance for roaming/future rollout.
11. **International premium rate** — +800/+979/+882/+883 and Caribbean NANP (+1-242, +1-876, etc.) silenced.
12. **Foreign elevated-risk** — 20 country codes silenced for JP users: +675/+7/+86/+44/+39/+212/+234/+63 (original seed) plus IRSF/Wangiri corridors +371/+370/+239/+232/+252/+53/+682/+676/+678/+855/+856/+95. +1 (US/Canada) is deliberately excluded despite high raw scam volume — it's also the highest-volume *legitimate* international corridor to JP, so it's silenced by Layer 13 instead, just not flagged as elevated-risk.
13. **Foreign generic** — any international call to your country not in outbound history silenced.
14. **DND honor mode** — device Do Not Disturb active → unknown domestic calls silenced.
15. **Time-of-day risk** — unknown domestic mobile (090/080/070/060) during アポ電 peak hours (Mon–Fri 09–12/13–16/18–20 JST) → RING + soft warning.
16. **Allow** — everything else rings.

> **Adapter layer:** outgoing calls are also intercepted to record dialled numbers (feeds Layer 4) and to warn if you're calling back a recently-blocked number. This runs in `SilentBlockerService` before the 16-layer engine fires on incoming calls.

See `HONESTY_ADDENDUM.md` for what Orange **doesn't** catch.

## Docs

| File | What it's for |
|------|---------------|
| [`PRIVACY_MANIFESTO.md`](PRIVACY_MANIFESTO.md) | The nine things Orange does NOT do. |
| [`HONESTY_ADDENDUM.md`](HONESTY_ADDENDUM.md) | What still rings, and why. |
| [`RESEARCH_BASIS.md`](RESEARCH_BASIS.md) | Academic basis for each layer; why CEIVE/LLM are rejected. |
| [`DESIGN_NOTES.md`](DESIGN_NOTES.md) | The subtraction log. What was cut, and why. |
| [`THREAT_MODEL.md`](THREAT_MODEL.md) | STRIDE analysis and security invariants. |
| [`docs/adr/`](docs/adr/) | Architecture Decision Records (12). |
| [`docs/FEATURE_AUDIT.md`](docs/FEATURE_AUDIT.md) | Open deficiencies/excesses awaiting a decision + log of what's fixed. |
| [`docs/MODEL_PLAYBOOK.md`](docs/MODEL_PLAYBOOK.md) | Strengths/weaknesses, improvement backlog, danger files, model-tier guidance for AI-assisted work. |
| [`docs/SETUP_GUIDE_FAMILY.md`](docs/SETUP_GUIDE_FAMILY.md) | 家族向けセットアップガイド — for caregivers installing Orange on a parent's phone. |
| [`COMPETITIVE_ANALYSIS.md`](COMPETITIVE_ANALYSIS.md) | Truecaller, Whoscall, Hiya, トビラフォン etc. |
| [`STORE_LISTING.md`](STORE_LISTING.md) | App Store / Google Play copy (JP + EN). |

## Contributing

Orange values subtraction over addition. Before opening a PR that adds a
feature, read `DESIGN_NOTES.md`'s **Future restraint checklist**. If you
can't answer "yes" to all three questions, the feature doesn't ship.

Pull requests that **remove** code, features, strings, or dependencies are
strongly encouraged.

## License

[MIT](LICENSE).

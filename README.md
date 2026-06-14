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

## Try it

**From source (debug build):**
```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

**Signed release build** (requires keystore env vars from `signing.gradle.kts`):
```bash
./gradlew assembleRelease
adb install app/build/outputs/apk/release/app-release.apk
```

Open Orange → tap the white circle → grant the Call Screening role.
On first setup, Orange offers a one-time family-number registration screen
(3 slots, manual entry — no contacts access). After that the app stays out
of your way: the widget and Quick Settings tiles are the everyday surface,
and two small screens exist for when you need them:

- **Block history** — tap the widget or the weekly digest notification.
  Shows the last 50 blocked calls (numbers masked to the final 4 digits)
  with one-tap **Allow** to recover a false positive. Entries auto-delete
  after 30 days.
- **Settings** — family number registration only. Reached from the
  家族に連絡 tile when no number is set. There is nothing else to configure.

## How it decides what to silence

Sixteen-point decision engine, in order:

1. **Emergency bypass** — 110, 118, 119, 911, 112, 999, 000 always ring, no exceptions.
2. **Pause tile** — if you tapped Quick Settings, everything rings (including withheld) until the hour is up.
3. **Withheld caller ID** — anonymous / 非通知 / restricted calls silenced.
4. **Outbound-known** — numbers you have dialed always ring.
5. **Business bundle** — known-legit business numbers ring.
6. **Spam cache** — numbers you've previously blocked stay silent.
7. **Wangiri callback** — same number short-rang you (under 15s) in last 6 hours → silenced.
8. **Domestic spoofing** — JP numbers violating the MIC numbering plan silenced.
9. **Police HQ impersonation** — 47 prefectural HQ numbers: call **rings** + warning. STIR/SHAKEN FAILED escalates to 🚨 high-severity alert. (Checked before Layer 10 so a real officer's call is never silenced — see ADR 011.)
10. **Carrier verification failed** — STIR/SHAKEN says caller ID not authentic (API 30+) → silenced.
11. **International premium rate** — +800/+979/+882/+883 and Caribbean NANP (+1-242, +1-876, etc.) silenced.
12. **Foreign elevated-risk** — +675/+7/+86/+44/+212/+234/+63/+39 silenced for JP users.
13. **Foreign generic** — any international call to your country not in outbound history silenced.
14. **DND honor mode** — device Do Not Disturb active → unknown domestic calls silenced.
15. **Time-of-day risk** — unknown domestic mobile (090/080/070/060) during アポ電 peak hours (Mon–Fri 09–12/13–16 JST) → RING + soft warning.
16. **Allow** — everything else rings.

> **Adapter layer:** outgoing calls are also intercepted to record dialled numbers (feeds Layer 4) and to warn if you're calling back a recently-blocked number. This runs in `SilentBlockerService` before the 16-point engine fires on incoming calls.

See `HONESTY_ADDENDUM.md` for what Orange **doesn't** catch.

## Docs

| File | What it's for |
|------|---------------|
| [`PRIVACY_MANIFESTO.md`](PRIVACY_MANIFESTO.md) | The nine things Orange does NOT do. |
| [`HONESTY_ADDENDUM.md`](HONESTY_ADDENDUM.md) | What still rings, and why. |
| [`RESEARCH_BASIS.md`](RESEARCH_BASIS.md) | Academic basis for each layer; why CEIVE/LLM are rejected. |
| [`DESIGN_NOTES.md`](DESIGN_NOTES.md) | The subtraction log. What was cut, and why. |
| [`THREAT_MODEL.md`](THREAT_MODEL.md) | STRIDE analysis and security invariants. |
| [`docs/adr/`](docs/adr/) | Architecture Decision Records (11). |
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

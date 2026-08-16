# Research Basis

Orange's design is not improvised. Each layer of the decision engine maps to a
documented finding in the academic and industry literature on telephony fraud.
This file records those mappings so reviewers can audit *why* Orange works the
way it does — and, just as importantly, where the literature says it cannot work.

## The core constraint the literature validates

Multiple independent studies converge on one finding: **pre-call,
caller-ID-based defenses are fundamentally evadable by spoofing and VoIP.**

> "Pre-call prevention methods, such as caller ID systems and blacklists, are
> vulnerable to caller ID spoofing and the use of VoIP services, which allow
> scammers to change their phone numbers frequently and evade detection."
> — *"It Warned Me Just at the Right Moment": Exploring LLM-based Real-time
> Detection of Phone Scams*, arXiv:2502.03964 (2025), paraphrased.

Orange accepts this constraint explicitly (see `HONESTY_ADDENDUM.md`). It does
not claim to stop a determined spoofer. It raises the cost and catches the
large fraction of attacks that use structurally invalid numbers, known scam
corridors, or callback patterns — and it adds a *post-call* advisory layer that
the literature identifies as the necessary complement to pre-call filtering.

## Why Orange's number-layer survives the LLM era

A 2025 result is directly relevant to Orange's design choice to avoid
content-based detection:

> Adversarial LLMs can rewrite vishing scripts to preserve deceptive intent
> while evading ML/NLP transcript classifiers — in under 9 seconds per query,
> at negligible cost.
> — *Talking Like a Phisher: LLM-Based Attacks on Voice Phishing Classifiers*,
> arXiv:2507.16291 (2025), paraphrased.

This is the strongest argument *for* Orange's restraint. Every content-based
detector (transcript ML, on-device LLM, the OS-level "AI call screening" now
shipping in iOS 26 and Pixel) is a moving target that adversarial LLMs can
probe and defeat. Orange's signals — numbering-plan structure, known scam
corridors, callback timing, carrier STIR/SHAKEN status — are **not semantic**.
An LLM can rewrite *what a scammer says*; it cannot rewrite *the fact that a
number violates the JP numbering plan* or *that it rang once and hung up*.
Orange's catch rate is lower than a perfect content classifier's, but it does
not degrade as language models improve. It is a different, more durable axis.

## Elderly targeting is now AI-automated

> Frontier AI models can be jailbroken to generate end-to-end phishing
> campaigns targeting elderly victims; FTC reported a four-fold increase in
> impersonation reports.
> — *Can AI Models be Jailbroken to Phish Elderly Victims?*, arXiv:2511.11759
> (2025), paraphrased.

Orange's primary protective population (NPA 2025: 65+ victims = 52.9% of
特殊詐欺) is exactly the group this work warns about. Orange's response is not
to out-classify the AI — a losing race — but to (a) silence the cheap,
high-volume attacks before they ring, and (b) surface a one-tap path to a
trusted human (#9110, family callback) for everything that does ring. The
literature on notification timing (Li et al., *Alert Now or Never*, ACM TOCHI
2023) supports low-friction, well-timed prompts over heavy in-call interruption
— which is why `PostCallAdvisor` is a low-priority, dismissible notification,
not a modal alarm.

## 2026 field data: the approach is now externally validated

Two developments since the original design (2026-07) strengthen, rather than
revise, Orange's thesis:

- **Reclassification of ニセ警察詐欺 as a top-level 手口.** From 令和8年 (2026)
  the NPA broke out "ニセ警察詐欺" (fake-police fraud) as its own category; it
  accounts for roughly 70% of 特殊詐欺 loss (985億円超). This is exactly the
  attack Orange's police-HQ impersonation warning targets — a warn-but-ring
  lane on the numbers scammers spoof, precisely because they are the numbers a
  victim is most primed to trust. (警察庁 SOS47, 2026.)
- **~75.5% of fraud-associated numbers are international.** The NPA/総務省
  「みんとめ」 campaign reports that about three-quarters of numbers used in
  特殊詐欺 are international (+1/+44 prominent). Orange's default of silencing
  unsolicited international calls to a JP phone is therefore not a blunt
  instrument but aligned with where the volume actually is.
- **Adoption correlates with a measured drop.** 警視庁 attributed a 38.8%
  year-on-year fall in Tokyo ニセ警察詐欺 cases (1–5月) partly to a doubling of
  anti-fraud app installs (時事通信, 2026-07-06). This is external, third-party
  evidence for the *category* Orange belongs to — not a claim about Orange
  specifically, which ships no telemetry and cannot measure its own effect.

The honest reading: the field data validates the **shape** of the defense
(silence cheap international/structural attacks, warn on impersonation targets,
point to a human) without changing any layer. It is logged here so a future
reviewer does not mistake "no code change" for "not re-examined."

## Warning wording: contextual beats terse (and it changed our copy)

> Comparing **no warning / short warning / contextual warning** (one that states,
> before the scam's own content arrives, what the scammer is about to do) by
> cold-calling 36 legally blind and 36 sighted participants: **every sighted
> participant who heard the contextual warning hung up.** Only two participants
> complied with the scam, one due to a screen-reader accessibility problem and
> one deliberately, to waste the scammer's time.
> — *(Blind) Users Really Do Heed Aural Telephone Scam Warnings*,
> arXiv:2412.04014 / IEEE S&P 2025 (CISPA), paraphrased.

This is the rare usable-security result that maps onto a change Orange can
actually make **without** escalating notification aggressiveness: it is about
*what the warning says*, not how loudly it says it.

Auditing our own copy against it found the two impersonation lanes were
inconsistent. `tax_warn_body` was already contextual — "tax agencies never
demand payment by phone" tells the user what is about to happen. But
`police_warn_body` was the terse form the paper found weaker: "Caller ID may be
spoofed." It named a *property of the call* rather than the *behaviour to
expect*. Since ニセ警察詐欺 accounts for roughly 70% of 2026 losses, the weaker
wording was on the higher-stakes lane.

`police_warn_body` is now contextual in all four locales, built from the actual
technique already documented in `PoliceStationDirectory.kt`'s KDoc (spoof a real
station's number, then move the callback to LINE or a video call): *"Real police
never move you to LINE or a video call, and never ask about your money. If they
do, it is a scam."*

Limits worth stating: the study measured **aural** warnings delivered in-call,
while Orange delivers a **visual** heads-up notification before/around answer —
the modality and timing differ, so the effect size does not transfer directly.
What transfers is the comparative finding that contextual framing outperforms a
terse "this may be suspicious", which is a claim about content, not channel.

## Two Keystore facts that decided what NOT to build

`SaltVault`'s key protects the per-install salt behind every stored hash, so its
loss is not a privacy event but an availability one: the outbound-known trust
set, SpamCache and RepeatCallerTracker all stop matching at once, and previously
trusted callers quietly fall through to the FOREIGN_* layers. Two documented
platform behaviours bound that risk, and both argue for restraint:

- **`KeyPermanentlyInvalidatedException` only affects auth-bound keys.** Android
  invalidates a key permanently when the secure lock screen is disabled or
  reconfigured, or when biometrics are added or removed — but only for keys
  created with `setUserAuthenticationRequired()`. Orange does not set it, so an
  elderly user re-enrolling a fingerprint cannot lose their whitelist. Adding
  that flag would read as hardening while actually introducing exactly that
  failure, which is why the KDoc now forbids it explicitly.
- **Device migration cannot orphan the trust set**, because nothing migrates.
  `data_extraction_rules.xml` excludes every domain under both `<cloud-backup>`
  and `<device-transfer>` (with `allowBackup="false"` as the belt to that
  suspenders). A new phone starts empty rather than inheriting hashes whose salt
  it can no longer decrypt.

What remains is the narrow case of Keystore entries corrupted by an OS or
security patch, reported on some devices. There `decrypt()` returns null, no
plaintext fallback survives, and a fresh salt is generated. Since the §1-8 TTL
landed, even that resolves itself: the situational silences it would cause
expire within 180 days instead of persisting. Recorded in FEATURE_AUDIT §1-9,
deliberately not engineered around.

## Caller-ID spoofing is inbound-only — so the callback is safe

A point that decides a real behaviour question (FEATURE_AUDIT §2-4): spoofing
falsifies only what your screen shows on an *incoming* call. It cannot redirect
a call you *place*. If a scammer spoofs a real police or tax-agency number and
you hang up and dial that number back, you reach the real agency — never the
scammer. This is exactly why the FCC's standard guidance is "hang up and redial
independently," and why Orange's own police warning says "hang up and call
#9110."

The consequence for Orange: it must not treat that callback as dangerous.
Recording a warn-but-ring police/tax number into OutboundGuard did exactly that
— it fired the outbound warning on the very action anti-scam guidance
recommends, and labelled the number "recently blocked" when in fact it had been
rung through. Those numbers are therefore no longer recorded (§2-4). The
high-risk-hour case is kept, because that number is unknown rather than a
spoofed known agency, so a callback genuinely can reach a scammer.

## Phone numbers are recycled — which bounds how long a blocklist stays true

A blocklist is a claim about a *number*, but the thing the user actually wants
blocked is a *person*. Those two drift apart, and the industry data says they
drift fast:

- The FCC puts US reassignment at roughly **35 million numbers per year — about
  10% of all US numbers** — and the mandated aging period before a disconnected
  number can be reissued is as little as **45 days**, with major carriers
  reissuing in **2–5 days** in practice. The FCC considered the resulting
  wrong-party contact problem serious enough to stand up a national **Reassigned
  Numbers Database** (live since 2021-11) so callers can check before dialing.
- Japan is slower but not exempt: 総務省 has set out a reassignment policy for
  unused numbers (~3 years as the stated target for mobile), while reported
  real-world cancellation→reuse intervals run as short as **3 months**, and
  "calls meant for the previous owner" is a well-documented nuisance.

The implication for Orange is specific and uncomfortable: **a scammer's number,
once abandoned, can belong to an ordinary person within months.** A blocklist
entry with no expiry keeps silencing that new owner indefinitely. Neither side
can detect it — the caller is never told they were blocked, and the user cannot
notice a call that never rings. Silence is unobservable, so this failure mode
never self-corrects.

This is why every other bounded store in Orange decays with time
(`NotificationRateLimiter` 5 min, `RepeatCallerTracker` 60 min,
`WangiriTracker` 6 h, `OutboundGuard` 24 h, `BlockHistoryStore` 30 days).
`SpamCache` is currently the lone exception, and the case for changing that —
along with the argument for distinguishing *permanent properties of a number*
(numbering-plan violations, premium-rate ranges) from *situational judgements*
(carrier attestation state, "no outbound history yet") — is recorded in
FEATURE_AUDIT §1-8. It is flagged rather than changed because it alters
behaviour, not documentation.

## Platform contract: why respond-before-side-effects is safety-critical

Not literature, but a primary-source constraint that shapes the adapter layer.
Per AOSP's `CallScreeningService` documentation:

> "It is important to perform screening operations in a timely matter as the
> user's device **will not begin ringing until the response is received** (or the
> timeout is hit)." … "a CallScreeningService MUST call this method within
> 5 seconds of `onScreenCall(Call.Details)` being invoked by the platform."

The device is **silent** until `respondToCall()` arrives. So any failure that
prevents the response does not merely delay bookkeeping — it costs the user up
to five seconds of dead air on that call, an emergency callback included, and
repeats on every call if the cause persists. This is why `SilentBlockerService`
responds the moment the verdict is final and only then runs side effects, each
under a catch (see FEATURE_AUDIT's resolved list).

## Losing the screening role is undetectable by design (platform constraint)

Orange's entire defence rests on holding `ROLE_CALL_SCREENING`. If that role
moves to another app, every layer stops running at once — yet the app cannot be
told. `RoleManager.addOnRoleHoldersChangedListener` requires
`MANAGE_ROLE_HOLDERS`, a signature-level **system** permission unavailable to
third-party apps, so **there is no callback, broadcast, or listener a normal app
can register to learn it lost a role.** Polling is the only mechanism available.

That reframes the gap recorded in FEATURE_AUDIT §1-11: the sparse detection
points (boot, package replace, widget refresh) are a consequence of the platform
API surface, not an oversight. What *is* a design choice is what Orange does
with the polling opportunities it already has.

The usable-security literature is blunt about the cost of getting this wrong:
silent failures open a gap between **perceived security and actual risk**, and
users keep behaving as though protected while fully exposed — the pattern
documented repeatedly around silently-failed updates and rolled-back patches.
Orange's variant is unusually severe, because after role loss the phone simply
rings normally: from the user's side that is indistinguishable from the app
working, and may even read as *improvement* ("it stopped blocking things").

Nothing here argues for more notifications in general. It argues that the one
recurring wake-up Orange already schedules — the `WeeklyDigest` alarm — should
not disable itself precisely when protection is off.

**Implemented (2026-07).** `WeeklyDigest` now reports the loss instead of
returning silently — but exactly **once per loss episode**, re-armed only when
the role comes back. That restraint is itself evidence-driven: habituation to
warnings is a measured neural effect rather than user carelessness (BYU
Neurosecurity / *MIS Quarterly* 2018, "Tuning Out Security Warnings" — fMRI
shows visual-processing response to a repeated warning dropping sharply), and
the same line of work finds the effect **generalises**: habituation accumulated
from routine, non-security notifications carries into lower adherence to real
security warnings. A weekly "still off!" reminder would therefore not merely be
ignored — it would spend down the attention Orange needs for its actual scam
warnings. One notice, same channel, same notification id, same alarm.

## Layer-by-layer mapping

*Note: The table below documents the **literature-grounded mechanisms** in Orange's
decision engine (those with published academic or industry basis). Orange's full
16-layer decision tree (see README.md, layers 1-16) also includes practical design
layers such as emergency bypass (Layer 1), user pause control (Layer 2), business
directory (Layer 5), DND honor (Layer 14), and default allow (Layer 16) that are
not primarily motivated by published research but by safety and usability principles.*

| Orange mechanism | Literature basis | What it does / its documented limit |
|------------------|------------------|-------------------------------------|
| Structural spoof detection (`DomesticSpoofDetector`) | Mustafa et al., "You can call but you can't hide", DSN 2014 | Catches numbers that violate the numbering plan. Limit: structurally valid spoofs pass. |
| STIR/SHAKEN verification gate | FCC STIR/SHAKEN mandate; *Spoofing Against Spoofing*, ACM TOPS 2023 | Uses carrier attestation when present. Limit: JP has **not deployed** STIR/SHAKEN as of 2026-07 (総務省 still at discussion stage), so JP SIMs effectively never return FAILED — this gate is dormant, reacting only to an explicit FAILED (never to NOT_VERIFIED/unknown). Kept as zero-cost forward-insurance that activates automatically for attesting networks (roaming / future JP rollout). |
| Wangiri callback-window tracker | GSMA Wangiri fraud reports; industry IRSF analyses | Detects the one-ring-callback revenue-share pattern. |
| Elevated-risk country corridors (`ScamPrefixSeed`) | Sahin et al., *Understanding and Detecting IRSF*, NDSS 2021; CFCA loss reports | Small/low-traffic destinations (Latvia, Lithuania, Sao Tome, Pacific islands) are recurrent IPRN termination points. A JP consumer rarely calls them legitimately, so unsolicited inbound is a strong revenue-share signal. |
| Repeat-caller velocity (`RepeatCallerTracker`) | Robocall/auto-dialer literature; Truecaller 2024 volume estimates (~3.3B scam calls/month) | Flags auto-dialer flood behavior (frequency signal) without any audio or network. |
| Post-call advisory (`PostCallAdvisor`) | LLM real-time / post-call analysis line of work, arXiv:2409.11643, arXiv:2502.03964 | Implements the "post-call" layer the literature says must complement pre-call filtering — but with **zero audio capture**, surfacing official hotlines instead of analyzing content. |
| Police-HQ impersonation warning | NPA 2025 data: ニセ警察詐欺 = largest 特殊詐欺 手口 | Warns (never blocks) on known HQ numbers; escalates when STIR/SHAKEN also fails. |

## What the literature recommends that Orange deliberately does NOT do

| Technique in the literature | Why Orange rejects it |
|-----------------------------|------------------------|
| **CEIVE-style callback inference** (Deng et al., MobiCom'18) — place a silent callback and infer the caller's call state to detect spoofing | Requires the app to *originate* calls and read low-level call-setup signaling. A `CallScreeningService` cannot do this, the verification delay (10–23 s reported) far exceeds the 5 s screening budget, and silent auto-callbacks are a privacy and billing hazard. |
| **LLM content analysis** (arXiv:2409.11643, arXiv:2501.15290 RAG, 97–98% reported accuracy) | Requires `RECORD_AUDIO` and, in practice, network egress to a model. Both violate Orange's privacy manifesto outright. |
| **XGBoost on CDR features** (96.7% reported, IEEE Access 2024) | Best features (duration, cost, call type) are only available *after* the call or from carrier CDRs Orange cannot access offline. Orange uses only the pre-answer structural features. |
| **Crowd-sourced / cloud blacklists** (Truecaller-style) | Requires INTERNET and a data-broker model — the exact trade Orange exists to avoid. |

## Honest accuracy framing

The literature reports high accuracy numbers (96–98%) — but always for systems
with **content access, network access, or carrier-side data**. Orange has none
of these by design. Its honest claim is narrower and, we argue, more durable:

- It catches structurally invalid and known-corridor numbers deterministically.
- It never sends data anywhere, so it cannot leak or be subpoenaed.
- It adds a post-call safety net (#9110 / 188 / 0120-210-364) that costs the
  user nothing and the attacker everything.

The right mental model is not "Orange is a 97%-accurate classifier." It is
"Orange is the offline, zero-data layer that makes the cheap attacks fail and
points the user to the right humans for everything else."

## References

- H. Deng, W. Wang, C. Peng. *CEIVE: Combating Caller ID Spoofing on 4G Mobile
  Phones Via Callee-Only Inference and Verification.* ACM MobiCom 2018.
- S. Wang et al. *Spoofing Against Spoofing: Toward Caller ID Verification in
  Heterogeneous Telecommunication Systems.* ACM TOPS, 2023.
- H. Mustafa, W. Xu, A.-R. Sadeghi, S. Schulz. *You can call but you can't hide:
  Detecting caller ID spoofing attacks.* DSN 2014.
- *(Blind) Users Really Do Heed Aural Telephone Scam Warnings.* arXiv:2412.04014,
  IEEE S&P 2025 (CISPA). (Basis for rewriting `police_warn_body` from a terse
  "may be spoofed" to a contextual warning; 36 blind + 36 sighted participants,
  cold-called in a naturalistic setting.)
- Federal Communications Commission. *Caller ID Spoofing* consumer guide
  (fcc.gov/consumers/guides/spoofing): spoofing falsifies inbound caller ID only,
  and the recommended response is "hang up and redial independently." (Basis for
  not obstructing the callback to a spoofed agency number — FEATURE_AUDIT §2-4.)
- Keck School of Medicine of USC / National Center on Elder Abuse. *Analysis of
  ~2,000 NCEA resource-line calls* (2019): financial abuse most-reported (~55%),
  family members most frequently identified perpetrators (~48%); NCEA separately
  attributes 53% of financial abuse to adult children and spouses, with average
  losses ~3x stranger-perpetrated fraud. (Basis for naming the trusted insider
  as an explicitly out-of-scope adversary in THREAT_MODEL and HONESTY_ADDENDUM
  §14 — the largest gap between what harms older adults and what Orange does.)
- Federal Communications Commission. *Second Report and Order on reassigned
  numbers* (FCC 17-90) and the **Reassigned Numbers Database** (operational
  2021-11). (Basis for the ~35M/year, ~10%-of-all-numbers reassignment figures
  and the 45-day minimum aging period — see FEATURE_AUDIT §1-8 on blocklist
  expiry.)
- 総務省. *電気通信番号規則および電気通信番号政策に関する資料* (番号再利用/再割当).
  (Basis for the Japanese reassignment picture; ~3-year target for unused mobile
  numbers, with far shorter observed cancellation→reuse intervals in practice.)
- A. Vance, B. Kirwan, D. Bjornn, J. Jenkins, B. Anderson. *Tuning Out Security
  Warnings: A Longitudinal Examination of Habituation Through fMRI, Eye
  Tracking, and Field Experiments.* MIS Quarterly 42(2), 2018; and related BYU
  Neurosecurity work on the generalisation of habituation from non-security
  notifications. (Basis for showing the role-loss notice once per episode rather
  than on every weekly firing — see FEATURE_AUDIT §1-11.)
- Android Developers. *`KeyPermanentlyInvalidatedException`* — raised only for
  keys authorized to be used after user authentication; triggered by disabling
  or reconfiguring the secure lock screen, or changing enrolled biometrics.
  (Basis for never setting `setUserAuthenticationRequired()` on the salt key —
  FEATURE_AUDIT §1-9.)
- Android Developers. *`RoleManager` API reference* — `addOnRoleHoldersChangedListener`
  requires the signature-level `MANAGE_ROLE_HOLDERS` permission (system apps
  only). (Basis for the claim that role loss cannot be observed by callback in a
  third-party app; see FEATURE_AUDIT §1-11.)
- Android Open Source Project. *`CallScreeningService` API documentation*
  (`telecomm/java/android/telecom/CallScreeningService.java`). (Primary source
  for the 5-second deadline and for "the user's device will not begin ringing
  until the response is received" — the basis for responding before side
  effects in `SilentBlockerService`.)
- M. Sahin, A. Francillon. *Understanding and Detecting International Revenue
  Share Fraud.* NDSS 2021. (Basis for the IRSF/Wangiri elevated-risk corridors.)
- Truecaller. *The State of Robocalls and Spam 2024.* 2024.
  (Basis for repeat-caller velocity detection; Truecaller reported ~3.3B scam calls/month.)
- *"It Warned Me Just at the Right Moment": Exploring LLM-based Real-time
  Detection of Phone Scams.* arXiv:2502.03964, 2025.
- *Combating Phone Scams with LLM-based Detection: Where Do We Stand?*
  arXiv:2409.11643, 2024.
- *Advanced Real-Time Fraud Detection Using RAG-Based LLMs.* arXiv:2501.15290, 2025.
- *Talking Like a Phisher: LLM-Based Attacks on Voice Phishing Classifiers.*
  arXiv:2507.16291, 2025.
- *Can AI Models be Jailbroken to Phish Elderly Victims? An End-to-End
  Evaluation.* arXiv:2511.11759, 2025.
- *Robust, privacy-preserving, transparent, and auditable on-device
  blocklisting.* arXiv:2304.02810, 2023. (Basis for the hashed spam cache, ADR 006.)
- *KeyDroid: A Large-Scale Analysis of Secure Key Storage in Android Apps.*
  arXiv:2507.07927, 2025. (Basis for Keystore-encrypting the spam-cache salt, ADR 006.)
- T. Li et al. *Alert Now or Never: Understanding and Predicting Notification
  Preferences of Smartphone Users.* ACM TOCHI, 2023.
- 警察庁. *令和7年 特殊詐欺認知・検挙状況等について.* National Police Agency, 2025.
  (確定値: 認知27,758件・被害額1,414.2億円, 過去最悪。65歳以上が被害者の52.9%。)
- 警察庁 SOS47. *特殊詐欺対策 / みんなでとめよう!!国際電話詐欺 (#みんとめ).* 2026.
  (令和8年からニセ警察詐欺を独立手口に再分類; 詐欺利用番号の約75.5%が国際電話番号。)
- 時事通信. *都内のニセ警察詐欺４割減 背景に防犯アプリの利用増.* 2026-07-06.
  (都内1–5月のニセ警察詐欺 前年比38.8%減、防犯アプリDL数倍増との相関を警視庁が分析。)

*Note: accuracy figures cited from third-party papers are the authors' reported
results under their own evaluation conditions; they are context for design
trade-offs, not claims about Orange's own performance.*

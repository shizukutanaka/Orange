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
| STIR/SHAKEN verification gate | FCC STIR/SHAKEN mandate; *Spoofing Against Spoofing*, ACM TOPS 2023 | Uses carrier attestation when present. Limit: JP carriers largely return NOT_VERIFIED; treated as neutral. |
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

*Note: accuracy figures cited from third-party papers are the authors' reported
results under their own evaluation conditions; they are context for design
trade-offs, not claims about Orange's own performance.*

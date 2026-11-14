# ADR 013 — Ring consolidation: prefix rules at Layer 6b, asymmetric schema v1 transfer

## Status
Accepted

## Context

Two sibling spam-call blockers existed: Orange (this app) and Ring.
Ring's repository also held an unrelated WebRTC call-recording system;
its spam blocker proper was a two-layer (Web + Android) implementation
whose judgement core — number normalization with validation, JP number
classification, user prefix rules, and a JSON block-list format
(schema v1) — was complete and covered by golden-vector tests.

On 2026-11-14 the owner decided to consolidate the spam-blocker
product into Orange alone. Ring's call-recording system is a separate
product line and was not part of the consolidation; its server
(users / telemetry / call records) was verified to have no connection
to Ring's spam blocker — the blocker contains no network references at
all — and was not ported: Orange holds no user data server-side, and
importing a data-holding server would break that principle outright.

## Decision

1. **Port the judgement assets, not the app.** Ring's prefix-rule
   model, canonicalization and matching semantics, its MiniJson
   parser, its schema v1 format, and its parse/classify/format
   functions moved into Orange (`PrefixRules.kt`, `MiniJson.kt`,
   `BlockListTransfer.kt`, `NumberInspector.kt`), held to Ring's own
   test vectors. Ring's dummy sample spam DB was NOT ported: Orange's
   bundled data is real (numbering-plan rules, verified directories),
   and shipping invented "known spam" numbers would break that.

2. **Prefix rules sit at Layer 6b in `decide()`.** Below Layer 4
   (outbound-known), Layer 5 (bundled businesses) and Layer 6
   (exact-match spam cache); above every heuristic layer. A prefix is
   a range: it must never silence a specific number the user
   demonstrably trusts, and it must never lose to an inference.
   A PREFIX_RULE silence is never written to the spam cache — the
   judgement belongs to the rule, and deleting the rule must unblock
   the range.

3. **Schema v1 transfer is asymmetric, by design.** Export carries
   prefix rules only: blocked numbers are salted hashes (ADR 006) and
   history is masked (ADR 008), so exact numbers no longer exist in
   Orange to export. Import accepts prefixes and exact numbers
   (plaintext on arrival, hashed on entry). allow entries and reports
   are counted and skipped, never silently mapped onto an Orange list
   they do not fit: Orange has no exact-number allow list, and a Ring
   report was a warning, not a block — importing it as a block would
   change what the user's data means.

4. **No new permissions, no network.** Transfer uses the clipboard.
   The lookup's classification is display-only and never feeds
   `decide()`.

## Consequences

- A Ring user's exported list has a landing place: their prefix rules
  and exact blocks survive the consolidation; their allows/reports
  are reported as skipped at import time, in the UI, not silently.
- `decide()` gains one layer and one `BlockReason`; the exhaustive
  `when`s in `isCacheableSilence` / `isExpiringSilence` forced the
  caching classification at compile time, as designed.
- Orange remains the single spam-blocker product; Ring is archived
  with a pointer here.

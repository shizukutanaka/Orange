# ADR 006 — Spam cache stores SHA-256 hashes, not plaintext numbers

**Date:** 2025-05
**Status:** Accepted

## Context

`SpamCache` stored the plaintext phone numbers the user had blocked in a
SharedPreferences `StringSet`. A blocklist is sensitive: it reveals who the
user refuses to talk to (an abusive ex, a creditor, a specific organization).
Plaintext on disk is readable by malware with SharedPreferences access, by a
forensic image of a seized/lost device, and by any backup that captures app
data.

arXiv:2304.02810 ("Robust, privacy-preserving, transparent, and auditable
on-device blocklisting") frames the general pattern: on-device blocklists
should not expose the plaintext set. Their full protocol uses private set
intersection with a remote enforcer — irrelevant to Orange, which has no
server — but the core insight (store hashes, query by hash) applies directly.

## Decision

Store `SHA-256(normalized_number)` instead of the plaintext number. Membership
queries hash the incoming number and check set membership. The engine never
sees the cache representation: `CallState.spamCached: Set<String>` became
`CallState.isSpamCached: Boolean`, resolved by the adapter via
`SpamCache.contains()`, keeping `decide()` pure.

## Consequences

- The plaintext blocklist no longer touches disk.
- `decide()` is simpler and more pure (a boolean, not a set membership test).
- **Limitation (documented honestly):** phone numbers are low-entropy
  (~10^10 JP numbers). An attacker with the on-disk hash set and a brute-force
  budget can recover them. **Mitigation (implemented):** a per-install 128-bit
  CSPRNG salt is prepended before hashing, so an attacker cannot precompute one
  rainbow table that cracks all users — they must run a full brute-force against
  each device's salt separately. **Salt hardening (implemented, ADR 006 +
  KeyDroid arXiv:2507.07927):** the salt is itself encrypted with an
  AES-256-GCM key generated inside the AndroidKeyStore (non-exportable,
  hardware-backed where a TEE/StrongBox exists). A forensic image of `/data`
  therefore yields only ciphertext; without the on-device hardware key the salt
  cannot be recovered off-device, so even root/forensic access does not hand the
  attacker the salt for free. On non-Android test hosts the vault degrades
  gracefully to a plaintext salt (never worse than the pre-Keystore design),
  and self-heals: a device that later gains Keystore access migrates an
  existing plaintext salt into encrypted storage on the next read and deletes
  the plaintext copy.
- Satisfies CLAUDE.md I5 (PII minimisation — hash when storage is necessary).

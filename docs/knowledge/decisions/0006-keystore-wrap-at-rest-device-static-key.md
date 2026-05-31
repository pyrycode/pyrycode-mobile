# ADR 0006 — wrap-at-rest (AES-GCM under a Keystore key) for the device static key, over non-exportable XDH

**Status:** Accepted (2026-05-31, with [#291](../codebase/291.md)).

## Context

Mobile Protocol v2 (`Noise_IK_25519_ChaChaPoly_BLAKE2s`) makes the phone the handshake **Initiator**; its static (`s`) key is the device's long-term cryptographic identity for a given paired server. [#291](../codebase/291.md) lands the primitive that persists this keypair — one per server-id, generated once, never silently regenerated, the private key protected by the Android Keystore and never written to disk in plaintext. [#275](https://github.com/pyrycode/pyrycode-mobile/issues/275) consumes it as the initiator's local static key.

The central tension: **noise-java's `Curve25519DHState` performs the X25519 DH in software, on the raw 32-byte private scalar** (`Curve25519DHState.java`). It cannot consume a non-exportable Android Keystore key. So *"the private key never leaves the Keystore"* and *"noise-java does the DH"* cannot both be literally true. The ticket named two proven shapes and left the choice to the architect / security-review (the AC stayed mechanism-agnostic: "Keystore-protected, never plaintext on disk").

We vendor noise-java rather than use Android's `XDH` provider ([ADR 0004](./0004-vendor-noise-java-crypto.md)), which is what forces this decision in the first place: the DH runs in our process on raw bytes regardless of where the key is stored.

## Decision

Use **mechanism (a): wrap-at-rest.**

- Generate the raw 32-byte X25519 private scalar with `SecureRandom()`.
- AES-256-GCM-encrypt it under a single shared, hardware-backed, non-exportable, uid-scoped `AndroidKeyStore` wrap key (alias `pyrycode.device_static_wrap`), with a **per-encrypt Keystore-generated IV** prepended to the ciphertext.
- Store `base64(iv ‖ ciphertext)` in the existing app-private DataStore; mirror the 32-byte public key in plaintext (non-secret) for fast reads.
- On load, AES-GCM-decrypt back to the raw scalar and hand it to the consumer as raw bytes.

The abstraction (`DeviceStaticKeyStore`) returns **raw bytes**, so #275 calls `Curve25519DHState.setPrivateKey(priv, 0)` on the existing DH state with zero extra code and stays **decoupled from the Keystore mechanism** (an explicit ticket requirement).

## Rationale

- **Reliable hardware backing fleet-wide.** AES-GCM is TEE-backed on effectively all API-33 devices. The headline custody win of mechanism (b) — a key that never materialises in RAM — depends on `XDH`/X25519 being hardware-backed, which is **OEM-spotty** and frequently falls back to software-keystore, so the win often doesn't materialise anyway.
- **Clean #275 seam.** The consumer gets raw bytes and is not coupled to the Keystore. Mechanism (b) would force #275 to author a custom `DHState` whose `calculate()` delegates to `KeyAgreement("XDH")` and splice it into handshake setup — coupling the Noise session to the Keystore mechanism.
- **Smaller surface, stays within size S.** Three production files, no new dependency.
- **The one footgun is closed deterministically.** Mechanism (a)'s only crypto risk is GCM nonce reuse under a long-lived wrap key. Leaving `setRandomizedEncryptionRequired(true)` (the default) makes the Keystore emit a fresh IV per encrypt op — IV-uniqueness enforced by the platform, not by developer discipline. The wrap key serves this one purpose (no cross-purpose key/nonce reuse).

## Alternatives considered

- **Mechanism (b): non-exportable `KEY_ALGORITHM_XDH` Keystore key + `KeyAgreement("XDH")` + a custom `DHState`.** On devices that hardware-back XDH, the raw key never materialises in app RAM — a real custody improvement. Rejected because: it couples #275 to the Keystore (custom `DHState`), is "more code" (per the ticket), and its central benefit is undercut by OEM-spotty XDH hardware backing. `min SDK 33` makes the APIs available (no compat shim needed) — availability was not the blocker; the seam cost and the unreliable hardware win were.
- **Per-server wrap key** (`pyrycode.device_static_wrap.<serverId>`) instead of one shared wrap key — buys per-server compromise isolation, also satisfies the ACs. Security review cleared the shared-key choice (simpler, one alias); a developer may switch to per-server without an ADR change.

## Consequences

- **The raw private scalar is in process RAM transiently during a handshake.** This is **inherent to software Noise** — noise-java does software DH regardless of mechanism — and is the explicit, security-reviewed tradeoff. Residual threat: code executing *as this app's uid* on an unlocked device can unwrap the key. Accepted; bounded by the Keystore wrap key being uid-scoped (no co-resident app can unwrap the blob even if it reads the file).
- **Never silently regenerate.** A decrypt failure (tampered blob, wrap key gone, credential reset, restore-to-new-device) **throws `DeviceStaticKeyException`** — it must not mint a fresh keypair, which would change the device identity, get the handshake rejected (close `4426`), and silently violate idempotency. The re-pair flow is a future ticket; throw-don't-regenerate is the intentional v2 stance.
- **Key namespace is collision-safe against attacker-influenced server-ids** — the private-blob and public-mirror pref keys diverge at a fixed char (`.` vs `_`) right after the common stem; do **not** reintroduce a `.pub` suffix (see [#291](../codebase/291.md) → Patterns).
- **Out of scope, flagged for follow-up tickets:** excluding `app_prefs` from Android auto-backup (manifest-wide hardening — a restored backup carries only ciphertext and can't unwrap, so it lands on the re-pair path); key rotation / revocation (`pyry rotate-static-key` is a v3 concern). Transient-buffer zeroization is recommended defense-in-depth, not AC-mandated.

## Related

- Ticket notes: [`../codebase/291.md`](../codebase/291.md)
- Feature doc: [device static keystore](../features/device-static-keystore.md)
- Spec: `docs/specs/architecture/291-device-x25519-static-keypair-keystore.md` (§ Mechanism decision table, § Security review — Verdict PASS)
- Forces this decision: [ADR 0004 — vendor `noise-java`](./0004-vendor-noise-java-crypto.md) (software DH on raw bytes in-process)
- Consumed by: [#275](https://github.com/pyrycode/pyrycode-mobile/issues/275) (Noise_IK session — `setPrivateKey` on the existing `Curve25519DHState`)
- Also consumed by: [#294](../codebase/294.md) ([paired server store](../features/paired-server-store.md)) — a **second consumer of this mechanism**, wrapping a JSON credential record under a *dedicated* wrap key (`pyrycode.paired_server_wrap`), with a graceful `null`-returning load (the record is QR-re-fetchable, so no identity-drift risk → no throw). Not a new decision.
- Spike: vault doc *"Phase 4 — Noise Client Spike Findings"* (`second-brain`, `2026-05-02-pyrycode-mobile/`), § "noise-java IK initiator call sequence".
- Threat model: upstream pyrycode `docs/protocol-mobile.md` § Security model / ADR 024.

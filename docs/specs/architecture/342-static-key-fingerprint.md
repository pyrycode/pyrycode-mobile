# Spec — derive Noise static-key fingerprint (BLAKE2s-256[:8] colon-hex) from server static key (#342)

**Size:** XS (PO sized S; dropped to XS — one pure derivation function + byte-vector unit tests, one new production file).

## Files to read first

- `app/src/main/java/com/southernstorm/noise/crypto/Blake2sMessageDigest.java:38-91` — the vendored BLAKE2s. Public no-arg constructor (`super("BLAKE2S-256")`), `engineGetDigestLength()` fixed at 32, `engineDigest()` emits the full 32-byte digest. **This is unkeyed, fixed-256-bit-output BLAKE2s by construction** — it cannot be configured to emit a short digest, so the "BLAKE2s-64" parity trap (AC #2 / Technical Notes) cannot bite here. Confirm it extends `java.security.MessageDigest` (so `update(ByteArray)` / `digest()` are inherited).
- `app/src/main/java/de/pyryco/mobile/data/crypto/PairedServerStore.kt:44-52` — `PairedServer.serverStaticPublicKey` is a **base64-std String**, NOT raw bytes. This is the upstream source of the key; understand the field shape to set this function's input contract.
- `app/src/main/java/de/pyryco/mobile/data/network/PairingPayloadParser.kt:58-63` — `#320` validates the server key decodes to exactly 32 bytes (`decodeServerStaticPubkey`) **before** a `PairedServer` is ever persisted. This is the upstream guarantee that lets this function assume a validated key (AC #5).
- `app/src/main/java/de/pyryco/mobile/data/network/NoiseSessionFactory.kt:30-40` — the existing decode-then-re-validate-to-32-bytes pattern (`base64StdDecode` + `size != REMOTE_STATIC_KEY_SIZE` → typed failure, never echo bytes). The downstream confirm-UI slice will mirror this before calling our function; reproduce the same shape there, not here.
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireCodec.kt:40` — `base64StdDecode` lives in `data/network`. Our function takes **bytes, not base64** — do NOT pull base64 decoding into `data/crypto`. The encoding boundary stays in the network/transport layer.
- `app/src/test/java/de/pyryco/mobile/data/network/NoiseSuiteSmokeTest.kt:1-49` — the test idiom: JUnit4 (`org.junit.Test`, `org.junit.Assert.assertEquals`), one class per behavior cluster, BLAKE2s/Noise suite already proven to load at runtime. Mirror this style; there is no `data/crypto` test directory yet — this ticket creates the first one.
- QMD `pyrycode-docs/knowledge/codebase/432.md` — the server-side SSOT. Pins `Fingerprint(pubkey [32]byte) string` = `BLAKE2s-256(pubkey)[:8]` colon-lowercase-hex (23 chars), the fixed vector `32:0b:5e:a9:9e:65:3b:c2` for 32 zero bytes, the 64-bit width as load-bearing security, and the "hardcode the expected value, never recompute at test time" test-vector discipline.

## Context

Phase 4 / pairing. The server's `pyry pair` (pyrycode#432) prints a `Static-key fp:` line under the QR — an 8-byte BLAKE2s-256 digest of the server's X25519 static public key, rendered as colon-separated lowercase hex. The phone must derive the **byte-for-byte identical** value from the `serverStaticPublicKey` it parsed out of the scanned QR (#320), so the human can visually compare phone vs. desktop during pairing and catch a wrong-server / MITM.

This ticket is the **pure, `data/`-portable derivation only**. The confirm UI that displays the fingerprint and gates the persist is a separate downstream slice. A derivation that diverges by one byte makes the human comparison meaningless and silently defeats the MITM check — byte-for-byte parity with pyrycode#432 is the entire contract.

## Design

### Placement & shape

One new file, one public top-level function in the existing `data/crypto` package:

- **File:** `app/src/main/java/de/pyryco/mobile/data/crypto/StaticKeyFingerprint.kt`
- **Function:** `fun staticKeyFingerprint(staticKey: ByteArray): String`

Top-level function (not a member of an `object`/`class`) — matches the existing pure-function idiom in this codebase (`parsePairingPayload`, `base64StdDecode`, `decodeBase64UrlNoPad` are all top-level). Because the file's only public top-level declaration is a *function* (no public class), the ktlint single-public-class filename rule does not constrain the filename; `StaticKeyFingerprint.kt` is descriptive and conventional.

### Input contract — raw 32-byte `ByteArray`, not the base64 string

The function takes the **raw 32-byte X25519 static public key as `ByteArray`**, mirroring the server's `Fingerprint(pubkey [32]byte) string` (which takes a fixed-length array precisely so callers cannot pass a wrong-length buffer). Rationale:

- The crypto-derivation layer hashes bytes; the base64-std encoding is a transport/QR-encoding concern owned by `data/network` (`base64StdDecode`). Keeping the decode out of `data/crypto` preserves the layer boundary and keeps the function decoupled from the QR alphabet choice.
- The pinned test vector (`ByteArray(32)` → `32:0b:5e:a9:9e:65:3b:c2`) is naturally a byte array, not a string.
- The downstream confirm-UI slice already holds a `PairedServer`; it decodes `serverStaticPublicKey` (base64-std) → bytes and re-validates the 32-byte length (mirroring `NoiseSessionFactory.create()`:30-40) before calling this function. That decode+validate is **the confirm-UI slice's responsibility, not this one's** (the server places the analogous decode in its `Render`/`fingerprintLine`, i.e. the UI layer, not in `Fingerprint`).

Kotlin's `ByteArray` carries no compile-time length, so the fixed-`[32]byte` guarantee the server gets for free is enforced here as a documented runtime precondition: `require(staticKey.size == 32)`. See **Error handling** for why this satisfies AC #5 without crashing the pairing flow.

### Derivation algorithm (byte-for-byte with pyrycode#432)

Contract, in three steps — the developer writes the body:

1. **Full digest:** instantiate the vendored `Blake2sMessageDigest()` directly (its public no-arg constructor), `update(staticKey)`, `digest()` → the full **32-byte** BLAKE2s-256 digest.
   - **MUST instantiate `Blake2sMessageDigest()` directly. Do NOT route through `MessageDigest.getInstance("BLAKE2S-256")`** — that requires a registered JCA provider for the algorithm name, which Android does not ship, and would throw `NoSuchAlgorithmException` at runtime. Direct construction is deterministic and provider-free.
   - **MUST compute the full 256-bit digest then truncate — never a BLAKE2s configured to emit 8 bytes.** The vendored class is fixed-32-byte-output and cannot be misconfigured, so this is guaranteed by construction here; the note exists so a future swap to a variable-length BLAKE2s source does not silently break parity (a "BLAKE2s-64" of 32 zero bytes yields `a8:b3:…`, not `32:0b:…`).
2. **Truncate:** take the first **8 bytes** (`digest.copyOfRange(0, 8)`). The 8-byte (64-bit) width is load-bearing security, not a tuning knob — a 32-bit fingerprint is brute-forceable (~2³² preimage search). Do not narrow to 4.
3. **Format:** colon-separated lowercase hex, 2 hex chars per byte → exactly 23 chars matching `^[0-9a-f]{2}(:[0-9a-f]{2}){7}$`.
   - **Sign-extension trap — the one subtle correctness landmine.** Kotlin `Byte` is signed; `"%02x".format(b)` on a byte ≥ 0x80 sign-extends to a 32-bit int and renders e.g. `ffffffa9` instead of `a9`. Two safe idioms:
     - Preferred (Kotlin 2.2, stable, no opt-in): `digest.copyOfRange(0, 8).toHexString(HexFormat { bytes.byteSeparator = ":" })` — `toHexString` is lowercase by default and handles byte width correctly.
     - Explicit fallback: `joinToString(":") { "%02x".format(it.toInt() and 0xFF) }` — the `and 0xFF` mask is mandatory.
   - The pinned zero-vector itself exercises this trap (its digest bytes include `a9 9e c2`, all ≥ 0x80), so an unmasked implementation fails the fixed-vector test rather than slipping through.

### Data flow

```
#320 parse (validates 32-byte len, base64-std) ──► PairedServer.serverStaticPublicKey : String (base64-std)
                                                            │
        [downstream confirm-UI slice, separate ticket]      │ base64StdDecode + size==32 re-check
                                                            ▼
                                              staticKey : ByteArray (32)  ──►  staticKeyFingerprint(staticKey)  ──►  "32:0b:5e:a9:9e:65:3b:c2"
                                                                                                                          │
                                                                                            human compares against desktop `Static-key fp:` line
```

## State + concurrency model

N/A — pure, synchronous, stateless function. No `viewModelScope`, no `StateFlow`, no flows, no dispatcher. A fresh `Blake2sMessageDigest` is constructed per call (no shared mutable state), so it is inherently thread-safe and re-entrant. No coroutines, no `runTest` needed.

## Error handling

| Failure mode | Result | Rationale |
|---|---|---|
| `staticKey.size != 32` | `require(...)` → `IllegalArgumentException` | Programmer-error contract; mirrors the server's "wrong-length is impossible" `[32]byte` intent in the closest Kotlin idiom. |

The `require` failure message MUST be a fixed string (e.g. `"static key must be 32 bytes"`) and MUST NOT interpolate the key bytes. Interpolating `staticKey.size` (an `Int` length, not content) is fine. The input is a *public* key, so this is consistency with the codebase's never-echo-key-material discipline (`PairedServerStore.toString` redacts the token; `parsePairingPayload` logs only fixed reasons), not a secret-leak fix.
| Network / IO / parse / permission | None — function performs no I/O | Pure in-memory hash of a non-secret public key. |
| `Blake2sMessageDigest` construction | Cannot fail | Direct concrete construction; no provider lookup, no checked exception. |

**Why `require` satisfies AC #5 ("handled without throwing into the pairing flow in a way that crashes it"):** AC #5 explicitly permits the function to *assume a validated key if the spec sequences it that way*. It does: #320 validates the 32-byte length before any `PairedServer` is persisted, and the downstream confirm-UI slice re-validates after decoding the stored base64 (mirroring `NoiseSessionFactory.create()`:30-40) — surfacing a typed re-pair failure on a bad/short key **before** calling this function. So the `require` precondition is **structurally unreachable in the real pairing flow**; it exists to fail loud on a programmer error (a future caller passing an unvalidated buffer), not as the flow's input validator. The function is total over all valid (32-byte) inputs and never throws for them. A silent hash of a wrong-length input is deliberately rejected: it would produce a meaningless string that defeats the security purpose, and the loud contract boundary is the better failure mode (consistent with #432's `[32]byte` signature).

## Testing strategy

Unit tests only (`./gradlew test`) — pure JVM, no Android framework, no Compose, no coroutines. New file `app/src/test/java/de/pyryco/mobile/data/crypto/StaticKeyFingerprintTest.kt`, JUnit4 idiom matching `NoiseSuiteSmokeTest`. Scenarios (developer writes the bodies in the project idiom):

- **Pinned fixed vector (the byte-for-byte parity assertion).** Input = 32 zero bytes (`ByteArray(32)`). Expected = the **hard-coded literal** `"32:0b:5e:a9:9e:65:3b:c2"`. The expectation MUST be a literal string, NOT recomputed from `Blake2sMessageDigest` at test time — a recomputed expectation is tautological and passes against a silently-wrong implementation (AC #2). This is the direct mirror of pyrycode#432's `TestFingerprint_FixedVector`.
- **Shape & length invariant (hash-value-independent).** For an arbitrary 32-byte key, assert `result.length == 23` and `result.matches(Regex("^[0-9a-f]{2}(:[0-9a-f]{2}){7}$"))`. Mirrors the server's `TestFingerprint_LengthAndShape`.
- **No sign-extension.** Assert that a key whose digest contains bytes ≥ 0x80 renders them as 2 hex chars, not 8 (the zero vector already covers this via `a9`/`9e`/`c2`; an explicit assertion documents the invariant and guards a future formatting refactor).
- **Determinism.** Same key passed twice yields the identical string (AC #4).
- **Wrong-length precondition.** `staticKeyFingerprint(ByteArray(31))` and `staticKeyFingerprint(ByteArray(33))` each throw `IllegalArgumentException` (documents the contract; use JUnit4 `assertThrows` / `@Test(expected=...)`).

No instrumented tests. Any *additional* known-answer vector beyond the zero vector must also be an **offline-computed literal** (never recomputed in-test), per the same anti-tautology rule; the single pinned zero vector is the SSOT and is sufficient for AC #2.

## Open questions

- None blocking this slice. **Note for the downstream confirm-UI slice (not this ticket):** it must decode `PairedServer.serverStaticPublicKey` (base64-std) and re-validate `size == 32` before calling `staticKeyFingerprint`, mirroring `NoiseSessionFactory.create()`:30-40 (decode → length-check → typed re-pair failure on mismatch). That slice owns the base64→bytes boundary and the user-facing failure surface; this slice deliberately does not.

## Security review

**Verdict:** PASS

This function's entire reason for existing is a security control: a human-verifiable fingerprint that defeats wrong-server / MITM during QR trust-on-first-use pairing. The load-bearing property is **byte-for-byte parity with pyrycode#432** — if parity breaks, the human comparison silently fails open. The walk below treats that as the primary threat.

**Findings:**

- **[Trust boundaries]** No MUST-FIX. The untrusted→trusted boundary (QR-scanned bytes → validated 32-byte key) lives upstream at `PairingPayloadParser.kt:58-63` (#320, parse-before-persist). This function re-asserts the shape at its single explicit entry guard `require(staticKey.size == 32)`; downstream callers hold only a non-secret `String` fingerprint. The "silently displays a wrong fingerprint as valid" attack is closed two ways: implementation drift is caught by the hard-coded (non-recomputed) fixed-vector test, and wrong-length input is rejected by `require` rather than silently hashed.
- **[Tokens, secrets, credentials]** No MUST-FIX. Input is a server **public** key; output is a fingerprint of a public value — neither is secret. The function never touches `PairedServer.token` (it receives only the static-key bytes). It performs no logging. **SHOULD FIX** (folded into Error handling): the `require` message must be a fixed string with no key-byte interpolation — hygiene/consistency with the never-echo-key-material discipline, not a secret-leak fix (the key is public). Code-review verifies.
- **[File / storage operations]** N/A — pure in-memory derivation; no filesystem, no DataStore, no path construction, no TOCTOU, no at-rest persistence, no backup surface.
- **[Inter-process / Android attack surface]** N/A — no Activity/Service/Receiver, no intent or deep-link handling, no PendingIntent, no ContentProvider, no WebView. `data/`-portable, no `android.*` imports.
- **[Cryptographic primitives]** No MUST-FIX. Standard vendored primitive (`Blake2sMessageDigest`, ADR 0004 — the same BLAKE2s as `Noise_IK_25519_ChaChaPoly_BLAKE2s`), **not hand-rolled**. No RNG (deterministic), no key/nonce storage or reuse, no code-level secret comparison (the human eye does the compare, so constant-time comparison is N/A). The spec pins the two crypto-correctness controls: full-256-bit-digest-then-truncate (rejecting the "BLAKE2s-64" parity trap, guaranteed-by-construction here) and the 8-byte/64-bit width (rejecting brute-forceable 32-bit). The width *policy* (is 64 bits enough for a TOFU visual check?) is a settled upstream decision in pyrycode#432's server-side security review; mobile's only obligation is exact parity — relitigating the width is OUT OF SCOPE here.
- **[Network & I/O]** N/A — no network, WebSocket, OkHttp, TLS, or timeouts; the function performs zero I/O.
- **[Error messages, logs, telemetry]** No MUST-FIX. No logging, no telemetry, no crash-reporter surface inside the function (consistent with `parsePairingPayload`'s deliberate no-logging discipline). The fingerprint is intentionally user-displayed — that is the feature, not a leak (a public-key digest, also printed on the desktop). Only error surface is the `require` message — see the [Tokens] SHOULD FIX.
- **[Concurrency]** N/A — synchronous, stateless, no coroutines/scope/flows; a fresh `Blake2sMessageDigest` per call makes it thread-safe and re-entrant by construction. No shared-state TOCTOU.
- **[Threat model alignment]** Addresses the QR-TOFU MITM threat that `protocol-mobile.md` § Security review names the 64-bit fingerprint as the mitigation for; this function is the mobile half of that visual check. Mobile-specific threats (screenshot/overlay capture of the fingerprint) are non-issues here — the fingerprint is a public-key digest, deliberately displayed and also printed by the desktop. The display/screenshot surface itself belongs to the downstream confirm-UI slice — OUT OF SCOPE for this pure derivation.

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-06-01

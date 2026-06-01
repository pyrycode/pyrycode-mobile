# Static-key fingerprint

The **mobile half of the QR-pairing MITM visual check** (`data/crypto/StaticKeyFingerprint.kt`,
[#342](../codebase/342.md)): the pure function that derives the fingerprint the user reads against the
desktop's `Static-key fp:` line during QR trust-on-first-use pairing. The desktop's `pyry pair`
(pyrycode#432) prints an 8-byte BLAKE2s-256 digest of the server's X25519 static public key under the
QR; the phone derives the **byte-for-byte identical** value from the
[`serverStaticPublicKey`](paired-server-store.md) it parsed out of the scanned QR
([#320](pairing-payload-parser.md)). The human compares phone vs. desktop to catch a wrong-server /
MITM. A derivation that diverges by **one byte** makes that comparison meaningless and silently
defeats the check — so byte-for-byte parity with pyrycode#432 is the entire contract.

## What it does

```kotlin
fun staticKeyFingerprint(staticKey: ByteArray): String
```

One public top-level function (the existing pure-function idiom — `parsePairingPayload`,
`base64StdDecode` are likewise top-level; a function-only file is unconstrained by the ktlint
`standard:filename` rule). Given the raw **32-byte** X25519 static public key, it returns the
colon-separated lowercase-hex fingerprint — exactly **23 chars**, matching
`^[0-9a-f]{2}(:[0-9a-f]{2}){7}$` (e.g. `32:0b:5e:a9:9e:65:3b:c2`). Pure, synchronous, no I/O, no
logging, stateless and re-entrant (a fresh `Blake2sMessageDigest` per call). `data/`-portable — its
only import is `com.southernstorm.noise.crypto.Blake2sMessageDigest`; no `android.*`.

## How it works

The algorithm mirrors pyrycode#432's `Fingerprint(pubkey [32]byte) string` in three steps:

1. **Full digest.** Instantiate the vendored [`Blake2sMessageDigest`](../decisions/0004-vendor-noise-java-crypto.md)
   directly and `digest(staticKey)` → the full **32-byte** (256-bit) BLAKE2s digest.
2. **Truncate.** Take the **first 8 bytes**.
3. **Format.** Colon-separated lowercase hex, 2 chars per byte → 23 chars.

The shipped body is two lines:

```kotlin
val digest = Blake2sMessageDigest().digest(staticKey)
return digest.toHexString(0, FINGERPRINT_BYTES, COLON_HEX)   // COLON_HEX = HexFormat { bytes.byteSeparator = ":" }
```

Three correctness landmines are pinned into the design — each is a way the byte-for-byte contract
could silently break:

- **Full-digest-then-truncate, never a configured-short BLAKE2s.** BLAKE2s folds its output length
  into the init parameter block, so a BLAKE2s *configured* to emit 8 bytes ("BLAKE2s-64") produces a
  **different** digest — for the zero key it yields `a8:b3:…`, not the pinned `32:0b:…`. The vendored
  `Blake2sMessageDigest` is **fixed-256-bit-output by construction** (`engineGetDigestLength()` is 32,
  no variable-length constructor), so the trap **cannot bite here** — the note exists so a future swap
  to a variable-length BLAKE2s source doesn't silently break parity.
- **Construct directly, not via JCA.** Use `Blake2sMessageDigest()`, **not**
  `MessageDigest.getInstance("BLAKE2S-256")` — the algorithm name has no registered provider on
  Android and would throw `NoSuchAlgorithmException` at runtime. Direct construction is
  provider-free and deterministic.
- **Signed-`Byte` sign-extension.** Kotlin `Byte` is signed; `"%02x".format(b)` on a byte ≥ `0x80`
  sign-extends to a 32-bit int and renders e.g. `ffffffa9` instead of `a9`. The impl uses Kotlin 2.2's
  `ByteArray.toHexString(start, end, HexFormat)` (stable stdlib, no `@OptIn`), which is lowercase by
  default and byte-width-correct. The explicit-fallback idiom is
  `joinToString(":") { "%02x".format(it.toInt() and 0xFF) }` — the `and 0xFF` mask is mandatory. The
  pinned zero vector itself exercises this (its digest bytes include `a9`, `9e`, `c2`, all ≥ `0x80`),
  so an unmasked implementation fails the fixed-vector test rather than slipping through.

The **8-byte / 64-bit width is load-bearing security**, not a tuning knob — a 32-bit fingerprint is
brute-forceable (~2³² preimage search) and is a spec violation per pyrycode#432's server-side security
review. Never narrow to 4. The width *policy* is a settled upstream decision; mobile's only obligation
is exact parity.

## Input contract — raw 32-byte `ByteArray`, not the base64 string

The function takes the **raw bytes**, mirroring the server's `Fingerprint([32]byte)` (a fixed-length
array, so callers can't pass a wrong-length buffer). The base64-std → bytes decode is a transport /
QR-encoding concern owned by `data/network` ([`base64StdDecode`](mobile-protocol-v2-wire-layer.md)) —
deliberately **not** pulled into `data/crypto`, keeping the layer boundary clean and the function
decoupled from the QR alphabet choice. The pinned test vector (`ByteArray(32)`) is naturally a byte
array too.

Kotlin's `ByteArray` carries no compile-time length, so the fixed-`[32]byte` guarantee the server gets
for free is a documented runtime precondition:

```kotlin
require(staticKey.size == 32) { "static key must be 32 bytes" }
```

The `require` message is a **fixed string** — never interpolating key bytes (consistency with the
codebase's never-echo-key-material discipline; the key is public, so this is hygiene, not a leak fix).
This precondition is **structurally unreachable in the real pairing flow**: #320 validates the 32-byte
length before any `PairedServer` is persisted, and the downstream confirm-UI slice re-validates after
decoding. The `require` exists to fail loud on a *programmer* error (a future caller passing an
unvalidated buffer) — a silent hash of a wrong-length input is deliberately rejected because it would
produce a meaningless fingerprint that defeats the security purpose.

## Configuration / usage

**Live since [#343](../codebase/343.md)** — the [Pairing confirm gate](pairing-confirm-gate.md) is the
first (and currently only) live consumer. It owns the base64→bytes boundary via the new
`serverKeyFingerprint` wrapper (in [`PairingPayloadParser.kt`](pairing-payload-parser.md)), which
decodes + re-validates 32 bytes before calling this function:

```
PairedServer.serverStaticPublicKey : String (base64-std)
        │  base64StdDecode + re-validate size == 32   (mirror NoiseSessionFactory.create():30-40)
        ▼
staticKey : ByteArray(32)  ──►  staticKeyFingerprint(staticKey)  ──►  "32:0b:5e:a9:9e:65:3b:c2"
                                                                              │
                                              human compares against desktop `Static-key fp:` line
```

That slice owns the base64→bytes boundary and the user-facing failure surface (a typed re-pair failure
on a bad/short key, surfaced **before** calling this function) — exactly as the server places the
analogous decode in its `Render`/`fingerprintLine` UI layer, not in `Fingerprint`. The confirm gate
interposes between the parser's `Success` and `PairedServerStore.save(...)`.

## Edge cases and limitations

- **64-bit width is a fixed-by-parity decision.** Whether 64 bits is enough for a TOFU visual check is
  a settled upstream call in pyrycode#432's security review; relitigating the width here is out of
  scope. Mobile's contract is *exact parity*, full stop.
- **Wrong-length input throws, by design.** `staticKeyFingerprint(ByteArray(31))` /
  `ByteArray(33)` throw `IllegalArgumentException`. The loud contract boundary is preferred over
  silently hashing a wrong-length buffer.
- **The display / screenshot surface is not here.** Rendering the fingerprint on screen (and any
  screenshot/overlay concern) belongs to the confirm-UI slice. The value is a public-key digest,
  deliberately displayed and also printed by the desktop — not a secret.
- **Live consumer since #343.** The [Pairing confirm gate](pairing-confirm-gate.md) wires this between
  parse and persist (via `serverKeyFingerprint`, which decodes + re-validates first). The derivation
  itself is unchanged — #343 consumes it, never re-derives.

## Related

- [Pairing confirm gate](pairing-confirm-gate.md) — the #343 security checkpoint, this function's first
  live consumer; renders the derived fingerprint and gates the persist on a human confirm
- [Pairing payload parser](pairing-payload-parser.md) — produces the persisted `PairedServer` whose
  `serverStaticPublicKey` the confirm gate decodes (via `serverKeyFingerprint`) and feeds here; the
  parse/persist split was built to make the confirm gate a clean interpose
- [Paired server store](paired-server-store.md) — holds `serverStaticPublicKey` (base64-std string);
  the source of the bytes this function hashes
- [Mobile Protocol v2 wire layer](mobile-protocol-v2-wire-layer.md) — owns `base64StdDecode` /
  `decodeServerStaticPubkey`, the decode boundary deliberately kept out of `data/crypto`
- [Noise_IK session](noise-ik-session.md) — the transport that later consumes the same static key for
  the encrypted handshake (`NoiseSessionFactory.create()`'s decode-then-validate pattern the confirm
  slice mirrors)
- [ADR 0004 — vendor `noise-java`](../decisions/0004-vendor-noise-java-crypto.md) — the source of
  `Blake2sMessageDigest` (same BLAKE2s as `Noise_IK_25519_ChaChaPoly_BLAKE2s`); no new dependency
- Ticket: [#342](../codebase/342.md) — implementation notes. Split from #321; server SSOT pyrycode#432
  (`internal/pair/render.go` `Fingerprint` + its pinned test vector)

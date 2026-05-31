# Noise_IK session — handshake + AEAD transport (initiator)

The **phone's leg of the Mobile Protocol v2 encrypted transport**: a `Noise_IK_25519_ChaChaPoly_BLAKE2s` **initiator** that drives the Noise handshake against a paired `pyry` daemon, recovers the server's `conn_id`, splits into transport ciphers, and exposes AEAD `encrypt`/`decrypt` over byte arrays. This is the leg that lets the relay stay **untrusted and content-blind** — conversation traffic is end-to-end encrypted between the phone and the daemon; the relay only routes opaque frames.

Package: `de.pyryco.mobile.data.network` (`app/src/main/java/de/pyryco/mobile/data/network/`), sibling to the [wire codec](mobile-protocol-v2-wire-layer.md) ([#273](../codebase/273.md)) it sits on and the WS client ([#276](https://github.com/pyrycode/pyrycode-mobile/issues/276)) that will hold it. Landed in [#298](../codebase/298.md). End-to-end de-risked by the 2026-05-29 proving spike (a `noise-java` initiator interoperated with the Go `flynn/noise` responder against the live `wss://pyrycode-relay.pyryco.de` with zero byte-level debugging).

> **Transport surface is byte-array in / byte-array out.** This layer produces/consumes **raw Noise frame bytes** + the established `conn_id`. The WebSocket, the `InnerFrameV2` outer framing + base64, the inbound frame-size cap, `RemoteConversationRepository`, and the application message set are #276/[#278](https://github.com/pyrycode/pyrycode-mobile/issues/278). The in-place **re-key mechanism** (`writeRekeyInit`/`readRekeyResp`) landed in [#303](../codebase/303.md); its *triggers* — the 1-hour timer and the inbound `rekey_request` dispatch — stay at the future WS pump (needs the relay WS client [#301](https://github.com/pyrycode/pyrycode-mobile/issues/301)).

## Why Noise_IK, and why these inputs

`Noise_IK` (initiator-known-responder) authenticates **both** peers: the responder is authenticated by the QR-pinned server static key (`rs`), and the initiator by its device static key (`s`). A relay-operator MITM without the server's static private half cannot forge a valid `noise_resp` (the `es`/`se` DH fails → MAC failure), and the phone proves its identity in the same handshake. The suite is hardcoded — **no negotiation, no downgrade surface**. See pyrycode-side **ADR 024** (Noise_IK for mobile E2E) and [ADR 0004](../decisions/0004-vendor-noise-java-crypto.md) (the vendored `noise-java` library + suite choice).

Two crypto inputs, both from already-landed producers:

- **Local `s`** — the device X25519 static private key, the phone's long-term identity, custodied by [`DeviceStaticKeyStore`](device-static-keystore.md) ([#291](../codebase/291.md)) per [ADR 0006](../decisions/0006-keystore-wrap-at-rest-device-static-key.md). Handed out as the raw 32-byte scalar.
- **Remote `rs` + token** — the server static public key and the bearer token, custodied by [`PairedServerStore`](paired-server-store.md) ([#294](../codebase/294.md)) in the `PairedServer` record.

## Exported types

```kotlin
// The Android-resolved hello identity, injected so the session stays android.*-free.
data class NoiseClientInfo(val deviceName: String, val clientVersion: String)

// Any handshake / transport / setup failure. Message names the CATEGORY only —
// never key material, plaintext, token, or raw bytes.
class NoiseSessionException(message: String, cause: Throwable? = null) : Exception(message, cause)

class NoiseIkSession(           // a plain class, deliberately NOT a data class (see Secret handling)
    localStaticPrivateKey: ByteArray,   // raw 32-byte device s   (#291)
    remoteStaticPublicKey: ByteArray,   // raw 32-byte server rs   (#294)
    token: String,                      // hello secret            (#294)
    clientInfo: NoiseClientInfo,
) {
    fun writeInit(): ByteArray              // raw noise_init frame (hello as encrypted early-data)
    fun readResp(resp: ByteArray): String   // recover hello_ack; return conn_id; → established
    val connId: String                      // established conn_id (throws until established)
    fun encrypt(plaintext: ByteArray): ByteArray   // → ciphertext (plaintext + 16)
    fun decrypt(ciphertext: ByteArray): ByteArray  // → plaintext  (ciphertext − 16)
    fun writeRekeyInit(s: ByteArray): ByteArray    // re-key: fresh IK handshake vs pinned rs, empty early-data → raw noise_init (#303)
    fun readRekeyResp(resp: ByteArray)             // re-key: derive fresh pair, atomic swap + wipe old; fail-RETAIN (#303)
    fun close()                             // wipe transport ciphers + any pending re-key; idempotent
}

class NoiseSessionFactory(
    deviceStaticKeyStore: DeviceStaticKeyStore,
    pairedServerStore: PairedServerStore,
    clientInfo: NoiseClientInfo,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun create(): NoiseIkSession    // throws NoiseSessionException if setup fails
}
```

No `NoiseSession` interface exists yet — there is no consumer faking the session, so none was introduced (evidence-based: no speculative seam). [#276](https://github.com/pyrycode/pyrycode-mobile/issues/276) extracts one when it needs to fake the session in its WS-client tests.

## Lifecycle (the state machine)

```
NEW ──writeInit()──▶ AWAITING_RESP ──readResp()──▶ ESTABLISHED ──close()──▶ CLOSED
                                                      ▲   │
                            encrypt/decrypt + re-key (writeRekeyInit→readRekeyResp) stay ESTABLISHED
```

- **Constructor** (NEW) — `require` both keys are 32 bytes; build `HandshakeState(PROTOCOL, INITIATOR)`; set the local private key (which derives the matching public key) and pin the remote static `rs`; call `start()`. **Never `setPrologue`** — `start()` mixes the *empty* prologue, and that empty prologue is load-bearing for Go interop (a non-empty prologue diverges the handshake hash and the responder rejects msg1). Setup is eager, so the raw private key is copied into the DH state immediately, shrinking its RAM window.
- **`writeInit()`** (NEW → AWAITING_RESP) — build the `hello` envelope, `writeMessage` it as encrypted early-data into a buffer sized `hello.size + 96` (IK msg1 overhead: 32-byte `e` + 48-byte encrypted `s` + 16-byte payload MAC), drop the in-memory token reference, return the raw frame bytes.
- **`readResp(resp)`** (AWAITING_RESP → ESTABLISHED) — `readMessage` recovers the `hello_ack` early-data; `check(action == SPLIT)`; parse `conn_id`; `split()`. **The initiator does NOT swap**: `sender` = encrypt-outbound, `receiver` = decrypt-inbound (the spike's "one high-risk line"; the responder mirror-swaps). The handshake is then destroyed (it forks independent transport ciphers on `split()` — see Secret handling). **Fail-closed**: any failure transitions to CLOSED, not a half-open state.
- **`encrypt` / `decrypt`** (ESTABLISHED) — `encryptWithAd(null, …)` / `decryptWithAd(null, …)`. **`ad = null` always** (empty associated data is load-bearing — `protocol-mobile.md` mandates *"Implementations MUST NOT pass a non-empty AD without a corresponding spec amendment"*); ciphertext length = plaintext + 16 (Poly1305 tag). `decrypt` rejects ciphertext shorter than the 16-byte tag.
- **`writeRekeyInit(s)` → `readRekeyResp(resp)`** (ESTABLISHED, in place — [#303](../codebase/303.md)) — refresh the transport keys without tearing down the session. `writeRekeyInit` runs a *fresh* IK handshake against the **same pinned `rs`** with **empty** early-data and returns the raw `noise_init`; transport keeps flowing on the **current** keys until `readRekeyResp` derives the fresh pair and **atomically** swaps it in (under the same lock as `encrypt`/`decrypt`), wiping the old pair. `state` stays `ESTABLISHED` throughout — "re-key in flight" is tracked by a private `pendingRekey` field, not a new state, so transport never pauses. **Fail-RETAIN** (unlike `readResp`'s fail-close): any handshake/crypto failure retains the live keys and is **retryable**. See § Re-key below.
- **`close()`** — wipe the handshake (if still held) + the transport `CipherStatePair` + any pending re-key handshake; idempotent; subsequent ops throw `IllegalStateException`.

**Wrong-order calls** (`encrypt` before established, `writeInit` twice, any op after `close`) throw `IllegalStateException` — caller bugs, distinct from `NoiseSessionException` (runtime protocol/crypto failures). The session is **not resumable**: a failed/closed session is discarded and re-created via `factory.create()` (Noise ephemerals are per-handshake).

## `hello` / `hello_ack` (the early-data)

The handshake carries application early-data, framed by the [#273](mobile-protocol-v2-wire-layer.md) wire models and **always (de)serialized via `MobileJson`** (a default `Json {}` would drop defaulted fields like `role`/`protocol_versions`, which is wire-breaking):

- **`hello`** (in `noise_init`) — `HelloClientPayload(deviceName, clientVersion, token)` (`role = "client"` and `protocolVersions = ["v2"]` default) inside `Envelope(id = 1, type = "hello", ts = <RFC3339>, payload)`. The token rides **inside the encrypted early-data**, never a plaintext header (correct v2).
- **`hello_ack`** (in `noise_resp`) — decode `Envelope`, require `type == "hello_ack"`, decode `HelloAckPayload` → `connId`. Any malformed/wrong-type/missing-`conn_id` → `NoiseSessionException("malformed hello_ack")`.

The `Envelope.payload_encrypted` open seam from [#273](mobile-protocol-v2-wire-layer.md) is **resolved as: not here.** This session's transport surface is raw byte arrays; it never constructs an application `noise_msg` envelope. `payload_encrypted` belongs with the application message set ([#278](https://github.com/pyrycode/pyrycode-mobile/issues/278)) that builds those envelopes — adding it here would be speculative and untested.

## Re-key (in-place key rotation) — [#303](../codebase/303.md)

A long-lived session refreshes its AEAD keys in place rather than tearing down and rebuilding. Re-key is a **full fresh `Noise_IK` handshake re-run, initiated by the phone** (IK requires the initiator to start), against the **same pinned `rs`**, with **empty handshake early-data** — the original `hello`/token is **not** re-sent (the handshake itself is the signal; mirrors WireGuard/Tailscale re-key). The wire contract is `protocol-mobile.md` § Re-key; the binary side is the Go responder's atomic swap (`pyrycode` #453), of which this is the **initiator mirror**.

- **`writeRekeyInit(s: ByteArray): ByteArray`** — builds a *fresh* `HandshakeState(PROTOCOL, INITIATOR)` keyed with the caller-supplied device static `s` and the pinned `rs`, `start()` (empty prologue, same as initial), `writeMessage` with zero-length payload into a 96-byte (`IK_MSG1_OVERHEAD`) buffer; stores it as `pendingRekey`; returns the raw `noise_init`. **Requires** `ESTABLISHED` with no re-key already in flight (else `IllegalStateException`).
- **`readRekeyResp(resp: ByteArray)`** — `readMessage` the `noise_resp` (empty early-data — **no `conn_id` parsed**, it is unchanged across re-key); `check(action == SPLIT)`; `split()` (**initiator does NOT swap**, same direction assignment as `readResp`); then the **atomic swap** — `oldPair = ciphers; ciphers = newPair; sender/receiver = newPair.…; oldPair?.destroy()`. The whole method is `@Synchronized`, so the swap cannot interleave with an in-flight same-direction `encrypt`/`decrypt` (no mixed-key / nonce-reuse window). The re-key handshake (and its copy of `s`) is destroyed in `finally` regardless of outcome.

**Two design properties make this safe:**

- **Fail-RETAIN, not fail-close.** A failed re-key (forged/rotated/MITM `noise_resp`, truncated frame, or a handshake that never reaches `SPLIT`) **retains** the live CipherStates — transport continues on the current keys — and throws `NoiseSessionException`; `pendingRekey` is cleared, so the pump may **retry** `writeRekeyInit`. A forged re-key cannot down a working session into a re-handshake an attacker controls, nor leak old-key material. The old keys are wiped **only on a successful swap**.
- **Peer-static continuity is structural, not a byte-compare** (the initiator mirror of #453's responder `bytes.Equal(PeerStatic, …)`). Because the session re-keys against the *same pinned `rs`* every time, a `noise_resp` produced by anyone *not* holding `rs`'s private half (a relay-operator MITM, or a server that rotated its static key) fails `readMessage` (`es`/`se` DH diverge → MAC failure). No attacker-value-vs-secret comparison exists on this path, so there is no constant-time-compare obligation.

**The device static `s` is taken by parameter, never retained on the session.** This preserves the bounded RAM window #298 fought for (see § Threading & key hygiene): `s` lives only inside the pending re-key `HandshakeState`, wiped on `readRekeyResp`/`close`. The caller (the future WS pump) re-loads `s` via `DeviceStaticKeyStore.loadOrCreate(serverId)` and **must zero its buffer after `writeRekeyInit` returns** — mirroring `NoiseSessionFactory.create()`'s discipline. The *triggers* (1-hour timer + inbound `rekey_request`) live at that pump ticket (needs [#301](https://github.com/pyrycode/pyrycode-mobile/issues/301)); this layer is the byte-array(+key)-in / byte-array-out mechanism only.

## Factory wiring

`NoiseSessionFactory.create()` (in `withContext(ioDispatcher)`) resolves the two stores into a ready-to-handshake session and collapses **every** setup failure into a single `NoiseSessionException` (cause chain preserved), so #276 catches one type to surface "re-pair / retry":

| Failure | Surfaced as |
|---|---|
| Not paired (`load()` → `null`) | `NoiseSessionException("not paired")` |
| Stored `rs` not 32 bytes / bad base64 | `NoiseSessionException("invalid server static key")` (no echoed bytes; mirrors `decodeServerStaticPubkey`) |
| Device key undecryptable (needs re-pair) | `NoiseSessionException("device static key unavailable")` (cause preserved) |

After constructing the session, the factory **zeroes its copy of the private-key buffer** (the constructor has already copied it into the DH state). `create()` does **not** open a socket — that is #276's concern.

**DI** (`di/AppModule.kt`) — the only Android-bound values (`Build.MODEL`, `BuildConfig.VERSION_NAME`) are resolved at the Koin module (which is Android-bound) and injected as plain strings, keeping the session + factory portable:

```kotlin
single { NoiseClientInfo(deviceName = Build.MODEL, clientVersion = BuildConfig.VERSION_NAME) }
single { NoiseSessionFactory(get(), get(), get()) }   // DeviceStaticKeyStore, PairedServerStore, NoiseClientInfo
```

The factory is the **first registered consumer** of both [#291](../codebase/291.md) and [#294](../codebase/294.md)'s crypto stores (nothing calls `create()` yet — #276 wires the WS client).

## Threading & key hygiene

- **Single WS message pump expected** (one outbound, one inbound op at a time). As a **deterministic** backstop against the catastrophic, silent nonce-reuse a same-direction race would cause under ChaCha20-Poly1305 (the `sender`/`receiver` `CipherState`s each hold a monotonic nonce counter), `encrypt`/`decrypt`/`close` are `@Synchronized` — **as are `writeRekeyInit`/`readRekeyResp`** ([#303](../codebase/303.md)), so the re-key CipherState swap shares the same monitor and cannot interleave with an in-flight same-direction AEAD op. The lock is the floor, not a license to fan out — keep to the single-pump contract. `encrypt`/`decrypt` use different cipher objects, so they never deadlock and stay independent.
- **No `Log`/`Timber` anywhere** in the session or factory; `NoiseSessionException` messages are category strings only. Key lifecycle: raw private key → copied into the DH state in the constructor → factory zeros its input buffer → `readResp` destroys the handshake post-`split()` (wipes the device-key copy + handshake secrets — `split()` *forks* independent transport ciphers, so the live `CipherStatePair` is untouched) → only the transport keys survive → `close()` destroys them. This bounds the device-static-key RAM window to the handshake, not the whole session.
- **Re-key preserves that RAM-window bound across every rotation** ([#303](../codebase/303.md)). The session **never retains** the device static `s`: `writeRekeyInit` takes it by parameter, copies it into the *pending re-key `HandshakeState`*, and that handshake is the only place `s` lives across the re-key — destroyed on `readRekeyResp` (success or failure) and on `close()`. The old transport `CipherStatePair` is wiped (`oldPair.destroy()`) on a successful swap. Choosing instead to cache `s` on the session would have re-resident the phone's long-term identity in RAM for the whole multi-hour session, defeating the bound on each hourly re-key — hence the by-parameter design (the caller zeroes its buffer after the call).
- **Secret handling.** `NoiseIkSession` is a **plain `class`, deliberately not a `data class`** — it retains `token` until `writeInit`, and a generated `toString` would print it to any crash frame / stray `Log.d("$session")`. `NoiseClientInfo` carries no secret, so its `data class` `toString` is fine.

## Edge cases & limitations

- **Wrong `rs` / tampered `noise_resp`** → `BadPaddingException` mapped to `NoiseSessionException` (#276 maps this to server close `4426`). The recovered `conn_id` is trusted **only because** `readMessage` succeeded, which in IK requires the responder to hold the server static private key.
- **Truncated/garbage frames** → `ShortBufferException` mapped to `NoiseSessionException("malformed …")` (never a raw library leak).
- **No frame-size cap here.** Buffers are sized to input (`ackBuf = resp.size`, `pt = ct.size`), so an uncapped oversized inbound frame is a memory-pressure vector — owned by the WS layer. The **inbound** cap landed in the [relay WS transport](relay-ws-transport.md) ([#306](../codebase/306.md), `MAX_INBOUND_FRAME_CHARS = 128 KiB`), which rejects an over-cap text frame before it is decoded — so bytes never reach this session uncapped. A defensive outbound plaintext cap (65519 B) remains deferred (no observed failure; the Noise/app layer keeps frames within plaintext bounds).
- **In-place re-key** ([#303](../codebase/303.md)). The session now exposes `writeRekeyInit`/`readRekeyResp` to rotate the transport keys over an established session without a teardown (§ Re-key) — fail-RETAIN, retryable, with the old keys wiped on a successful swap. The *triggers* that fire it — the 1-hour timer and the inbound `rekey_request` dispatch — are **not** here; they live at the future WS pump (needs [#301](https://github.com/pyrycode/pyrycode-mobile/issues/301)).
- **Replay** is inherently resisted by the monotonic transport nonce (the session never calls `setNonce` or resets a `CipherState`).

## Testing

JVM-only (`app/src/test/.../NoiseIkSessionTest.kt`, `./gradlew test`) — the vendored `noise-java` suite is pure Java and the session takes raw bytes, so the whole lifecycle runs without a device (unlike the [#291](../codebase/291.md)/[#294](../codebase/294.md) Keystore stores, which need instrumented tests). An in-test **`TestResponder`** harness mirrors the Go `flynn/noise` peer: it drives the responder leg (generate static key, read msg1, write a `hello_ack` as msg2 early-data, `split()` with the responder's mirror-swap) so the initiator is exercised against a real peer, not a stub. The transport round-trip *is* the positive proof of the correct `sender`/`receiver` assignment — a crossed session MAC-fails. `NoiseSessionFactoryTest` fakes both stores (pure-JVM), enabled by the injectable `ioDispatcher`. [#303](../codebase/303.md) extended `TestResponder` with a **re-key leg** (a fresh responder handshake re-using its own static key) and added 9 re-key scenarios — note its **AC #3 caveat**: a peer-static-discontinuity test cannot be staged by re-keying the responder leg with a *different* key (a wrong-key responder can't even `readMessage` the `noise_init`); the faithful stand-in is a well-formed `noise_resp` from a fully independent handshake (`foreignRekeyResp()`).

## Related

- Ticket notes: [`../codebase/298.md`](../codebase/298.md) (the one-shot session) + [`../codebase/303.md`](../codebase/303.md) (the in-place re-key mechanism) — Patterns established + Lessons learned.
- Specs: `docs/specs/architecture/298-noise-ik-session-handshake-aead-transport.md`, `docs/specs/architecture/303-noise-ik-session-rekey.md`.
- Sits on: [Mobile Protocol v2 wire layer](mobile-protocol-v2-wire-layer.md) ([#273](../codebase/273.md)) — `Envelope` / `HelloClientPayload` / `HelloAckPayload` / `MobileJson` / `base64StdDecode`.
- Consumes: [Device static keystore](device-static-keystore.md) ([#291](../codebase/291.md), local `s`) + [Paired server store](paired-server-store.md) ([#294](../codebase/294.md), remote `rs` + token).
- Decisions: [ADR 0004 — vendor noise-java](../decisions/0004-vendor-noise-java-crypto.md) (suite + library), [ADR 0006 — Keystore wrap-at-rest](../decisions/0006-keystore-wrap-at-rest-device-static-key.md) (local `s` custody). Aligns with pyrycode-side ADR 024 (Noise_IK for mobile E2E).
- Downstream (not yet wired): **#276** relay WS client + `RemoteConversationRepository` (frames these bytes as `InnerFrameV2`, owns the WebSocket + inbound frame-size cap, extracts a `NoiseSession` interface); **#278** application message set (`noise_msg` envelopes + `Envelope.payload_encrypted`); the **WS-pump / re-key-trigger ticket** (1-hour timer + inbound `rekey_request` dispatch that call `writeRekeyInit`/`readRekeyResp`; needs **#301**).
- Re-key Go-side mirror (`pyrycode`): #453 responder atomic swap + peer-static continuity check (the responder image of this initiator's re-key); #450 binary-side re-key initiator (1h timer + `rekey_request` emit); #463 `pyry rekey` operator verb. Wire contract: `pyrycode/docs/protocol-mobile.md` § Re-key.
- Spike / byte oracle: vault doc *"Phase 4 — Noise Client Spike Findings"* (`second-brain`, `2026-05-02-pyrycode-mobile/phase-4-noise-client-spike-findings.md`). Go-side counterpart: pyrycode `internal/noise` (flynn/noise IK wrapper) + `docs/protocol-mobile.md`.

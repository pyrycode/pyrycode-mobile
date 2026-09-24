# Noise_IK session — handshake + AEAD transport (initiator)

The **phone's leg of the Mobile Protocol v2 encrypted transport**: a `Noise_IK_25519_ChaChaPoly_BLAKE2s` **initiator** that drives the Noise handshake against a paired `pyry` daemon, recovers the server's `conn_id`, splits into transport ciphers, and exposes AEAD `encrypt`/`decrypt` over byte arrays. This is the leg that lets the relay stay **untrusted and content-blind** — conversation traffic is end-to-end encrypted between the phone and the daemon; the relay only routes opaque frames.

Package: `de.pyryco.mobile.data.network` (`app/src/main/java/de/pyryco/mobile/data/network/`), sibling to the [wire codec](mobile-protocol-v2-wire-layer.md) ([#273](../codebase/273.md)) it sits on and the WS client ([#276](https://github.com/pyrycode/pyrycode-mobile/issues/276)) that will hold it. Landed in [#298](../codebase/298.md). End-to-end de-risked by the 2026-05-29 proving spike (a `noise-java` initiator interoperated with the Go `flynn/noise` responder against the live `wss://pyrycode-relay.pyryco.de` with zero byte-level debugging).

> **Transport surface is byte-array in / byte-array out.** This layer produces/consumes **raw Noise frame bytes** + the established `conn_id`. The WebSocket, the `InnerFrameV2` outer framing + base64, the inbound frame-size cap, `RemoteConversationRepository`, and the application message set are #276/[#278](https://github.com/pyrycode/pyrycode-mobile/issues/278). The in-place **re-key mechanism** (`writeRekeyInit`/`readRekeyResp`) landed in [#303](../codebase/303.md); its *triggers* — the 1-hour timer and the inbound `rekey_request` dispatch — landed in [#304](../codebase/304.md) on the [Noise session pump](noise-session-pump.md) ([#309](../codebase/309.md)).

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
    lastEventId: () -> Long? = { null }, // #416: replay cursor, read LIVE inside buildHello()
) {
    fun writeInit(): ByteArray              // raw noise_init frame (hello as encrypted early-data)
    fun readResp(resp: ByteArray): String   // recover hello_ack; return conn_id; → established
    val connId: String                      // established conn_id (throws until established)
    val negotiatedCapabilities: Set<String> // #401: granted capability set from hello_ack (throws until established)
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
    lastEventId: () -> Long? = { null },    // #416: forwarded verbatim into each session's hello
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
- **`writeInit()`** (NEW → AWAITING_RESP) — build the `hello` envelope (inside `buildHello()`), `writeMessage` it as encrypted early-data into a buffer sized `hello.size + 96` (IK msg1 overhead: 32-byte `e` + 48-byte encrypted `s` + 16-byte payload MAC), drop the in-memory token reference, return the raw frame bytes. **`buildHello()` invokes the `lastEventId` supplier here** ([#416](../codebase/416.md)) — so the [replay cursor](replay-cursor.md) is read **at handshake-build, not at session construction**: each reconnect advertises the cursor as of its own `hello`, never a captured snapshot.
- **`readResp(resp)`** (AWAITING_RESP → ESTABLISHED) — `readMessage` recovers the `hello_ack` early-data; `check(action == SPLIT)`; parse `conn_id` + the negotiated `capabilities` ([#401](../codebase/401.md), see § `hello`/`hello_ack`); `split()`. **The initiator does NOT swap**: `sender` = encrypt-outbound, `receiver` = decrypt-inbound (the spike's "one high-risk line"; the responder mirror-swaps). The handshake is then destroyed (it forks independent transport ciphers on `split()` — see Secret handling). **Fail-closed**: any failure transitions to CLOSED, not a half-open state.
- **`encrypt` / `decrypt`** (ESTABLISHED) — `encryptWithAd(null, …)` / `decryptWithAd(null, …)`. **`ad = null` always** (empty associated data is load-bearing — `protocol-mobile.md` mandates *"Implementations MUST NOT pass a non-empty AD without a corresponding spec amendment"*); ciphertext length = plaintext + 16 (Poly1305 tag). `decrypt` rejects ciphertext shorter than the 16-byte tag.
- **`writeRekeyInit(s)` → `readRekeyResp(resp)`** (ESTABLISHED, in place — [#303](../codebase/303.md)) — refresh the transport keys without tearing down the session. `writeRekeyInit` runs a *fresh* IK handshake against the **same pinned `rs`** with **empty** early-data and returns the raw `noise_init`; transport keeps flowing on the **current** keys until `readRekeyResp` derives the fresh pair and **atomically** swaps it in (under the same lock as `encrypt`/`decrypt`), wiping the old pair. `state` stays `ESTABLISHED` throughout — "re-key in flight" is tracked by a private `pendingRekey` field, not a new state, so transport never pauses. **Fail-RETAIN** (unlike `readResp`'s fail-close): any handshake/crypto failure retains the live keys and is **retryable**. See § Re-key below.
- **`close()`** — wipe the handshake (if still held) + the transport `CipherStatePair` + any pending re-key handshake; idempotent; subsequent ops throw `IllegalStateException`.

**Wrong-order calls** (`encrypt` before established, `writeInit` twice, any op after `close`) throw `IllegalStateException` — caller bugs, distinct from `NoiseSessionException` (runtime protocol/crypto failures). The session is **not resumable**: a failed/closed session is discarded and re-created via `factory.create()` (Noise ephemerals are per-handshake).

## `hello` / `hello_ack` (the early-data)

The handshake carries application early-data, framed by the [#273](mobile-protocol-v2-wire-layer.md) wire models and **always (de)serialized via `MobileJson`** (a default `Json {}` would drop defaulted fields like `role`/`protocol_versions`, which is wire-breaking):

- **`hello`** (in `noise_init`) — `HelloClientPayload(deviceName, clientVersion, token, lastEventId = lastEventId())` (`role = "client"`, `protocolVersions = ["v2"]`, and `capabilities = ["interactive"]` default) inside `Envelope(id = 1, type = "hello", ts = <RFC3339>, payload)`. The daemon validates the token **inside the encrypted early-data**; the WebSocket upgrade also carries the required token [header](relay-ws-transport.md#headers). The `capabilities` default ([#401](../codebase/401.md)) advertises the v2 features the phone understands and rides the wire via `MobileJson`'s `encodeDefaults` — see the [wire layer § Capability negotiation](mobile-protocol-v2-wire-layer.md#capability-negotiation-401). `lastEventId` ([#416](../codebase/416.md)) is the [replay cursor](replay-cursor.md), read **live** via the supplier here; a `null` (fresh connection, nothing observed) is **omitted on encode** by `explicitNulls = false`, so a fresh `hello` stays byte-identical to today.
- **`hello_ack`** (in `noise_resp`) — decode `Envelope`, require `type == "hello_ack"`, decode the whole `HelloAckPayload` (since [#401](../codebase/401.md) `parseHelloAck` returns the payload, not just `connId`). Any malformed/wrong-type/missing-`conn_id` → `NoiseSessionException("malformed hello_ack")` (a non-array `capabilities` is a malformed payload too → same fail-closed throw).
- **Negotiated capabilities ([#401](../codebase/401.md))** — `readResp` extracts both `connId` and `capabilities.toSet()` from the decoded `hello_ack`. The set is surfaced on a **new property** `val negotiatedCapabilities: Set<String>`, mirroring `connId` exactly (written once before `state = ESTABLISHED`; **throws `IllegalStateException` until established**). The **wire `List` → surface `Set`** conversion happens once here, at the post-MAC trust crossing — capabilities are a membership set (`CAPABILITY_INTERACTIVE in negotiatedCapabilities` answers "is it granted?"), and a `Set` dedups a daemon that repeats an entry. A daemon that echoes none decodes to the empty default → empty set (not granted). This is **surfacing-only**: the session gates nothing on the set; the [pump](noise-session-pump.md) carries it onto `PumpState.Open.capabilities` for the eventual decode gate (#385) / stall gate (#395).

The `Envelope.payload_encrypted` open seam from [#273](mobile-protocol-v2-wire-layer.md) is **resolved as: not here.** This session's transport surface is raw byte arrays; it never constructs an application `noise_msg` envelope. `payload_encrypted` belongs with the application message set ([#278](https://github.com/pyrycode/pyrycode-mobile/issues/278)) that builds those envelopes — adding it here would be speculative and untested.

## Re-key (in-place key rotation) — [#303](../codebase/303.md)

A long-lived session refreshes its AEAD keys in place rather than tearing down and rebuilding. Re-key is a **full fresh `Noise_IK` handshake re-run, initiated by the phone** (IK requires the initiator to start), against the **same pinned `rs`**, with **empty handshake early-data** — the original `hello`/token is **not** re-sent (the handshake itself is the signal; mirrors WireGuard/Tailscale re-key). The wire contract is `protocol-mobile.md` § Re-key; the binary side is the Go responder's atomic swap (`pyrycode` #453), of which this is the **initiator mirror**.

- **`writeRekeyInit(s: ByteArray): ByteArray`** — builds a *fresh* `HandshakeState(PROTOCOL, INITIATOR)` keyed with the caller-supplied device static `s` and the pinned `rs`, `start()` (empty prologue, same as initial), `writeMessage` with zero-length payload into a 96-byte (`IK_MSG1_OVERHEAD`) buffer; stores it as `pendingRekey`; returns the raw `noise_init`. **Requires** `ESTABLISHED` with no re-key already in flight (else `IllegalStateException`).
- **`readRekeyResp(resp: ByteArray)`** — `readMessage` the `noise_resp` (empty early-data — **no `conn_id` parsed**, it is unchanged across re-key); `check(action == SPLIT)`; `split()` (**initiator does NOT swap**, same direction assignment as `readResp`); then the **atomic swap** — `oldPair = ciphers; ciphers = newPair; sender/receiver = newPair.…; oldPair?.destroy()`. The whole method is `@Synchronized`, so the swap cannot interleave with an in-flight same-direction `encrypt`/`decrypt` (no mixed-key / nonce-reuse window). The re-key handshake (and its copy of `s`) is destroyed in `finally` regardless of outcome.

**Two design properties make this safe:**

- **Fail-RETAIN, not fail-close.** A failed re-key (forged/rotated/MITM `noise_resp`, truncated frame, or a handshake that never reaches `SPLIT`) **retains** the live CipherStates — transport continues on the current keys — and throws `NoiseSessionException`; `pendingRekey` is cleared, so the pump may **retry** `writeRekeyInit`. A forged re-key cannot down a working session into a re-handshake an attacker controls, nor leak old-key material. The old keys are wiped **only on a successful swap**.
- **Peer-static continuity is structural, not a byte-compare** (the initiator mirror of #453's responder `bytes.Equal(PeerStatic, …)`). Because the session re-keys against the *same pinned `rs`* every time, a `noise_resp` produced by anyone *not* holding `rs`'s private half (a relay-operator MITM, or a server that rotated its static key) fails `readMessage` (`es`/`se` DH diverge → MAC failure). No attacker-value-vs-secret comparison exists on this path, so there is no constant-time-compare obligation.

**The device static `s` is taken by parameter, never retained on the session.** This preserves the bounded RAM window #298 fought for (see § Threading & key hygiene): `s` lives only inside the pending re-key `HandshakeState`, wiped on `readRekeyResp`/`close`. The caller — the [Noise session pump](noise-session-pump.md) ([#309](../codebase/309.md)) since [#304](../codebase/304.md) — re-loads `s` via `NoiseSessionFactory.reloadDeviceStaticKey()` (the factory helper #304 added, which wraps `DeviceStaticKeyStore.loadOrCreate(serverId)`) and **must zero its buffer after `writeRekeyInit` returns** — mirroring `NoiseSessionFactory.create()`'s discipline. The *triggers* (1-hour timer + inbound `rekey_request`) live at that pump (#304); this layer is the byte-array(+key)-in / byte-array-out mechanism only.

## Factory wiring

`NoiseSessionFactory.create()` (in `withContext(ioDispatcher)`) resolves the two stores into a ready-to-handshake session and collapses **every** setup failure into a single `NoiseSessionException` (cause chain preserved), so #276 catches one type to surface "re-pair / retry":

| Failure | Surfaced as |
|---|---|
| Not paired (`load()` → `null`) | `NoiseSessionException("not paired")` |
| Stored `rs` not 32 bytes / bad base64 | `NoiseSessionException("invalid server static key")` (no echoed bytes; mirrors `decodeServerStaticPubkey`) |
| Device key undecryptable (needs re-pair) | `NoiseSessionException("device static key unavailable")` (cause preserved) |

After constructing the session, the factory **zeroes its copy of the private-key buffer** (the constructor has already copied it into the DH state). `create()` does **not** open a socket — that is #276's concern.

`di/RelayConnectionFactory.kt` owns connection construction. Its
`create(record: PairedServer)` entry wraps the supplied immutable record in a
read-only `PairedServerStore` view and gives that same view to the bundle's
`RelayConnectionSupervisor` and `NoiseSessionFactory`. Every dial uses that
record's relay URL, server id and token; the Noise handshake uses its token and
pinned server static key. Both initial device-key loading and
`reloadDeviceStaticKey()` use its server id. Saving another pairing cannot
retarget an explicit-record bundle, even when both hosts share a relay URL.
The app-owned `RelayConnectionRegistry` discovers saved hosts through the
[observable collection](paired-server-store.md#wiring--usage). It closes a removed
or credential-changed bundle before creating its replacement; identical records
and display-name-only edits keep their existing owner.

Each `RelayConnectionBundle` owns its supervisor, session factory and
[coordinator](relay-repository-coordinator.md#configuration). The session factory
receives a supplier over that bundle's coordinator:

```kotlin
lastEventId = { coordinator.replayCursor.latest }
```

The network layer still holds only a `() -> Long?`, with no repository reference
or Koin lookup. Construction stores the lambda; it runs at `hello`-build after
all bundle members exist and the coordinator has started. Reading the owning
coordinator at that moment preserves its live reconnect cursor without resolving
an app-wide coordinator or freezing the value at construction. Reconnect creates
a fresh transport, pump and Noise session while retaining this supplier and its
coordinator. See [replay ownership](relay-repository-coordinator-seams-and-passthroughs.md#reconnect-spanning-replay-cursor-412).

**Registry ownership and compatibility selection.** `appModule` eagerly owns the
registry, which constructs explicit-record bundles for all saved hosts. Empty
startup creates none; a successful pairing save reconciles without waiting for
another foreground edge. Backgrounding closes all supervisors resumably, retaining
each coordinator and cursor for a fresh Noise handshake on resume. Removal,
credential replacement and registry disposal permanently close the affected owners.

Existing app consumers follow the most recently saved surviving bundle, matching
`PairedServerStore.load()`. Changing this compatibility selection does not redial
unaffected hosts. Concrete Koin supervisor/session-factory/coordinator aliases
resolve that retained selection and refuse when none exists; they never create
another connection. `createCompatibility(store)` remains a factory helper but is
no longer used by app DI. See [registry configuration](relay-repository-coordinator.md#configuration)
and [lifecycle guarantees](lifecycle-connection-driver.md#guarantees-delegated-not-re-implemented).

The fake/relay repository selector remains unchanged. `Build.MODEL` and the
`pyrycode-mobile/`-prefixed `clientVersion` (`AppModule.mobileClientVersion()`,
[#1007](about-screen.md#versioning)) enter as plain `NoiseClientInfo` strings from
`AppModule`, keeping the session and factory portable. Setup error categories,
key-buffer wiping and single-connection algorithms are unchanged.

## Threading & key hygiene

- **Single WS message pump expected** (one outbound, one inbound op at a time). As a **deterministic** backstop against the catastrophic, silent nonce-reuse a same-direction race would cause under ChaCha20-Poly1305 (the `sender`/`receiver` `CipherState`s each hold a monotonic nonce counter), **every method that touches shared cipher/handshake state is `@Synchronized` on the instance monitor** — the handshake pair `writeInit`/`readResp` ([#497](../codebase/497.md)), the AEAD/lifecycle `encrypt`/`decrypt`/`close`, and the re-key `writeRekeyInit`/`readRekeyResp` ([#303](../codebase/303.md)). Because they all lock on `this`: the re-key CipherState swap cannot interleave with an in-flight same-direction AEAD op, **and** a teardown `close()` firing mid-handshake cannot wipe-and-null `handshake` (the device static-key copy + handshake secrets) out from under an in-flight `writeInit`/`readResp` — the use-after-wipe [#497](../codebase/497.md) closed. `close()` either wins the race (the handshake step then reads `handshake == null` → a clean `IllegalStateException("session is closed")`, not a wipe-corrupted read) or waits at most one bounded, non-blocking crypto op before wiping. The lock is the floor, not a license to fan out — keep to the single-pump contract. `encrypt`/`decrypt` use different cipher objects, so they never deadlock and stay independent; the handshake pair call only the non-`@Synchronized` private helpers `buildHello()`/`parseHelloAck()`, so there is no nested-lock or lock-ordering question (and `buildHello()`'s injected `lastEventId()` supplier is a lock-free `MutableStateFlow.value` read — no foreign lock is held under the monitor).
- **No `Log`/`Timber` anywhere** in the session or factory; `NoiseSessionException` messages are category strings only. Key lifecycle: raw private key → copied into the DH state in the constructor → factory zeros its input buffer → `readResp` destroys the handshake post-`split()` (wipes the device-key copy + handshake secrets — `split()` *forks* independent transport ciphers, so the live `CipherStatePair` is untouched) → only the transport keys survive → `close()` destroys them. This bounds the device-static-key RAM window to the handshake, not the whole session.
- **Re-key preserves that RAM-window bound across every rotation** ([#303](../codebase/303.md)). The session **never retains** the device static `s`: `writeRekeyInit` takes it by parameter, copies it into the *pending re-key `HandshakeState`*, and that handshake is the only place `s` lives across the re-key — destroyed on `readRekeyResp` (success or failure) and on `close()`. The old transport `CipherStatePair` is wiped (`oldPair.destroy()`) on a successful swap. Choosing instead to cache `s` on the session would have re-resident the phone's long-term identity in RAM for the whole multi-hour session, defeating the bound on each hourly re-key — hence the by-parameter design (the caller zeroes its buffer after the call).
- **Secret handling.** `NoiseIkSession` is a **plain `class`, deliberately not a `data class`** — it retains `token` until `writeInit`, and a generated `toString` would print it to any crash frame / stray `Log.d("$session")`. `NoiseClientInfo` carries no secret, so its `data class` `toString` is fine.

## Edge cases & limitations

- **Wrong `rs` / tampered `noise_resp`** → `BadPaddingException` mapped to `NoiseSessionException` (#276 maps this to server close `4426`). The recovered `conn_id` is trusted **only because** `readMessage` succeeded, which in IK requires the responder to hold the server static private key.
- **Truncated/garbage frames** → `ShortBufferException` mapped to `NoiseSessionException("malformed …")` (never a raw library leak).
- **No frame-size cap here.** Buffers are sized to input (`ackBuf = resp.size`, `pt = ct.size`), so an uncapped oversized inbound frame is a memory-pressure vector — owned by the WS layer. The **inbound** cap landed in the [relay WS transport](relay-ws-transport.md) ([#306](../codebase/306.md), `MAX_INBOUND_FRAME_CHARS = 128 KiB`), which rejects an over-cap text frame before it is decoded — so bytes never reach this session uncapped. A defensive outbound plaintext cap (65519 B) remains deferred (no observed failure; the Noise/app layer keeps frames within plaintext bounds).
- **In-place re-key** ([#303](../codebase/303.md)). The session now exposes `writeRekeyInit`/`readRekeyResp` to rotate the transport keys over an established session without a teardown (§ Re-key) — fail-RETAIN, retryable, with the old keys wiped on a successful swap. The *triggers* that fire it — the 1-hour timer and the inbound `rekey_request` dispatch — are **not** here; they landed in [#304](../codebase/304.md) on the [Noise session pump](noise-session-pump.md) ([#309](../codebase/309.md)).
- **Replay** is inherently resisted by the monotonic transport nonce (the session never calls `setNonce` or resets a `CipherState`).

## Testing

`di/RelayConnectionFactoryTest.kt` exercises two explicit-record bundles with
distinct real Noise responder keys and device keys while sharing a relay URL.
It decrypts each `hello` token, verifies the device public key on the responder,
then re-keys both after changing the shared store and checks caller-buffer
wiping. This covers both initial authentication and the separate key-reload
path; constructor arguments alone do not prove the encrypted exchange. The same
harness decrypts distinct reconnect cursors for the two bundles and checks that
an empty cursor omits `last_event_id`.

JVM-only (`app/src/test/.../NoiseIkSessionTest.kt`, `./gradlew test`) — the vendored `noise-java` suite is pure Java and the session takes raw bytes, so the whole lifecycle runs without a device (unlike the [#291](../codebase/291.md)/[#294](../codebase/294.md) Keystore stores, which need instrumented tests). An in-test **`TestResponder`** harness mirrors the Go `flynn/noise` peer: it drives the responder leg (generate static key, read msg1, write a `hello_ack` as msg2 early-data, `split()` with the responder's mirror-swap) so the initiator is exercised against a real peer, not a stub. The transport round-trip *is* the positive proof of the correct `sender`/`receiver` assignment — a crossed session MAC-fails. `NoiseSessionFactoryTest` fakes both stores (pure-JVM), enabled by the injectable `ioDispatcher`. [#303](../codebase/303.md) extended `TestResponder` with a **re-key leg** (a fresh responder handshake re-using its own static key) and added 9 re-key scenarios — note its **AC #3 caveat**: a peer-static-discontinuity test cannot be staged by re-keying the responder leg with a *different* key (a wrong-key responder can't even `readMessage` the `noise_init`); the faithful stand-in is a well-formed `noise_resp` from a fully independent handshake (`foreignRekeyResp()`). [#401](../codebase/401.md) added **5 capability scenarios** (and threaded a `capabilities` parameter through the `ackEnvelope` helper): hello advertises `interactive` end-to-end through `writeInit`; `hello_ack` echoing it → `negotiatedCapabilities` granted; `hello_ack` omitting it → empty set + `connId` preserved; a non-array `capabilities` → `NoiseSessionException` (fail-closed); pre-handshake property access throws.

## Related

- Ticket notes: [`../codebase/298.md`](../codebase/298.md) (the one-shot session) + [`../codebase/303.md`](../codebase/303.md) (the in-place re-key mechanism) + [`../codebase/401.md`](../codebase/401.md) (advertise + surface the negotiated `interactive` capability) + [`../codebase/416.md`](../codebase/416.md) (advertise the [replay cursor](replay-cursor.md) as `last_event_id` — the `lastEventId` supplier read in `buildHello()`) + [`../codebase/497.md`](../codebase/497.md) (extend the `@Synchronized` floor to the handshake pair `writeInit`/`readResp` — closes the mid-handshake `close()` use-after-wipe) — Patterns established + Lessons learned.
- Specs: `docs/specs/architecture/298-noise-ik-session-handshake-aead-transport.md`, `docs/specs/architecture/303-noise-ik-session-rekey.md`, `docs/specs/architecture/401-advertise-surface-interactive-capability.md`, `docs/specs/architecture/416-advertise-replay-cursor-last-event-id.md`.
- Sits on: [Mobile Protocol v2 wire layer](mobile-protocol-v2-wire-layer.md) ([#273](../codebase/273.md)) — `Envelope` / `HelloClientPayload` / `HelloAckPayload` / `MobileJson` / `base64StdDecode`; the `capabilities` field + `CAPABILITY_INTERACTIVE` token ([#401](../codebase/401.md), see [§ Capability negotiation](mobile-protocol-v2-wire-layer.md#capability-negotiation-401)).
- Consumes: [Device static keystore](device-static-keystore.md) ([#291](../codebase/291.md), local `s`) + [Paired server store](paired-server-store.md) ([#294](../codebase/294.md), remote `rs` + token).
- Decisions: [ADR 0004 — vendor noise-java](../decisions/0004-vendor-noise-java-crypto.md) (suite + library), [ADR 0006 — Keystore wrap-at-rest](../decisions/0006-keystore-wrap-at-rest-device-static-key.md) (local `s` custody). Aligns with pyrycode-side ADR 024 (Noise_IK for mobile E2E).
- Downstream: the [Noise session pump](noise-session-pump.md) ([#309](../codebase/309.md), **landed**) drives this session over the [relay WS transport](relay-ws-transport.md) ([#306](../codebase/306.md)) — `create()`/`writeInit`/`readResp`/`encrypt`/`decrypt`/`close`, and (since [#304](../codebase/304.md), **landed**) `writeRekeyInit`/`readRekeyResp` + `reloadDeviceStaticKey` for the 1-hour-timer / `rekey_request` re-key triggers. Still to come: **#278** application message set / `RemoteConversationRepository` (`noise_msg` envelopes + `Envelope.payload_encrypted` on the pump's `inbound`/`send`).
- Re-key Go-side mirror (`pyrycode`): #453 responder atomic swap + peer-static continuity check (the responder image of this initiator's re-key); #450 binary-side re-key initiator (1h timer + `rekey_request` emit); #463 `pyry rekey` operator verb. Wire contract: `pyrycode/docs/protocol-mobile.md` § Re-key.
- Spike / byte oracle: vault doc *"Phase 4 — Noise Client Spike Findings"* (`second-brain`, `2026-05-02-pyrycode-mobile/phase-4-noise-client-spike-findings.md`). Go-side counterpart: pyrycode `internal/noise` (flynn/noise IK wrapper) + `docs/protocol-mobile.md`.

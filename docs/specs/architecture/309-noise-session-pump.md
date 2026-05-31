# 309 — Noise session pump: drive the IK handshake + run the open-state `noise_msg` loop (Phase 4)

Drives the `Noise_IK` handshake over the landed relay WS transport (#306) and runs the open-state
`noise_msg` decrypt/dispatch loop on top of the landed `NoiseIkSession` (#303). The mobile mirror of
the Go binary's `V2SessionManager`: it executes `protocol-mobile.md` § Connection lifecycle → **Phone
steps 3–6** so that encrypted-session consumers attach to **one** running session instead of each
re-implementing the handshake and the decrypt loop.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/network/RelayTransport.kt` (whole file, 82 lines) — the
  transport surface this pump consumes. Extract: `inbound: Flow<InnerFrameV2>` (**single-consumer**,
  buffered, completes after the terminal `Down`); `send(InnerFrameV2): Boolean` (non-throwing
  enqueue); `close()` (idempotent); the single-use `NEW → DIALING → UP → terminal` lifecycle. The
  pump is the **sole** collector of `inbound`; it does **not** consume `events` (that's #307's).
- `app/src/main/java/de/pyryco/mobile/data/network/NoiseIkSession.kt:62-159` + `:233-274` — the
  session contract: `writeInit(): ByteArray` (NEW→AWAITING_RESP), `readResp(ByteArray): String`
  (returns `connId`; throws `NoiseSessionException` on MAC failure / malformed `hello_ack`),
  `encrypt`/`decrypt` (ESTABLISHED-only, `@Synchronized`), `close()` (wipes keys, idempotent). Note
  the lifecycle KDoc (`:43-60`): out-of-order calls throw `IllegalStateException`; not resumable.
- `app/src/main/java/de/pyryco/mobile/data/network/NoiseSessionFactory.kt` (whole file, 58 lines) —
  `suspend fun create(): NoiseIkSession`, runs on an injected `ioDispatcher`, collapses every setup
  failure into one `NoiseSessionException`. The pump owns a session built via this. The `ioDispatcher`
  ctor param is the test seam (pass the virtual-clock dispatcher).
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireModels.kt:22-44` — `InnerFrameV2(v,
  type, data)` (the wire frame the transport carries) and `Envelope(id, type, ts, payload, inReplyTo)`
  (the decrypted application payload; `payload` is untrusted raw `JsonElement`). These are the pump's
  inbound output and outbound input types.
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireCodec.kt:29-40` — `MobileJson` (the one
  configured `Json`; always use it, never a default `Json`), `base64StdEncode` / `base64StdDecode`
  (Go `base64.StdEncoding`). The pump base64-wraps `noise_msg`/`noise_init` `data` with these.
- `app/src/main/java/de/pyryco/mobile/data/network/RelayConnectionSupervisor.kt:40-104` — the **sibling**
  (#307). Extract the ownership boundary: it consumes `events` only, publishes the live transport on
  `currentConnection: StateFlow<RelayTransport?>` (set on `Up`, null on `Down`), and explicitly leaves
  `inbound` to "the sibling Noise session pump (#309)". A layer-up coordinator (#302) hands this
  connection's `inbound` to a fresh pump per connection — **this ticket does not wire that.**
- `app/src/test/java/de/pyryco/mobile/data/network/NoiseIkSessionTest.kt:324-457` — the `TestResponder`
  helper (the in-test mirror of the Go `flynn/noise` responder) + `establish()` / `ackEnvelope()` /
  `newPrivateKey()`. It's `private` to that test; the pump test **copies the pattern** (a responder is
  required to produce a real `noise_resp` and real `noise_msg` ciphertexts). Note the cipher direction
  at `:63-73`: pump-out → `responderPair.receiver` decrypts; `responderPair.sender` encrypts →
  pump-in decrypts.
- `app/src/test/java/de/pyryco/mobile/data/network/RelayConnectionSupervisorTest.kt:36-83`, `:342-390`
  — the `runTest` virtual-clock posture and the `FakeRelayTransport` shape. The pump test needs a
  **richer** fake (the supervisor's stubs `inbound = emptyFlow()`): a `Channel`-backed `inbound` the
  test pushes frames into, a captured `sentFrames` list, and a `close()` that completes `inbound`.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt:45-55` — how #306/#307 bind (factory + dormant
  singleton). Extract the "ships dormant" precedent. **This ticket adds no Koin binding** (see Design).
- `pyrycode/docs/protocol-mobile.md` § Connection lifecycle → Phone (steps 3–8), § Wire shapes,
  § Transport, § Re-key (read-only, in the upstream `pyrycode` repo). Extract: the three outer frame
  `type` strings (`noise_init` / `noise_resp` / `noise_msg`), the steps-3–6 sequence, AEAD `ad = nil`,
  the 10-second `noise_resp` deadline (step 4), and that re-key is a **separate** flow (#304).

## Context

Phase 4's encrypted transport is a chain: raw WS frames (#306, landed) → **encrypted session (this
ticket)** → typed consumers (#278 repo, #304 re-key triggers). The handshake-drive + open-state
dispatch loop fell through the #301 → #306/#307 split (#306 took "raw frames", #307 took
"reconnect/backoff"; neither took "run the Noise session"), surfaced during #304's architect review.

Today the primitives have **zero callers**: `NoiseIkSession.writeInit/readResp/encrypt/decrypt` are
byte-array-in/out with no driver, and `RelayTransport.inbound` carries opaque frames with no decrypt.
This pump is the missing glue that binds them into one running session.

**Scope & sizing.** Sized **S** and kept whole (no split). It is orchestration glue: **1** new
production file, **2** new exported types, **0** consumer call-site changes (additive, ships dormant —
zero edit fan-out), **5** AC. The error triggers funnel into **one** idempotent teardown path (no
per-branch logging — the pump emits no logs), so the reject-branch fan-out does not multiply turns.
The pre-authorised S→split (peel outbound `send` into a follow-up; ticket "Sizing note") is **not**
taken: outbound `send` is ~15 production lines over the *same* session+transport+test scaffold the
handshake/inbound loop already stands up — splitting it would duplicate the fake-transport + responder
+ store-fake harness to test 15 lines, costing more total turns, not fewer.

**Ownership (what this pump owns / does not own).** Owns: a connection-scoped `CoroutineScope`, the
`NoiseIkSession` built via `NoiseSessionFactory.create()`, the handshake drive to an open session, the
**single** inbound collector that decrypts each `noise_msg`, the outbound encrypt+frame path, and clean
lifecycle teardown tied to the transport. Does **not** own: the socket / reconnect / backoff (#306 +
#307); the crypto state machine or re-key mechanism (#303 — `writeRekeyInit`/`readRekeyResp` are not
called here); typed app-message / wire↔domain mapping (#278 builds that on this pump's `Envelope`
surface).

## Design

### Public surface (2 new exported types, both in `data/network/NoiseSessionPump.kt`)

```kotlin
class NoiseSessionPump(
    transport: RelayTransport,                 // already Up — supervisor published it on currentConnection
    sessionFactory: NoiseSessionFactory,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,   // crypto is CPU-bound; create() switches to IO itself
    handshakeTimeoutMs: Long = HANDSHAKE_TIMEOUT_MS,         // 10_000 — protocol step 4
) {
    val state: StateFlow<PumpState>            // handshake-completion + lifecycle signal (AC 1, AC 5)
    val inbound: Flow<Envelope>                // hot, single-consumer, decrypted app frames (AC 2)
    fun start()                                // single-use; launches the session-drive coroutine
    fun send(envelope: Envelope): Boolean      // encrypt+frame+send; false unless Open (AC 3)
    fun close()                                // idempotent teardown; wipes session keys (AC 5)
}

sealed interface PumpState {
    data object Handshaking : PumpState        // initial; noise_init sent, awaiting noise_resp
    data class Open(val connId: String) : PumpState     // the handshake-completion signal
    data class Closed(val cause: Throwable?) : PumpState // terminal; cause = null on a clean Down
}
```

`state` is the **handshake-completion signal** AC 1 requires: consumers observe for `Open` (e.g.
`state.filterIsInstance<PumpState.Open>().first()`) before they begin sending. `Closed` is terminal.

### Construction & ownership — ships dormant

Per the Phase-4 "no central flag; gate per-piece" pattern: the pump class lands with **no Koin
binding**. It is a **per-connection** object (not a singleton): a layer-up coordinator (#302, future
wiring) observes `RelayConnectionSupervisor.currentConnection`, and on a non-null transport constructs
`NoiseSessionPump(transport, koin.get<NoiseSessionFactory>()).also { it.start() }`. `NoiseSessionFactory`
is already a Koin `single` (AppModule.kt:46). Wiring the pump to `currentConnection` is **#302's job,
not this ticket's** — adding a binding now would be dead code with no consumer. The pump receives a
transport that is **already `Up`**; it never calls `transport.connect()`.

### Handshake drive (AC 1) — `protocol-mobile.md` Phone steps 3–6

`start()` is single-use (a second call throws `IllegalStateException`, mirroring `transport.connect()`)
and launches **one** coroutine in the pump scope that:

1. `session = sessionFactory.create()` (suspends on the factory's IO dispatcher). On
   `NoiseSessionException` (not paired / key unavailable / suite missing) → `teardown(cause)`, return.
2. `transport.send(InnerFrameV2(type = "noise_init", data = base64StdEncode(session.writeInit())))`.
3. Await the first inbound frame, **bounded by `handshakeTimeoutMs`** (protocol step 4: "await
   `noise_resp` within 10 seconds"). Recommended shape:
   `val first = withTimeoutOrNull(handshakeTimeoutMs) { transport.inbound.first() }`. Timeout (`null`)
   or an early `Down` (the flow completes before the first element → `NoSuchElementException`) →
   `teardown(cause)`.
4. Require `first.type == "noise_resp"` (else → teardown); `connId = session.readResp(base64StdDecode(
   first.data))`; `state.value = PumpState.Open(connId)`. `readResp` throwing `NoiseSessionException`
   (MAC failure / malformed `hello_ack`) → teardown.
5. Then run the open-state loop over the **remaining** frames: `transport.inbound.collect { onOpenFrame(it) }`.

> **Single-collector contract.** Steps 3 and 5 are the *same* logical consumer of the
> single-consumer `inbound`. `RelayTransport.inbound` is `receiveAsFlow()`-backed, which permits
> *sequential* re-collection (a `.first()` followed by a `.collect()` distributes — never duplicates —
> elements; the channel buffers anything that arrives in the gap, so no frame is lost). Do **not**
> split this across two coroutines or two components — that would fight #306's single-consumer contract
> and force a fragile hand-off. (Go built the equivalent as one `V2SessionManager`.) A
> single-`collect`-loop-with-a-timeout-watchdog variant is acceptable too; either keeps one collector.

### Open-state dispatch (AC 2) — the `when (frame.type)` seam

`onOpenFrame(frame)` is a clean dispatch on the outer frame type:

- `"noise_msg"` → `plaintext = session.decrypt(base64StdDecode(frame.data))` →
  `envelope = MobileJson.decodeFromString<Envelope>(plaintext.decodeToString())` →
  `inboundChannel.send(envelope)` (suspending — see § State + concurrency). **Emit only after a
  successful decrypt + parse** (never surface an unauthenticated/partial frame).
- `else` → `teardown(NoiseSessionException("unexpected open-state frame type"))`. **This `else` is the
  #304 seam** — the re-key triggers ticket inserts a `"noise_resp"` branch *before* it to route a
  re-key response. Do **not** implement re-key here.

A `base64StdDecode` failure (`IllegalArgumentException`), a `decrypt` failure
(`NoiseSessionException` — forged/tampered/replayed ciphertext fails AEAD), or an `Envelope` parse
failure (`SerializationException`) all route to `teardown`: the ordered encrypted stream cannot skip a
frame, so any undecryptable/unparseable frame tears the session down rather than dropping (mirrors
#306's local-protocol-violation posture and the session's `decrypt` contract).

### Outbound send (AC 3) — ordering is the load-bearing invariant

`send(envelope): Boolean` mirrors `transport.send`'s non-throwing `Boolean` contract:

- If `state.value !is PumpState.Open` → return `false` (no frame sent).
- Otherwise, **atomically** (under the pump's monitor — see § State + concurrency):
  `ct = session.encrypt(MobileJson.encodeToString(envelope).encodeToByteArray())`;
  `return transport.send(InnerFrameV2(type = "noise_msg", data = base64StdEncode(ct)))`.

The encrypt→enqueue pair **must** be one critical section. The AEAD nonce is a per-session monotonic
counter the wire does not carry (`protocol-mobile.md` § Transport); the receiver derives it from its
own counter and ordered WS delivery. If two concurrent sends encrypt as nonce *N*, *N+1* but enqueue
*N+1* before *N*, the receiver MAC-fails on the reordered frame and the connection dies. The lock makes
wire order == nonce order. A `send` racing `teardown` (session already closed → `encrypt` throws
`IllegalStateException`) returns `false` rather than throwing.

### Data flow

```
inbound:   relay ──WS text──▶ #306 transport.inbound ─(noise_msg)─▶ [single collector]
                                base64Decode ─▶ session.decrypt ─▶ MobileJson<Envelope> ─▶ pump.inbound ─▶ #278
outbound:  #278 ─(Envelope)─▶ pump.send ─[lock]─ MobileJson ─▶ session.encrypt ─▶ base64Encode ─▶ #306 transport.send ─▶ relay
handshake: start ─▶ session.writeInit ─▶ (noise_init) #306.send ；await (noise_resp) ─▶ session.readResp ─▶ state=Open(connId)
```

## State + concurrency model

- **Scope.** The pump owns `CoroutineScope(SupervisorJob() + dispatcher)` (default `Dispatchers.Default`
  — crypto is CPU-bound; `NoiseSessionFactory.create()` switches to IO internally). **One** coroutine
  is launched (the session drive of § Handshake + § Open-state). Connection-scoped — cancelled on
  teardown; nothing outlives the connection (AC 5). No `GlobalScope`, no application-scope.
- **`state`.** A single `MutableStateFlow<PumpState>(Handshaking)` exposed as `StateFlow`. Single
  source of truth for lifecycle; only the drive coroutine writes `Open`, only `teardown` writes
  `Closed`. `send` reads it.
- **`inbound`.** Internally `Channel<Envelope>(capacity = Channel.BUFFERED)` exposed as
  `channel.receiveAsFlow()` — hot (the collector runs independently of any subscriber),
  **single-consumer** (mirrors #306; the one external consumer is #278's repo — #304 extends the
  *internal* dispatch, not this stream). The collector `send`s (suspending) into it, so a slow/absent
  consumer applies backpressure all the way down to the transport's `inbound` and TCP flow-control —
  **lossless and in-order** (a dropped app frame is unacceptable). The channel is closed by `teardown`,
  completing the consumer's flow.
- **Ordering lock.** `send` is `@Synchronized` (or holds a single pump `Mutex`) spanning the
  encrypt→enqueue pair (§ Outbound). The session's own `@Synchronized` protects `encrypt` alone; it does
  **not** cover the gap to `transport.send`, so the pump must add this. Inbound order is guaranteed
  structurally by the single sequential collector (one `decrypt` at a time, in arrival order).
- **Teardown — one idempotent path for every trigger.** `teardown(cause: Throwable?)` is guarded by a
  CAS/`AtomicBoolean` (runs once, mirroring #306's `terminated`). It: (1) `state.value =
  Closed(cause)`; (2) `inboundChannel.close()`; (3) `session?.close()` (**wipes keys** — AC 5); (4)
  `transport.close()` (**idempotent** — a no-op on the clean-`Down` path where the transport already
  closed, but the active teardown on a fatal-frame path, so the supervisor observes `Down` and
  reconnects); (5) `scope.cancel()` last. Callable from inside the collector (fatal frame) and from
  outside (`close()`); idempotency makes both safe. `cause = null` ⟺ clean `Down`; non-null ⟺ a
  protocol/crypto fault.
- **Down detection.** The pump keys off `transport.inbound` **completing** (which #306 guarantees on
  the terminal `Down`). When the open-state `collect` returns normally, the drive calls
  `teardown(null)`. The pump does **not** observe `events` (owned by #307) — completion of `inbound` is
  its `Down` signal. This keeps the single-consumer contracts clean: #307 owns `events`, the pump owns
  `inbound`.
- **Not resumable.** `start()` once; after `Closed` the instance is spent. A fresh connection builds a
  fresh pump (mirrors #306/#303 single-use; Noise ephemerals are per-handshake).
- **Shutdown safety.** The pump persists nothing; process death mid-send leaves no partial on-disk
  state. Next launch = fresh transport + fresh pump + fresh handshake.

## Error handling

The pump emits **no logs** (aligned with #307's no-logs posture and #303's no-secrets-in-logs rule).
Every failure surfaces only via `PumpState.Closed(cause)`; `cause` is a `NoiseSessionException` whose
message is **category-only** (#303 guarantees no key material / plaintext / token / raw frame bytes).
Any new exception message the pump mints (`"unexpected open-state frame type"`, `"handshake timeout"`,
`"noise_resp expected"`) must stay category-only — never interpolate `frame.data`, decrypted plaintext,
or token.

| Failure | Layer | Result type | Surfaced as |
|---|---|---|---|
| Not paired / device key / suite unavailable | `factory.create()` | `NoiseSessionException` | `teardown` → `Closed(cause)` |
| `noise_resp` not received in `handshakeTimeoutMs` | handshake | timeout (`null`) | `teardown` → `Closed(timeout cause)` + `transport.close()` |
| First frame not `noise_resp` | handshake | — | `teardown` → `Closed` |
| `noise_resp` MAC failure / malformed `hello_ack` | `session.readResp` | `NoiseSessionException` | `teardown` → `Closed(cause)` |
| `noise_msg` base64 / decrypt / parse failure | open-state | `IllegalArgumentException` / `NoiseSessionException` / `SerializationException` | `teardown` → `Closed(cause)` (stream can't skip a frame) |
| Unknown open-state frame type | open-state | `NoiseSessionException` | `teardown` → `Closed` (the #304 seam) |
| Transport `Down` (inbound completes) | lifecycle | — | `teardown(null)` → `Closed(null)`; session wiped |
| `send` while not `Open` (or racing teardown) | outbound | — | returns `false` (no throw) |

No path crashes the process or leaks a coroutine (AC 4, AC 5): every fault converges on the single
idempotent `teardown`, which cancels the scope and wipes the session.

## Testing strategy

Pure-JVM unit tests (`./gradlew test`) — `data/`-layer, no device, same posture as
`NoiseIkSessionTest` / `RelayConnectionSupervisorTest`. Driven by `runTest`'s virtual clock with an
injected `StandardTestDispatcher` for both the pump and the factory's `ioDispatcher`.

**Harness the developer builds (in the test file):**

- A `FakeRelayTransport`: `inbound` backed by a `Channel<InnerFrameV2>` the test pushes into (helpers
  `pushInbound(frame)`, `completeInbound()` = simulate `Down`); `send` captures into a `sentFrames`
  list and returns `true`; `close()` records the call and completes `inbound`; `events = emptyFlow()`
  (the pump never reads it); `connect()` asserts it is **never** called.
- A `TestResponder` (copy the pattern from `NoiseIkSessionTest:371-427`): a real IK responder that
  `readInit`s the pump's `noise_init`, `writeResp`s a `hello_ack` (via `ackEnvelope`), `split()`s, and
  exposes helpers to **encrypt a `noise_msg`** (`responderPair.sender`) and **decrypt** the pump's
  outbound (`responderPair.receiver`). Mind the cipher direction (NoiseIkSessionTest:63-73).
- Store fakes so `NoiseSessionFactory.create()` returns a session pinned to the responder: a
  `DeviceStaticKeyStore` returning a fresh 32-byte keypair, and a `PairedServerStore` returning a
  `PairedServer` whose `serverStaticPublicKey = base64StdEncode(responder.staticPublicKey)`, `token =
  "tok"`. Build `NoiseSessionFactory(fakeDev, fakePaired, clientInfo, ioDispatcher = testDispatcher)`.

**Scenarios (bulleted; the developer writes the bodies in the project idiom):**

- *AC 1 — handshake → Open.* `start()` → exactly one frame sent, `type == "noise_init"`, its `data`
  base64-decodes to an IK msg1 the responder accepts; feed the responder's `noise_resp` → `state`
  goes `Handshaking → Open(connId)` with `connId` == the `ackEnvelope`'s `conn_id`.
- *AC 1 — handshake timeout.* `start()`, send `noise_init`, never feed `noise_resp`;
  `advanceTimeBy(handshakeTimeoutMs)` → `state` is `Closed`; `transport.close()` was called.
- *AC 1 — `noise_resp` MAC failure.* Feed a `noise_resp` from an **independent** responder (different
  static, à la `foreignRekeyResp`) → `state` is `Closed(cause is NoiseSessionException)`; transport
  closed.
- *AC 1 — wrong first-frame type.* First inbound frame is a `noise_msg` (not `noise_resp`) →
  `Closed`.
- *AC 2 — inbound `noise_msg` → Envelope.* After `Open`, push a responder-encrypted `noise_msg` whose
  plaintext is a known `Envelope` → `pump.inbound` emits exactly that `Envelope` (id/type/payload).
- *AC 2 — undecryptable inbound frame.* After `Open`, push a `noise_msg` with truncated/garbage
  ciphertext → `Closed`, `transport.close()` called, no crash, `pump.inbound` completes.
- *AC 2 — malformed Envelope plaintext.* After `Open`, responder encrypts non-JSON plaintext →
  decrypt OK, parse fails → `Closed`.
- *AC 2 — unknown open-state frame type.* After `Open`, push a frame with an unrecognised `type` →
  `Closed` (pins the #304 `else` seam).
- *AC 3 — outbound send.* After `Open`, `send(env)` returns `true`; the captured frame is a
  `noise_msg` whose `data`, base64-decoded and decrypted by `responderPair.receiver`, parses to `env`.
- *AC 3 — send before Open / after Closed.* `send` returns `false` and captures no frame.
- *AC 5 — transport Down.* After `Open`, `completeInbound()` → `state` is `Closed(null)`; the session
  is wiped (a subsequent `send` returns `false`); `advanceUntilIdle()` leaves no active coroutine.
- *AC 5 — `close()` idempotent.* Two `close()` calls → no throw; `state` `Closed`; session wiped.

## Open questions

- **`v` field / `4421` close code.** Protocol says a receiver MUST reject `v != 2` and close with
  `4421`. The pump can't carry a `4421` (the transport's `close()` is parameterless), and the server is
  proven to send `v = 2` (2026-05-29 spike). Deferred: the pump does not validate `frame.v`, and on a
  protocol violation it `transport.close()`s with normal closure; the supervisor reconnects regardless.
  A transport-surface extension to carry a specific WS close code is out of scope (future ticket).
- **`last_seen_ts` backfill.** Protocol step 3 notes `hello` may carry `last_seen_ts` to trigger
  backfill. The landed `NoiseIkSession.writeInit`/`HelloClientPayload` (#303) don't include it; the
  pump calls `writeInit` as-is. Backfill is a future #303/#278 concern, not this pump's.
- **`send` signature: `@Synchronized` non-suspend vs `suspend` + `Mutex`.** Both preserve the
  encrypt→enqueue ordering invariant. Recommended: non-suspend `@Synchronized` to mirror
  `transport.send`'s exact `Boolean` contract (the consumer reuses an existing call shape); the
  critical section is a microsecond-scale AEAD op. The developer may pick `suspend` + `Mutex` if a
  coroutine-friendly signature reads better at the #278 call site.

## Security review

**Verdict:** PASS

The asset is the encrypted transport (confidentiality + integrity of the application stream) and the
session keys held in RAM. Walked every category adversarially against the spec; no MUST FIX.

**Findings:**

- **[1 Trust boundaries]** No MUST FIX — the network→process boundary is **explicit and single**: an
  inbound `noise_msg`'s bytes become trusted only via `session.decrypt` (AEAD authentication;
  `NoiseIkSession.kt:243-259` throws `NoiseSessionException("frame authentication failed")` on a
  `BadPaddingException`, and rejects sub-MAC-length input). The spec mandates emitting on `inbound`
  **only after** a successful `decrypt` + `Envelope` parse, so a forged/tampered/cross-session-replayed
  frame can never reach a consumer (it tears the session down). Downstream holds an AEAD-authenticated
  `Envelope`, but its `payload: JsonElement` stays **untrusted raw JSON** until a consumer validates it
  — already documented on the type (`MobileWireModels.kt:34`); the pump correctly does not validate
  payload shape (that's #278). The handshake `noise_resp` boundary is the same: `readResp` authenticates
  against the pinned `rs` before `Open`.

- **[2 Tokens, secrets, credentials]** No MUST FIX — the pump **never touches the `hello` token**: it
  calls `session.writeInit()`, which builds+seals the token internally and drops the reference
  (`NoiseIkSession.kt:117`). The pump holds no key material of its own; the session owns the
  `CipherState`s. The MUST-hold lifecycle invariant — **every** teardown path (`Down` / fatal / `close()`)
  calls `session.close()` to wipe keys — is satisfied by the single idempotent `teardown` and is pinned
  by the AC-5 tests. No token/rotation/revocation logic lives here (that's pairing #294 / re-key #303/#304).

- **[3 File / storage]** N/A — the pump performs **no** file or storage I/O. Key custody (Keystore
  wrap) is the factory's dependencies (#291/#294); the pump persists nothing, so there is no
  path-traversal, TOCTOU, at-rest-encryption, atomic-write, or `allowBackup` surface to consider.

- **[4 Inter-process / Android attack surface]** N/A — pure `data/`-layer orchestration over portable
  interfaces; **zero `android.*`**. No `Activity`/`Service`/`BroadcastReceiver`/`ContentProvider`,
  no `<intent-filter>`, no deep link, no `PendingIntent`, no `WebView`. Nothing exported.

- **[5 Cryptographic primitives]** No MUST FIX — the pump **hand-rolls no crypto**: all AEAD/handshake
  is delegated to `NoiseIkSession` (#303, vendored `noise-java`, suite
  `Noise_IK_25519_ChaChaPoly_BLAKE2s`, ADR 0004). The pump uses **no RNG** (jitter belongs to #307's
  supervisor). The one crypto-adjacent obligation it owns is **nonce-order discipline**: the AEAD nonce
  is a per-session monotonic counter the wire does not carry (`protocol-mobile.md` § Transport), so the
  pump must keep wire order == encrypt order. The session's `@Synchronized` covers `encrypt` alone, not
  the gap to `transport.send` (verified `NoiseIkSession.kt:234-240`) — the spec therefore requires the
  pump's own lock to span the encrypt→enqueue pair, and the single sequential inbound collector keeps
  `decrypt` in arrival order. A reorder is an **availability** bug (MAC failure → session death), not a
  confidentiality break; addressed in design. *Code-review must verify the lock spans the full pair.*

- **[6 Network & I/O]** No MUST FIX — the inbound frame-size cap (`MAX_INBOUND_FRAME_CHARS` = 128 KiB)
  is enforced by #306 on `transport.inbound`; the pump **inherits and does not lift** it, so a hostile
  oversized frame is already rejected upstream and base64-decode yields a bounded (~96 KiB) buffer.
  Over-size ciphertext past the Noise 65535-byte per-message limit fails `decrypt` → teardown. OkHttp
  TLS/timeouts/cert-pinning are #306's (`MODERN_TLS`, `readTimeout = 0` for the long-lived WS). The
  pump **adds** the protocol-mandated 10-second `noise_resp` deadline (step 4): because #306's
  `readTimeout` is deliberately 0, a relay that accepts the WS but never answers `noise_init` would
  otherwise hang the session in `Handshaking` forever with no `Down` — `handshakeTimeoutMs` closes that
  silent-hang availability hole. Certificate pinning is OUT OF SCOPE (#306 / future).

- **[7 Error messages, logs, telemetry]** No MUST FIX — the pump emits **no logs** and no telemetry
  (aligned with #307's no-logs posture and #303's no-secrets rule). Failures surface only via
  `PumpState.Closed(cause)`, where `cause` is a `NoiseSessionException` whose message is **category-only**
  by #303's contract (no key material / plaintext / token / raw frame bytes). The spec constrains any
  new message the pump mints to stay category-only. *Code-review must verify no `frame.data` / decrypted
  plaintext / token is interpolated into a thrown message, and that a downstream caller that later logs
  `cause` inherits the category-only guarantee.*

- **[8 Concurrency]** No MUST FIX — every coroutine is owned by the pump's connection-scoped
  `CoroutineScope(SupervisorJob() + dispatcher)`, cancelled by `teardown` on `Down`/fatal/`close()`; no
  application-scope or `GlobalScope` leak, and the AC-5 "no leaked coroutine" test pins it. The
  state-race (`send` reads `Open`, then `teardown` closes the session before `encrypt`) is handled:
  `encrypt` throws `IllegalStateException` → `send` returns `false`, no nonce-reuse, no crash (pinned by
  the send-after-Closed test). `teardown` is CAS-idempotent and does its synchronous cleanup
  (state → channel close → `session.close()` → `transport.close()`) **before** `scope.cancel()`, so
  cancellation cannot skip key-wipe. `inbound` is intentionally a hot single-consumer channel-flow (not
  an accidentally-shared hot flow across screens). Shutdown mid-send leaves no persistent partial state.
  *Code-review must verify `teardown`'s key-wipe is not behind a cancellable suspension.*

- **[9 Threat model alignment]** Addressed against `protocol-mobile.md` § Security model: **#3 relay
  MITM** (severity high→low, cryptographic) — a MITM/rotated-`rs` `noise_resp` MAC-fails in `readResp`
  → teardown (AC 4); **#6 replay** (low→very-low, AEAD nonce) — per-session keys + the single in-order
  collector preserve counter discipline, cross-session replay fails on fresh keys; **#5 implementation
  bugs** (defense-in-depth) — the ordering lock + single collector + fail-closed teardown are the
  defense. Mobile-specific threats (screenshot/accessibility/overlay/keyboard-logging) are N/A — no UI.
  OUT OF SCOPE, named: re-key (#304), typed wire↔domain mapping (#278), cert-pinning (#306/future), and
  a `4421`-carrying protocol-violation close (transport-surface extension; the pump closes normally and
  the supervisor reconnects).

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-05-31

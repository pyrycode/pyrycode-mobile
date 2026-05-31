# Noise session pump — drive the IK handshake + run the open-state `noise_msg` loop

The **Phase 4 encrypted-session layer**: the component that sits **on top of** the single-connection
[relay WS transport](relay-ws-transport.md) and the byte-array [`Noise_IK` session](noise-ik-session.md)
and binds them into **one** running encrypted session. It drives the `Noise_IK` handshake to an open
state, then runs the single open-state `noise_msg` decrypt/dispatch loop, so that encrypted-session
consumers (the remote conversation repository #278, the re-key triggers #304) attach **here** instead of
each re-implementing the handshake and the decrypt loop. It is the **mobile mirror of the Go binary's
`V2SessionManager`** and executes `protocol-mobile.md` § Connection lifecycle → Phone **steps 3–6**.

Package: `de.pyryco.mobile.data.network` (`NoiseSessionPump` + `PumpState`), co-located with the
[transport](relay-ws-transport.md) it consumes and the [session](noise-ik-session.md) it drives. Landed
in [#309](../codebase/309.md) (foundational gap surfaced during #304's architect review), on the OkHttp
engine ([ADR 0005](../decisions/0005-okhttp-websocket-engine.md)) + vendored noise-java
([ADR 0004](../decisions/0004-vendor-noise-java-crypto.md)). Portable, `android.*`-free.

> **Ships dormant.** The pump lands with **no Koin binding** and **no consumers** — it is a
> **per-connection** object, not a singleton. A layer-up coordinator ([#302](../codebase/302.md)) is to
> observe [`RelayConnectionSupervisor.currentConnection`](relay-reconnect-supervisor.md) and, on a
> non-null transport, construct `NoiseSessionPump(transport, koin.get<NoiseSessionFactory>()).also {
> it.start() }`. That wiring is **#302's job, not #309's** (and is not yet done). The pump receives a
> transport that is **already `Up`**; it never calls `transport.connect()`.

## Where it sits in the Phase 4 stack

```
RemoteConversationRepository (#278)   ◀── inbound: Flow<Envelope> / send(Envelope) / state
        ▲
NoiseSessionPump (#309) ─ handshake drive + open-state noise_msg loop   ◀── this doc
        │  collects inbound (decrypt) ; sends noise_msg (encrypt)
        ▼
RelayTransport (#306) ─ one single-use socket, InnerFrameV2 ⇄ JSON text
        │
OkHttp WS (ADR 0005) ─ TLS / TCP
```

The pump and the [reconnect supervisor](relay-reconnect-supervisor.md) ([#307](../codebase/307.md)) are
**code-independent siblings** over the *same* live connection: the supervisor owns the **socket
lifecycle** (dial / reconnect / backoff) and collects #306's **`events`**; the pump owns the **Noise
session** and collects #306's **`inbound`**. Two different single-consumer streams → no contention, **no
blocker between them**. A fresh pump is built per fresh connection (the supervisor's `currentConnection`
is the per-connection seam).

## Exported types

```kotlin
class NoiseSessionPump(
    transport: RelayTransport,                              // already Up — never dials
    sessionFactory: NoiseSessionFactory,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,  // crypto is CPU-bound; create() switches to IO itself
    handshakeTimeoutMs: Long = 10_000,                      // protocol step 4: the noise_resp deadline
) {
    val state: StateFlow<PumpState>          // handshake-completion + lifecycle signal
    val inbound: Flow<Envelope>              // hot, single-consumer, decrypted app frames
    fun start()                              // single-use; launches the one session-drive coroutine
    fun send(envelope: Envelope): Boolean    // encrypt+frame+send; false unless Open (or racing teardown)
    fun close()                              // idempotent teardown; wipes session keys
}

sealed interface PumpState {
    data object Handshaking : PumpState                  // initial; noise_init sent, awaiting noise_resp
    data class Open(val connId: String) : PumpState      // the handshake-completion signal
    data class Closed(val cause: Throwable?) : PumpState // terminal; cause == null ⟺ clean Down/close()
}
```

`state` is the **handshake-completion signal**: a consumer observes for `Open`
(`state.filterIsInstance<PumpState.Open>().first()`) before it begins sending; `Closed` is terminal.

## The handshake drive — `protocol-mobile.md` Phone steps 3–6

`start()` is single-use (a second call throws `IllegalStateException`) and launches **one** coroutine in
the pump scope that:

1. `session = sessionFactory.create()` — suspends on the factory's IO dispatcher; resolves the device `s`
   + pinned server `rs` + token into a ready-to-handshake session. A `NoiseSessionException` (not paired /
   key unavailable / suite missing) → teardown.
2. Send `noise_init` — `transport.send(InnerFrameV2("noise_init", base64StdEncode(session.writeInit())))`.
   The encrypted `hello` + token ride **sealed inside** `writeInit()` (never a plaintext header).
3. Await the first inbound frame, **bounded by `handshakeTimeoutMs`** —
   `withTimeoutOrNull(handshakeTimeoutMs) { transport.inbound.first() }`. A timeout (`null`) or an early
   `Down` (`inbound` completes empty → `NoSuchElementException`) → teardown.
4. Require `type == "noise_resp"` → `connId = session.readResp(base64StdDecode(data))` →
   `state = Open(connId)`. A wrong type, a bad base64, or `readResp` throwing `NoiseSessionException`
   (MAC failure / malformed `hello_ack`) → teardown.
5. Run the open-state loop over the **remaining** frames: `transport.inbound.collect { onOpenFrame(it) }`.

> **One sequential collector for the connection's lifetime.** Steps 3 and 5 are the *same* logical
> consumer of #306's single-consumer `inbound`. `RelayTransport.inbound` is `receiveAsFlow()`-backed, so a
> `.first()` followed by a `.collect()` **distributes** (never duplicates) elements — the channel buffers
> anything arriving in the gap, so no frame is lost. **Do not split this across two coroutines** — that
> would fight #306's single-consumer contract. (Go built the equivalent as one `V2SessionManager`.)

## The open-state dispatch — the `when (frame.type)` seam

`onOpenFrame(frame)` is a clean dispatch on the outer frame type:

- **`"noise_msg"`** → `plaintext = session.decrypt(base64StdDecode(data))` →
  `envelope = MobileJson.decodeFromString<Envelope>(plaintext)` → emit on `inbound`. **Emitted only after
  a successful decrypt + parse** — a forged / tampered / cross-session-replayed frame fails AEAD in
  `decrypt` and can never reach a consumer.
- **`else`** → teardown. The ordered encrypted stream **cannot skip a frame**, so an unknown type — or a
  base64 / `decrypt` / `Envelope`-parse failure on a `noise_msg` — tears the session down rather than
  dropping the frame. **This `else` is the #304 re-key seam**: the re-key triggers ticket inserts a
  `"noise_resp"` branch here to route a re-key response. Re-key is **not** implemented in this layer.

## Outbound `send` — ordering is the load-bearing invariant

`send(envelope): Boolean` mirrors `transport.send`'s non-throwing `Boolean` contract:

- Returns `false` (no frame) if not `Open`, or if a racing teardown closed the session (`encrypt` throws
  `IllegalStateException` → caught → `false`, never a throw, no nonce consumed).
- Otherwise, **under `synchronized(outboundLock)`**: `encrypt(MobileJson(envelope))` → `base64Encode` →
  `transport.send(InnerFrameV2("noise_msg", …))`.

The encrypt→enqueue pair **must** be one critical section. The AEAD nonce is a per-session monotonic
counter the wire does **not** carry (`protocol-mobile.md` § Transport); the receiver derives it from its
own counter + ordered WS delivery. If two concurrent sends encrypt as nonce *N*, *N+1* but enqueue *N+1*
before *N*, the receiver MAC-fails the reordered frame and the session dies. The session's own
`@Synchronized` covers `encrypt` alone, **not** the gap to `transport.send` — so the pump's lock is what
makes wire order == nonce order. Inbound order is guaranteed structurally by the single sequential
collector (one `decrypt` at a time, in arrival order).

> **Consumer awareness note (for #278's `send` call site).** A `false` return on an **`Open`** session
> means the session is effectively **spent**, not "retry the same envelope": the nonce was consumed in
> `encrypt` *before* `transport.send`, so a live-but-backpressured socket dropping the frame (OkHttp's
> `WebSocket.send` returns `false` on a full 16 MiB buffer, not only when closed) leaves a nonce gap the
> server will MAC-fail. This self-heals — the session dies, the supervisor reconnects with a fresh
> handshake — but the consumer must **not** re-send the same envelope on the same session.

## State & concurrency model

- **One connection-scoped scope** (`CoroutineScope(SupervisorJob() + dispatcher)`, `Dispatchers.Default`
  in production). **One** coroutine launched (the session drive). No `GlobalScope`, no application scope —
  cancelled by `teardown`, so nothing outlives the connection.
- **Single state source** — one `MutableStateFlow<PumpState>(Handshaking)`; only the drive coroutine
  writes `Open`, only `teardown` writes `Closed`, `send` reads it.
- **`inbound`** — `Channel<Envelope>(BUFFERED)` exposed as `receiveAsFlow()`: hot (the collector runs
  independently of any subscriber), **single-consumer**, lossless + in-order (the collector `send`s
  suspending, so a slow/absent consumer backpressures all the way down to the transport and TCP
  flow-control). Closed by `teardown`, completing the consumer's flow.
- **Ordering lock** — `send` holds `synchronized(outboundLock)` across the encrypt→enqueue pair (above).
- **Teardown — one idempotent path for every trigger.** `teardown(cause)` is CAS-guarded
  (`terminated.compareAndSet`) and is a **non-suspend `fun`**: `state = Closed(cause)` →
  `inboundChannel.close()` → `session?.close()` (**wipes keys**) → `transport.close()` (idempotent; active
  on a fatal-frame path so the supervisor sees `Down` and reconnects) → `scope.cancel()` **last**. The
  key-wipe is never behind a cancellable suspension. Callable from inside the collector (fatal frame) and
  from outside (`close()`); idempotency makes both safe.
- **`Down` detection** = `transport.inbound` **completing** (#306 guarantees this on the terminal `Down`).
  The drive then calls `teardown(null)`. The pump **never reads `events`** (owned by #307) — keeping both
  single-consumer contracts clean.
- **Not resumable.** `start()` once; after `Closed` the instance is spent. A fresh connection builds a
  fresh pump (Noise ephemerals are per-handshake). The pump persists nothing — process death mid-send
  leaves no partial on-disk state.

## Security posture

The spec's § Security review verdict is **PASS** (no MUST FIX). The pump hand-rolls no crypto (all
AEAD/handshake delegated to #303) and uses no RNG. Its crypto-adjacent obligations, confirmed by code
review against the diff:

- **The network→process trust boundary is explicit and single** — an inbound frame's bytes become trusted
  only via `session.decrypt` (AEAD authentication); the pump emits on `inbound` **only after** a
  successful decrypt + `Envelope` parse, so a forged/tampered/replayed frame tears the session down rather
  than reaching a consumer. The `Envelope.payload` stays **untrusted raw JSON** until a consumer validates
  it (that's #278) — the pump correctly does not validate payload shape.
- **Never touches the `hello` token** — `writeInit()` builds + seals it internally; the pump holds no key
  material of its own (the session owns the CipherStates), and **every** teardown path wipes them.
- **Ordering discipline** — the `outboundLock` spans the full encrypt→enqueue pair (a reorder is an
  availability bug → session death → reconnect, not a confidentiality break).
- **No logs, category-only causes** — every failure surfaces only via `Closed(cause)`, whose message is
  category-only (no `frame.data` / plaintext / token interpolated). A downstream caller that logs `cause`
  inherits the guarantee.
- **The 10 s `noise_resp` deadline closes a silent-hang hole** — #306's `readTimeout` is deliberately 0
  (a long-lived WS footgun guard), so a relay that accepts the WS but never answers `noise_init` would
  otherwise hang the session in `Handshaking` forever with no `Down`. `handshakeTimeoutMs` bounds it.
- **Inherited, not re-implemented**: the inbound frame-size cap (`MAX_INBOUND_FRAME_CHARS = 128 KiB`) is
  #306's; TLS/timeouts/cert-pinning are #306's. The `v != 2` / `4421` close-code validation is **deferred**
  (the transport's `close()` is parameterless; the pump closes normally and the supervisor reconnects).

## Edge cases & limitations

- **Handshake failure** (timeout, wrong first frame, `noise_resp` MAC failure, factory failure) → `Closed`
  + `transport.close()`; the supervisor reconnects with a fresh handshake.
- **Any undecryptable / unparseable / unknown open-state frame** → `Closed` (the stream can't skip a
  frame). Not a silent drop.
- **`send` before `Open` / after `Closed` / racing teardown** → `false`, no throw, no frame.
- **Re-key, typed wire↔domain mapping, a `4421`-carrying protocol-violation close** — all out of scope
  (#304 / #278 / a future transport-surface extension), named in the spec's § Open questions.
- **`last_seen_ts` backfill** — `writeInit` is called as-is (#303's `HelloClientPayload` carries no
  `last_seen_ts`); backfill is a future #303/#278 concern.

## Testing

JVM-only (`app/src/test/.../NoiseSessionPumpTest.kt`, `./gradlew test`), `runTest` virtual clock with a
`StandardTestDispatcher` injected into **both** the pump and the factory's `ioDispatcher` — same posture
as [`NoiseIkSessionTest`](noise-ik-session.md) / [`RelayConnectionSupervisorTest`](relay-reconnect-supervisor.md).
Test doubles live in-file: a `Channel`-backed `FakeRelayTransport` (`pushInbound` / `completeInbound` /
captured `sentFrames`; **`connect()` `error()`s** — the pump must never dial), a real IK `TestResponder`
(copied from `NoiseIkSessionTest`) so the session is exercised against a real peer, and a
`NoiseSessionFactory` over fake stores pinned to the responder's static key. 14 `@Test` cover AC 1–5:
handshake→`Open` (with the encrypted `hello` asserted), timeout, MAC failure, wrong/unknown frame type,
inbound decrypt→`Envelope`, fail-closed teardown on every bad frame, outbound round-trip, `Down` /
idempotent-`close()` lifecycle + no-leak, `start()`-twice throws.

> **Test-harness note (reusable):** a "no leaked coroutine" assertion that collects `inbound` and checks
> `job.isCompleted` must launch the collector as a **foreground child of the test scope** (not
> `backgroundScope` — those are deliberately *not* drained by `advanceUntilIdle()`), so `advanceUntilIdle()`
> drains it *and* `runTest`'s structured concurrency enforces completion. See
> [`codebase/309.md`](../codebase/309.md) § Lessons learned.

## Related

- Ticket notes: [`../codebase/309.md`](../codebase/309.md) — files/line refs, patterns, lessons,
  verification.
- Spec: `docs/specs/architecture/309-noise-session-pump.md` (§ Design, § State + concurrency model,
  § Error handling, § Security review — Verdict PASS).
- Sits on: [Relay WebSocket transport](relay-ws-transport.md) ([#306](../codebase/306.md)) — collects
  `inbound`, sends `InnerFrameV2`, keys off `inbound` completion for `Down`; never `events`.
  [Noise_IK session](noise-ik-session.md) ([#303](../codebase/303.md)/[#298](../codebase/298.md)) via
  `NoiseSessionFactory.create()` — `writeInit`/`readResp`/`encrypt`/`decrypt`/`close`. Frames/codec from
  the [Mobile Protocol v2 wire layer](mobile-protocol-v2-wire-layer.md) ([#273](../codebase/273.md)) —
  `Envelope`, `InnerFrameV2`, `MobileJson`, `base64StdEncode`/`base64StdDecode`.
- Siblings / consumers: [reconnect supervisor](relay-reconnect-supervisor.md) ([#307](../codebase/307.md))
  — the same connection's `events`; publishes `currentConnection` (the per-connection seam); **no
  blocker**. **#302** (lifecycle coordinator — builds a pump per connection; the **unwired** seam).
  **#278** (`RemoteConversationRepository` — the one external `inbound`/`send` consumer; see the send
  awareness note). **#304** (re-key triggers — extends the open-state `else` seam).
- Go-side mirror (`pyrycode`): [`V2SessionManager`](https://github.com/pyrycode/pyrycode/issues/446)
  (open-state dispatch + tampered-frame teardown) on the
  [IK wrapper #433](https://github.com/pyrycode/pyrycode/issues/433).
- Decisions: [ADR 0004 — vendor noise-java](../decisions/0004-vendor-noise-java-crypto.md),
  [ADR 0005 — OkHttp WebSocket engine](../decisions/0005-okhttp-websocket-engine.md). Aligns with
  pyrycode-side ADR 024 (Noise_IK for mobile E2E; relay untrusted + content-blind).
</content>

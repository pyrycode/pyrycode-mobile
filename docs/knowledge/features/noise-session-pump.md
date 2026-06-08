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
([ADR 0004](../decisions/0004-vendor-noise-java-crypto.md)). Portable, `android.*`-free. The **re-key
triggers** — a 1-hour timer + an inbound `rekey_request` dispatch, driving #303's in-place re-key —
landed on top of this pump in [#304](../codebase/304.md) (see § Re-key triggers).

> **Lands per-connection, not a singleton.** #309 shipped the pump with **no Koin binding** and **no
> consumers** — it is a **per-connection** object. The
> [`RelayRepositoryCoordinator`](relay-repository-coordinator.md) ([#351](../codebase/351.md), **landed**)
> now observes [`RelayConnectionSupervisor.currentConnection`](relay-reconnect-supervisor.md) and, on a
> non-null transport, constructs `NoiseSessionPump(transport, koin.get<NoiseSessionFactory>()).also {
> it.start() }` per connection, tearing it down (`close()`, wiping keys) when the connection drops. To
> let the coordinator own that lifecycle without the repository seeing it, #351 made the pump declare
> **`: ManagedSessionPump`** (the `start()`/`close()` lifecycle view layered over `SessionPump`'s
> `inbound`/`send` data view) — purely additive, no behaviour change. The pump receives a transport that
> is **already `Up`**; it never calls `transport.connect()`.

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
    rekeyIntervalMs: Long = 3_600_000,                      // #304: the 1-hour re-key cadence; injected small in tests
) : ManagedSessionPump {                     // #351: inbound/send (SessionPump) + start/close (the lifecycle view)
    val state: StateFlow<PumpState>          // handshake-completion + lifecycle signal (pump-specific, not in the contract)
    val inbound: Flow<Envelope>              // hot, single-consumer, decrypted app frames
    fun start()                              // single-use; launches the one session-drive coroutine
    fun send(envelope: Envelope): Boolean    // encrypt+frame+send; false unless Open (or racing teardown)
    fun close()                              // idempotent teardown; wipes session keys
}

sealed interface PumpState {
    data object Handshaking : PumpState                  // initial; noise_init sent, awaiting noise_resp
    data class Open(                                     // the handshake-completion signal
        val connId: String,
        val capabilities: Set<String> = emptySet(),     // #401: negotiated set from hello_ack (defaulted → existing sites green)
    ) : PumpState
    data class Closed(val cause: Throwable?) : PumpState // terminal; cause == null ⟺ clean Down/close()
}
```

`state` is the **handshake-completion signal**: a consumer observes for `Open`
(`state.filterIsInstance<PumpState.Open>().first()`) before it begins sending; `Closed` is terminal.
`Open.capabilities` ([#401](../codebase/401.md)) carries the negotiated capability set the session
surfaced from `hello_ack` (the daemon's intersection of advertised ∩ supported), so a consumer of the
already-exposed `state` flow reads grant with `CAPABILITY_INTERACTIVE in open.capabilities` **without
re-parsing wire bytes**. It is a **defaulted** field — the four existing `Open(connId)`
construction/assertion sites (3 in `NoiseSessionPumpTest`, 1 in `RelayRepositoryCoordinatorTest`) stay
green untouched, and `RelayRepositoryCoordinator.toPyrycodeLinkStatus()`'s `is PumpState.Open` match is
unaffected (it doesn't destructure). **Surfacing-only** — the pump gates nothing on the set; the decode
gate (#385) and stall gate (#395) consume it.

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
   `state = Open(connId, session.negotiatedCapabilities)` (the negotiated set surfaced from `hello_ack`,
   [#401](../codebase/401.md)). A wrong type, a bad base64, or `readResp` throwing `NoiseSessionException`
   (MAC failure / malformed `hello_ack`, including a non-array `capabilities`) → teardown.
5. Run the open-state loop over the **remaining** frames: `transport.inbound.collect { onOpenFrame(it) }`.

> **One sequential collector for the connection's lifetime.** Steps 3 and 5 are the *same* logical
> consumer of #306's single-consumer `inbound`. `RelayTransport.inbound` is `receiveAsFlow()`-backed, so a
> `.first()` followed by a `.collect()` **distributes** (never duplicates) elements — the channel buffers
> anything arriving in the gap, so no frame is lost. **Do not split this across two coroutines** — that
> would fight #306's single-consumer contract. (Go built the equivalent as one `V2SessionManager`.)

## The open-state dispatch — the `when (frame.type)` seam

`onOpenFrame(frame)` is a clean dispatch on the outer frame type, with three branches as of
[#304](../codebase/304.md):

- **`"noise_msg"`** → `plaintext = session.decrypt(base64StdDecode(data))` →
  `envelope = MobileJson.decodeFromString<Envelope>(plaintext)`. **Then discriminate on `envelope.type`**
  (#304): a **`"rekey_request"`** control envelope → `scope.launch { initiateRekey() }` (the server
  nudging a re-key) and is **not** forwarded; anything else → emit on `inbound`. The domain envelope is
  surfaced only after a successful decrypt + parse — a forged / tampered / cross-session-replayed frame
  fails AEAD in `decrypt` and can never reach a consumer (see § Re-key triggers for why `rekey_request`
  is intercepted at the producer).
- **`"noise_resp"`** (#304) → the re-key handshake reply. A raw handshake frame (not a `noise_msg`), so it
  arrives here rather than as decrypted app traffic. If `rekeyInFlight`, route the bytes to
  `session.readRekeyResp(base64StdDecode(data))` (atomic CipherState swap), clear the flag, and re-base
  the timer. A `noise_resp` with **no re-key in flight** is a protocol violation → teardown; a
  `readRekeyResp` MAC failure (rotated `rs` / MITM) → teardown (see § Re-key triggers).
- **`else`** → teardown. The ordered encrypted stream **cannot skip a frame**, so a genuinely unknown type
  — or a base64 / `decrypt` / `Envelope`-parse failure on a `noise_msg` — tears the session down rather
  than dropping the frame.

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

## Re-key triggers — the 1-hour timer + inbound `rekey_request` ([#304](../codebase/304.md))

The pump drives [#303](../codebase/303.md)'s in-place re-key mechanism from **two** entry points, both of
which make the phone send a fresh `noise_init` (IK requires the initiator — the phone — to start; the
server can only *nudge*). It **invokes** `writeRekeyInit`/`readRekeyResp`; it re-implements neither. The
Go-side mirror is `pyrycode` #450 (initiator timer + emit) / #454 (inbound discriminator).

- **Trigger 1 — the session-scoped 1-hour timer.** A one-shot `delay(rekeyIntervalMs)` launched by
  `rebaseRekeyTimer()`, armed when the pump reaches `Open` and **re-based by each completed re-handshake**
  (the two call sites — initial arm + post-`readRekeyResp` re-base — are both in the single drive
  coroutine, so `rekeyTimerJob` has no race). `rekeyIntervalMs` defaults to `3_600_000` (1 h,
  `protocol-mobile.md` § Re-key); tests inject a small value. ("Reset on reconnect" then follows for free
  once [#307](../codebase/307.md) drives the loop — each reconnect builds a fresh pump whose `Open`
  re-bases the cadence.)
- **Trigger 2 — the inbound `rekey_request`.** A `{ "type": "rekey_request", "payload": { "reason": … } }`
  envelope, AEAD-sealed inside a `noise_msg`, so it arrives **decrypted** in `onOpenFrame`'s `noise_msg`
  branch and is intercepted there (above). It is a **control** message — **not** forwarded to the
  single-consumer `inbound` (so #278 never sees transport control), launched off the collector so the
  keystore re-load can't stall it. **The payload is intentionally not decoded**: discriminating on `type`
  alone makes an unknown / absent / extra `reason` impossible to crash on (the forward-compat AC, satisfied
  by construction).

**Both funnel into one `initiateRekey()`**, serialised by a `rekeyMutex` and coalescing on a `@Volatile
rekeyInFlight`:

1. Skip if not `Open`. Under the mutex, **coalesce first** (`rekeyInFlight || !Open` → return) — the
   second of two racing triggers skips (the mobile analog of Go #450's `skipped_already_awaiting`), and a
   flood of nudges never even reaches the next step.
2. Re-load the device static `s` via `sessionFactory.reloadDeviceStaticKey()` (a `NoiseSessionException`
   — not paired / key unavailable — skips the re-key; transport unaffected).
3. `writeRekeyInit(s)` → set `rekeyInFlight = true` (**before** the send: the `noise_resp` can only arrive
   after the server reads the `noise_init`) → `transport.send` the `noise_init`. A `finally { s.fill(0) }`
   zeros the device-static copy with **no suspension point** between the re-load and the wipe, so
   cancellation/teardown can't skip it; `session.close()` independently wipes the session's copy in
   `pendingRekey`. The re-key `noise_init` is sent **outside `outboundLock`** — it carries no
   transport-AEAD payload, so it is outside the wire-order==nonce-order invariant the lock protects, and
   ordinary `noise_msg` traffic may interleave on the **current** keys until the swap.

**Completion** routes through the `noise_resp` branch (above): `readRekeyResp` swaps in the new keys
atomically (#303's `@Synchronized` — no mixed-key / nonce-reuse window), clears `rekeyInFlight`, and
re-bases the timer. **No `rekey_ack` is sent** — the next successful AEAD round-trip on the new keys is the
implicit ack. A MAC failure (the responder is not the pinned `rs` → rotated key or relay MITM) throws →
teardown → #307 reconnects with a fresh full handshake; #303's **fail-RETAIN** keeps the old keys valid
right up to `session.close()`, so **no half-swapped state is observable**.

> **Why no phone-side re-key reply timeout** (unlike Go #450's 30 s window). The phone is the *initiator*;
> after it sends a re-key `noise_init`, a missing `noise_resp` cannot strand the session on a reliable
> ordered WS/TCP transport — either the server answers (swap completes), or the connection closes
> (`inbound` completes → teardown → reconnect), or a lost/late swap desyncs the keys and the **next** AEAD
> frame in either direction MAC-fails → teardown. All paths converge on teardown→reconnect without an
> explicit timer; one is deferred until a stuck re-key is actually observed (evidence-based).

`NoiseSessionFactory.reloadDeviceStaticKey()` (added by #304) is the re-supply seam #303's design (B)
named: it re-loads `s` fresh per re-key (mirroring `create()`'s load + zero discipline) rather than
caching it on the multi-hour pump — preserving #298/#303's bounded device-key RAM window across every
hourly rotation.

## State & concurrency model

- **One connection-scoped scope** (`CoroutineScope(SupervisorJob() + dispatcher)`, `Dispatchers.Default`
  in production). The session-drive coroutine is the long-lived one; #304's re-key adds children — the
  one-shot timer `delay` and each launched `initiateRekey` — **all** children of this same scope. No
  `GlobalScope`, no application scope — `teardown`'s `scope.cancel()` cancels the timer and any in-flight
  re-key, so nothing outlives the connection.
- **Re-key concurrency (#304) — one new lock, no ordering hazard.** `rekeyMutex` serialises the two
  initiation paths (timer vs `rekey_request`) so `writeRekeyInit`'s `check(pendingRekey == null)` can't
  trip from a double-initiate; it **does not nest** with `outboundLock` or the session monitor (the re-key
  `noise_init` send is outside `outboundLock`), so no lock-ordering cycle exists. `rekeyInFlight` is
  `@Volatile`, set `true` only under the mutex and before the `noise_init` send, cleared only in the drive
  coroutine on a completed `readRekeyResp`; the completion read in the single sequential collector is safe
  by wire causality (the resp can't arrive before the server reads the init) + `@Volatile`. The CipherState
  swap's atomicity is **#303's `@Synchronized`**, not re-implemented here. `rekeyTimerJob` is touched only
  by the drive coroutine → single-threaded, no race.
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
- **Never touches the `hello` token** — `writeInit()` builds + seals it internally; the session owns the
  CipherStates and **every** teardown path wipes them. The pump's only transient key material is the
  device static `s` re-loaded **per re-key** (#304) and zeroed in a `finally` with no preceding suspension
  (the session's copy in `pendingRekey` is wiped independently by `session.close()`) — preserving the
  bounded device-key RAM window across every hourly rotation; the coalesce check precedes the re-load, so
  a `rekey_request` flood never even loads `s`.
- **Re-key trust crossing is authenticated** — the re-key `noise_resp` bytes (`onOpenFrame` →
  `readRekeyResp`) come from a possibly-hostile relay but are authenticated by the pinned-`rs` IK
  handshake; a forged/rotated `noise_resp` MAC-fails → teardown (no swap to attacker keys). The inbound
  `rekey_request` arrives **already AEAD-authenticated** (only the trusted peer can nudge a re-key) and the
  action it nudges (re-key) is benign.
- **Ordering discipline** — the `outboundLock` spans the full encrypt→enqueue pair (a reorder is an
  availability bug → session death → reconnect, not a confidentiality break); the re-key `noise_init` is
  **outside** the lock (it carries no transport-AEAD payload, so it is outside the nonce-order invariant).
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
- **Re-key failure** (rotated `rs` / MITM `noise_resp`, or a stray `noise_resp` with no re-key in flight)
  → `Closed`; the supervisor reconnects with a fresh handshake (#304, see § Re-key triggers). A device-key
  re-load failure (not paired / key unavailable) instead **skips** the re-key silently — transport stays
  live on the current keys.
- **Typed wire↔domain mapping, a `4421`-carrying protocol-violation close, a phone-side re-key reply
  timeout** — all out of scope (#278 / a future transport-surface extension / deferred-until-observed),
  named in the spec's § Open questions.
- **`last_seen_ts` backfill** — `writeInit` is called as-is (#303's `HelloClientPayload` carries no
  `last_seen_ts`); backfill is a future #303/#278 concern.

## Testing

JVM-only (`app/src/test/.../NoiseSessionPumpTest.kt`, `./gradlew test`), `runTest` virtual clock with a
`StandardTestDispatcher` injected into **both** the pump and the factory's `ioDispatcher` — same posture
as [`NoiseIkSessionTest`](noise-ik-session.md) / [`RelayConnectionSupervisorTest`](relay-reconnect-supervisor.md).
Test doubles live in-file: a `Channel`-backed `FakeRelayTransport` (`pushInbound` / `completeInbound` /
captured `sentFrames`; **`connect()` `error()`s** — the pump must never dial), a real IK `TestResponder`
(copied from `NoiseIkSessionTest`) so the session is exercised against a real peer, and a
`NoiseSessionFactory` over fake stores pinned to the responder's static key. **26 `@Test`** — 14 from
#309 (handshake→`Open` with the encrypted `hello` asserted, timeout, MAC failure, wrong/unknown frame
type, inbound decrypt→`Envelope`, fail-closed teardown on every bad frame, outbound round-trip, `Down` /
idempotent-`close()` lifecycle + no-leak, `start()`-twice throws), **1 from [#401](../codebase/401.md)**
(a `hello_ack` echoing `["interactive"]` surfaces `setOf("interactive")` on `PumpState.Open` — asserted
via the `noiseResp`/`ackEnvelope` helpers, which gained a `capabilities` parameter), plus **11 re-key
scenarios from [#304](../codebase/304.md)**: timer fires only after the interval, re-key completes + traffic continues on
new keys, timer re-based by a completed re-key, completion sends no ack + stays `Open`, inbound
`rekey_request` triggers re-key + not forwarded, forward-compat `reason` ×3, rotated-`rs`/MITM teardown
without half-swap, stray `noise_resp` teardown, close-mid-re-key no-leak. The `TestResponder` gained a
**re-key leg** (`rekey(init)` re-using the responder's own static so the pinned-`rs` continuity holds);
`rekeyIntervalMs` is injected small so the virtual clock drives the timer. **Two re-key test gotchas** —
the `FakeDeviceStaticKeyStore` must hand out a **fresh copy per call** (the per-re-key re-load would
otherwise get the scalar `create()` already zeroed), and `runCurrent()` (not `advanceUntilIdle()`) settles
a `noise_resp` when asserting "no extra frame" (an over-advance re-fires the re-based timer); see
[`codebase/304.md`](../codebase/304.md) § Lessons learned.

> **Test-harness note (reusable):** a "no leaked coroutine" assertion that collects `inbound` and checks
> `job.isCompleted` must launch the collector as a **foreground child of the test scope** (not
> `backgroundScope` — those are deliberately *not* drained by `advanceUntilIdle()`), so `advanceUntilIdle()`
> drains it *and* `runTest`'s structured concurrency enforces completion. See
> [`codebase/309.md`](../codebase/309.md) § Lessons learned.

## Related

- Ticket notes: [`../codebase/401.md`](../codebase/401.md) (the `PumpState.Open.capabilities` surfacing) +
  [`../codebase/309.md`](../codebase/309.md) (the pump itself) +
  [`../codebase/304.md`](../codebase/304.md) (the re-key triggers built on it) — files/line refs,
  patterns, lessons, verification.
- Specs: `docs/specs/architecture/309-noise-session-pump.md` + `docs/specs/architecture/304-noise-ik-rekey-triggers.md`
  (§ Design, § State + concurrency model, § Error handling, § Security review — both Verdict PASS).
- Sits on: [Relay WebSocket transport](relay-ws-transport.md) ([#306](../codebase/306.md)) — collects
  `inbound`, sends `InnerFrameV2`, keys off `inbound` completion for `Down`; never `events`.
  [Noise_IK session](noise-ik-session.md) ([#303](../codebase/303.md)/[#298](../codebase/298.md)) via
  `NoiseSessionFactory.create()` — `writeInit`/`readResp`/`encrypt`/`decrypt`/`close`, **plus
  `writeRekeyInit`/`readRekeyResp` + `reloadDeviceStaticKey` for the #304 re-key triggers**. Frames/codec
  from the [Mobile Protocol v2 wire layer](mobile-protocol-v2-wire-layer.md) ([#273](../codebase/273.md))
  — `Envelope`, `InnerFrameV2`, `MobileJson`, `base64StdEncode`/`base64StdDecode`.
- Siblings / consumers: [reconnect supervisor](relay-reconnect-supervisor.md) ([#307](../codebase/307.md))
  — the same connection's `events`; publishes `currentConnection` (the per-connection seam); **no
  blocker**. [`RelayRepositoryCoordinator`](relay-repository-coordinator.md) ([#351](../codebase/351.md),
  **landed**) — builds + `start()`s a pump per connection and `close()`s it on drop; owns the
  `: ManagedSessionPump` declaration. **#278** (`RemoteConversationRepository` — the one external
  `inbound`/`send` consumer; see the send
  awareness note; never sees `rekey_request`, intercepted at the producer). **[#304](../codebase/304.md)**
  (re-key triggers — **landed** on this pump: arms a 1-hour timer off `Open`, intercepts `rekey_request`
  in `onOpenFrame`, routes the re-key `noise_resp` via the new `noise_resp` branch).
- Go-side mirror (`pyrycode`): [`V2SessionManager`](https://github.com/pyrycode/pyrycode/issues/446)
  (open-state dispatch + tampered-frame teardown) on the
  [IK wrapper #433](https://github.com/pyrycode/pyrycode/issues/433); the re-key triggers mirror
  [#450](https://github.com/pyrycode/pyrycode/issues/450) (initiator timer + `rekey_request` emit) /
  [#454](https://github.com/pyrycode/pyrycode/issues/454) (inbound discriminator).
- Decisions: [ADR 0004 — vendor noise-java](../decisions/0004-vendor-noise-java-crypto.md),
  [ADR 0005 — OkHttp WebSocket engine](../decisions/0005-okhttp-websocket-engine.md). Aligns with
  pyrycode-side ADR 024 (Noise_IK for mobile E2E; relay untrusted + content-blind).
</content>

# Relay WebSocket transport — `/v1/client` dial + bare `InnerFrameV2` frame I/O

The **single-connection WebSocket leg of the Phase 4 transport**: an OkHttp-backed transport that dials the paired relay's `/v1/client` endpoint and carries bare [`InnerFrameV2`](mobile-protocol-v2-wire-layer.md) JSON **text** frames between the phone and the relay, in both directions. It is the wire the [Noise_IK session](noise-ik-session.md)'s bytes ride on — but it is **transport-only and content-blind**: it (de)serializes `InnerFrameV2` ↔ JSON text via `MobileJson` and **never inspects a frame's `data`** (that is Noise ciphertext, authenticated one layer up). The relay itself is protocol-neutral and content-blind too — it wraps/unwraps a `{conn_id, frame}` routing envelope on its **binary** leg only; the phone sends/receives bare `InnerFrameV2` text frames and never sees the envelope.

Package: `de.pyryco.mobile.data.network` (`app/src/main/java/de/pyryco/mobile/data/network/`), sibling to the [wire codec](mobile-protocol-v2-wire-layer.md) ([#273](../codebase/273.md)) it serializes through and the [Noise session](noise-ik-session.md) ([#298](../codebase/298.md)) whose bytes it carries. Landed in [#306](../codebase/306.md) (split from #301), on the OkHttp engine ([ADR 0005](../decisions/0005-okhttp-websocket-engine.md)). Mirrors the byte-faithful OkHttp idiom proven by the 2026-05-29 Noise client spike.

> **Transport-only, single-use, ships dormant.** No Noise, no reconnect, no `ConnectionStateSource` swap, **no Koin binding** (same dormant-but-tested pattern as [`PairedServerStore`](paired-server-store.md)/`NoiseSessionFactory`). One `connect()` = one socket; the transport surfaces raw `Up`/`Down` events for a **sibling reconnect supervisor** (a separate child split from #301, blocked-by this) to drive a reconnect loop on top. The Noise-bytes → `InnerFrameV2(type, base64(bytes))` wrapping is the future WS pump's job — `send()` takes an already-built frame.

## Where it sits in the Phase 4 stack

```
application msgs (#278) ─ noise_msg envelopes
        │
Noise_IK session (#298/#303) ─ raw bytes in/out, AEAD, conn_id
        │  (future WS pump wraps raw bytes as InnerFrameV2(type, base64(bytes)) / unwraps inbound)
        ▼
RelayTransport (#306) ─ InnerFrameV2 ⇄ JSON text frame, /v1/client dial   ◀── this doc
        │
OkHttp WS (ADR 0005) ─ TLS / TCP
```

This transport is **below** the Noise session in the runtime data flow (it is the wire), yet content-blind: it carries the opaque ciphertext frames the session produces, and end-to-end authenticity/confidentiality come from Noise, **not** from this transport (pyrycode-side ADR 024 / `protocol-mobile.md` § Security model — the relay is untrusted).

## Exported types

```kotlin
/** A single-connection relay WS transport. One connect() = one socket. NOT reconnecting. */
interface RelayTransport {
    val inbound: Flow<InnerFrameV2>          // hot, connection-scoped, single-consumer; completes on Down
    val events: Flow<TransportEvent>          // hot, connection-scoped, single-consumer; completes on Down
    fun connect()                             // open one socket (async); single-use — a 2nd call throws
    fun send(frame: InnerFrameV2): Boolean    // serialize (via MobileJson) + enqueue; false = closed/buffer full
    fun close()                               // tear down + complete the streams; idempotent
}

sealed interface TransportEvent {
    data object Up : TransportEvent
    data class Down(val code: Int?, val reason: String?, val cause: Throwable?) : TransportEvent
}

class OkHttpRelayTransport(
    pairedServer: PairedServer,       // relay URL + serverId + token            (#294)
    clientInfo: NoiseClientInfo,      // deviceName (Build.MODEL) + clientVersion (#298)
    webSocketFactory: WebSocket.Factory,   // the dial seam — a configured OkHttpClient
) : RelayTransport {
    companion object { fun defaultClient(): OkHttpClient }   // the secure shared client (see § Network config)
}
```

The interface is justified **now** (unlike #298's deferred `NoiseSession`): the ticket names three consumers — the sibling supervisor, [#278](https://github.com/pyrycode/pyrycode-mobile/issues/278), [#302](https://github.com/pyrycode/pyrycode-mobile/issues/302) — that build on exactly this surface and will fake it in their tests. `TransportEvent.Down` carries the **WS close code + reason** (not just the cause) so the supervisor and [#308](https://github.com/pyrycode/pyrycode-mobile/issues/308) (relay auth-gate) can tell a `4401` reject (→ re-pair, stop retrying) from a network blip (→ backoff retry) — OkHttp already hands these over, so dropping them would force a breaking change downstream.

## Lifecycle (single-use)

```
NEW ──connect()──▶ DIALING ──(onOpen → Up)──▶ UP ──(onClosed/onFailure/local-violation/close())──▶ DOWN (terminal)
                       │                        │
                       └──── send() enqueues ───┴──── inbound delivers received frames
```

- **`connect()`** — single-use, guarded by `connectStarted.compareAndSet(false,true)`; a second call throws `IllegalStateException` (caller bug). Builds the upgrade `Request`; a build failure from bad stored data (malformed `relayUrl`, header-injection attempt) is **caught and surfaced as a `Down`** (does not throw). Then `webSocketFactory.newWebSocket(request, listener)`.
- **`send(frame)`** — `webSocket?.send(MobileJson.encodeToString(frame)) ?: false`. Never throws; `false` if the socket is closed or the OkHttp send buffer is full.
- **`close()`** — `webSocket?.close(1000, null)` + the terminal path. Idempotent.
- **Terminal `Down`** — emitted **exactly once** via `terminated.compareAndSet(false,true)`, closing the race across `onClosed` / `onFailure` / a local protocol-violation close / an app `close()`. After it, both streams complete and the instance is **spent** — a supervisor discards it and constructs a fresh one to reconnect (Noise ephemerals are per-handshake; there is no resumable transport state, so a reconnect state machine is correctly kept **out** of this layer).

## The `/v1/client` verbatim dial

`connect()` builds the dial URL from `pairedServer.relayUrl`:

1. **Scheme-convert** for OkHttp's `HttpUrl` (which rejects `ws`/`wss`): `wss://`→`https://`, `ws://`→`http://` via `replaceFirst`; `http`/`https` left as-is (so a `ws://localhost` test and a `wss://…` production URL both work). The spike's idiom.
2. **Append the path as a literal on the URL string**: `base.trimEnd('/') + "/v1/client"`, passed to `Request.Builder().url(stringUrl)` so the slash stays literal. **Never** `addPathSegment("v1/client")` — that percent-encodes the `/` to `%2F`. **Never `/v2/client`** — the relay serves the `/v1/` namespace only; `v2` is the inner-frame `"v":2`.

## Headers

Set on the upgrade `Request` from `pairedServer` + `clientInfo`:

| Header | Value | Rule |
|---|---|---|
| `X-Pyrycode-Server` | `pairedServer.serverId` | required, always set |
| `X-Pyrycode-Token` | `pairedServer.token` | always set; relay **ignores** the value under v2 (the real authenticating token rides inside #298's encrypted hello, not here) |
| `User-Agent` | `clientInfo.clientVersion` | always set |
| `X-Pyrycode-Device-Name` | `clientInfo.deviceName` | **set only when `isNotBlank()`** — omitted for empty **and** whitespace-only |

`deviceName` is sourced from `NoiseClientInfo` (from `Build.MODEL`), **not** `PairedServer` (which has no such field). `Build.MODEL` is non-nullable, so "available" = **non-blank**, not non-null. OkHttp's `Request.Builder.header()` rejects CR/LF/control chars, so a hostile QR-sourced `serverId`/`token` cannot inject extra headers (it throws at build → surfaced as `Down`).

## Frame I/O

- **Outbound (`send`)** — `MobileJson.encodeToString(frame)` → bare text frame. `MobileJson` is mandatory: a default `Json` drops `v:2` (wire-breaking).
- **Inbound (`onMessage(text)`)** — size-guard (§ Network config), then `MobileJson.decodeFromString<InnerFrameV2>(text)`, then hand the typed frame to `inbound`. `data` is passed through **opaquely** — never base64-decoded or interpreted (it is Noise ciphertext, authenticated by the #298 layer's MAC).
- **Protocol violations from the untrusted relay** — an unexpected **binary** frame, a `SerializationException`, or an **oversized** frame is rejected: local `close()` with a recommended code (`1003` / `1002` / `1009`) + a single terminal `Down`. An ordered encrypted stream cannot skip a frame, so the transport **tears down rather than drops** — the supervisor reconnects with a fresh handshake.

## Streams & concurrency

Both `inbound` and `events` are `Channel(BUFFERED)` exposed via `receiveAsFlow()` — **hot, connection-scoped, single-consumer**:

- **`inbound`** is fed with `trySendBlocking(frame)`: when the buffer fills it **blocks the OkHttp reader thread** → OkHttp won't deliver the next message until `onMessage` returns → TCP flow-control. This is the **intended, deterministic backpressure** — frames are **never silently dropped** (a drop would break the upstream Noise nonce sequence). On `Down`, the channels `close()` and buffered frames drain to the consumer before the Flow completes (no truncation).
- **`events`** is sparse (`Up` once on open; the single terminal `Down`); `trySend` is fine. Buffered, so a supervisor subscribing right after `connect()` cannot miss the `Up`.

A `Channel` (not `SharedFlow`) because a transport stream wants exactly **one** logical consumer — the Noise pump for `inbound`, the supervisor for `events` — with clean completion on `Down` and no fan-out.

The transport **owns no coroutine and no scope** — OkHttp owns its reader/dispatcher threads; the channels bridge those callbacks to `Flow`. Shared mutable state is `@Volatile var webSocket` (written in `connect`, read in `send`/`close`) plus two `AtomicBoolean`s (`connectStarted`, `terminated`). `send`/`connect`/`close` are non-suspend and safe to call from any dispatcher. Nothing to leak or cancel on the transport's side.

## Network config (`defaultClient()`)

The securely-configured shared `OkHttpClient` lives here (unit-asserted), even though the *singleton binding* is the supervisor ticket's — keeping the security-critical config reviewable and testable in the same ticket as the dial it secures. The supervisor binds **one** app-wide client from it (a fresh client per reconnect would leak thread pools):

- `connectTimeout` = 15 s, `writeTimeout` = 15 s — bound the dial / a stuck send.
- `pingInterval` = 20 s — WS keepalive; a missed pong fails the connection (→ `Down`). **This is the liveness mechanism** for the long-lived socket.
- **`readTimeout` = 0, `callTimeout` = 0 (both disabled), deliberately** — a non-zero `readTimeout` would kill a healthy idle WS between frames; a non-zero `callTimeout` caps the whole call and would tear down the long-lived connection. Both are WS footguns; `pingInterval` provides liveness instead.
- `connectionSpecs = [MODERN_TLS, CLEARTEXT]` — TLS 1.2+ strong ciphers for `wss://`; `CLEARTEXT` retained for `ws://` local/dev relays; legacy `COMPATIBLE_TLS` (TLS 1.0/1.1) dropped.
- **No `HttpLoggingInterceptor`** — it would log the `X-Pyrycode-Token` header. If ever added for debug builds, it MUST redact that header.

**Inbound frame-size cap** — `MAX_INBOUND_FRAME_CHARS = 131_072` (128 KiB), comfortable headroom over the ≈87.5 KB max legitimate wire frame (base64 of 65519 B plaintext + 16 B tag, plus the JSON envelope). The wire frame is pure ASCII (JSON + base64-std), so `text.length` ≈ byte count — no re-encode needed. This is the inbound cap the Noise session ([#298](noise-ik-session.md)) explicitly deferred to the WS layer.

> **Honest limitation:** OkHttp's stable API reads the **whole** inbound message into memory before `onMessage`, so the cap is a **post-receive** guard — it stops an oversized frame from propagating and prevents repeated growth, but the single offending allocation already happened. `connectTimeout`/`pingInterval` bound the slow-loris variant, and the relay is the user's own paired (semi-trusted) infrastructure. True read-layer capping would need a non-stable OkHttp hook — out of scope.

## Security posture

The asset is the phone↔relay channel; the relay is **untrusted and content-blind by design** (pyrycode-side ADR 024) — E2E authenticity + confidentiality come from the [Noise_IK session](noise-ik-session.md) one layer up, not from this transport. This transport's security job is narrow and the spec's § Security review verdict is **PASS**:

- **Single untrusted→trusted crossing is `onMessage(text)`** — it trusts only the *structural envelope* (`decodeFromString<InnerFrameV2>` succeeds + size ≤ cap); `data` stays untrusted and opaque. Malformed/oversized/binary → reject (close + `Down`), never passed up.
- **Token hygiene** — read only into the `X-Pyrycode-Token` header (which the relay ignores under v2); never logged (no `Log`/`Timber`/`println` anywhere; `Down.reason`/exception strings are **category-only** — `t.javaClass.simpleName`, never the token, dial URL, or frame bytes; no `HttpLoggingInterceptor`). Token revocation propagates as a server WS close (`4401`), whose code this transport surfaces via `Down.code`.
- **No cert pinning on the relay TLS** — intentional: the trust anchor is the QR-pinned **Noise server static key** (`rs`), verified end-to-end in #298. A malicious relay is already defeated by Noise; pinning its cert would add rotation pain for no security gain.
- **No file/storage, no IPC/exported Android surface, no hand-rolled crypto, no RNG** — a portable `android.*`-free object; the only Android-origin values (`Build.MODEL`, version) arrive pre-resolved via `NoiseClientInfo`.

## Error handling

| Failure | Source | Surfaced as |
|---|---|---|
| Dial refused / DNS / TLS / non-101 upgrade | `onFailure(t, response)` | `Down(code = response?.code, reason = t.simpleName, cause = t)` |
| Server graceful close (incl. `4401`) | `onClosing`→ack→`onClosed(code, reason)` | `Down(code, reason, cause = null)` |
| Malformed inbound JSON / wrong shape | `decodeFromString` → `SerializationException` | local `close(1002,…)` + `Down(cause = SerializationException)` |
| Oversized inbound frame | size guard | local `close(1009,…)` + `Down(cause = ProtocolException)` |
| Unexpected inbound binary frame | `onMessage(bytes)` | local `close(1003,…)` + `Down(cause = ProtocolException)` |
| Malformed stored `relayUrl` / header-injection attempt | `Request` build in `connect()` | caught → `Down(cause = IllegalArgumentException)` (does **not** throw) |
| `send()` after close / before open | `webSocket == null` | returns `false` (no throw) |
| `connect()` twice | state guard | `IllegalStateException` (caller bug) |

**Bad data → uniform `Down`; bad call order → throw** — external/stored failures all funnel to `events` so the supervisor handles them in one place; only programmer errors raise.

## Edge cases & limitations

- **Single-use** — after the terminal `Down`, the instance is spent; reconnecting means a **fresh instance**, not a reset (the supervisor's job).
- **In-flight outbound frames** may be lost on an abrupt `close()` (still in OkHttp's buffer) — acceptable: a dropped/half-sent frame forces the supervisor to reconnect with a fresh handshake (same non-resumable contract as #298). No durable state, so process death mid-send leaves nothing partial.
- **No outbound frame-size cap** — deferred (evidence-based: we generate well-formed frames upstream; no observed failure). Keeping outbound ≤ 65519 B plaintext is the Noise/app layer's job. The **inbound** cap is in scope (above).
- **Replay/reorder by an active relay** is detected by the Noise monotonic nonce one layer up — not here.

## Testing

JVM-only (`app/src/test/.../OkHttpRelayTransportTest.kt`, `./gradlew test`), driven against an in-process `MockWebServer().withWebSocketUpgrade(serverListener)` — no device, like the sibling `data/network` tests. The **real** OkHttp reader/dispatcher threads feed the Channel-backed flows, so each scenario bridges them with `runBlocking { withTimeout(5_000) { … } }` collecting `inbound.first()` / `events.take(n).toList()` — **not** `runTest` virtual time (which would not advance real network threads).

Coverage: connect path + all headers (incl. the present-when-non-blank and absent-when-blank/whitespace device-name cases), outbound serialize → bare text frame, inbound text → `InnerFrameV2` (opaque `data` unchanged), `Up` then `Down(4401)` on server close, `Down(cause)` on dial failure, malformed-URL/malformed-JSON → `Down` (no throw), the `connect()`-twice / `send()`-after-`close()` state guards, and the `defaultClient()` security config.

> **Test-harness note (reusable):** a server-side WS listener **must echo the peer's close** (`onClosing` → `webSocket.close(code, null)`), or a client-initiated close leaves the socket half-open and `MockWebServer.shutdown()` hangs ~5 s. The tests use a `ClosingServerListener` base every server listener extends. See [`codebase/306.md`](../codebase/306.md) § Lessons learned (and the Kotlin-nested-block-comment compile trap).

## Related

- Ticket notes: [`../codebase/306.md`](../codebase/306.md) — Patterns established + Lessons learned + files/line refs.
- Spec: `docs/specs/architecture/306-relay-ws-transport.md` (§ Design, § Network config, § Security review — Verdict PASS).
- Sits on: [Mobile Protocol v2 wire layer](mobile-protocol-v2-wire-layer.md) ([#273](../codebase/273.md)) — `InnerFrameV2` + `MobileJson`. Reads the [`PairedServer`](paired-server-store.md) ([#294](../codebase/294.md)) record (relay URL + serverId + token) and `NoiseClientInfo` ([#298](../codebase/298.md)) (device name + version).
- Carries (opaquely): the raw Noise bytes the [Noise_IK session](noise-ik-session.md) ([#298](../codebase/298.md)/[#303](../codebase/303.md)) produces — once the future WS pump wraps them as `InnerFrameV2(type, base64(bytes))`.
- Decisions: [ADR 0005 — OkHttp WebSocket engine](../decisions/0005-okhttp-websocket-engine.md) (this is the first production reference it named). Aligns with pyrycode-side ADR 024 (relay untrusted; E2E auth is Noise).
- Consumed by (not yet wired — all build on this `connect()`/`close()`/`send()`/`inbound`/`events` surface): the **sibling reconnect supervisor** (auto-reconnect/backoff + the live `ConnectionStateSource` swap; binds the singleton `OkHttpClient` from `defaultClient()`), **#278** (`RemoteConversationRepository`), **#302** (process-lifecycle reconnect), **#308** (relay auth-gate — consumes `Down.code == 4401`).
- Spike / byte oracle: vault doc *"Phase 4 — Noise Client Spike Findings"* (`second-brain`, `2026-05-02-pyrycode-mobile/phase-4-noise-client-spike-findings.md`). Kotlin reference client: `~/WorkSpace/Projects/noise-spike/src/main/kotlin/Main.kt`.

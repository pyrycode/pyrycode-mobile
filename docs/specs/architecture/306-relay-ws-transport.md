# Architecture: relay WS transport — `/v1/client` dial + bare `InnerFrameV2` frame I/O (#306)

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireModels.kt:22-27` — `InnerFrameV2(v=2, type, data)`, the bare WS **text** payload this transport (de)serializes. `data` is opaque base64 — never decode/interpret it here.
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireCodec.kt:29-34` — `MobileJson`, the **only** `Json` to use. Its `encodeDefaults`/`explicitNulls`/`ignoreUnknownKeys` config is the wire contract (a default `Json` drops `v:2` → server-breaking). Both directions go through it.
- `app/src/main/java/de/pyryco/mobile/data/crypto/PairedServerStore.kt:43-51` — `PairedServer(serverId, token, relayUrl, serverStaticPublicKey)`; `toString()` already redacts `token`. The transport derives the dial URL + the `X-Pyrycode-Server`/`X-Pyrycode-Token` headers from it. It has **no** device-name field (see header construction).
- `app/src/main/java/de/pyryco/mobile/data/network/NoiseIkSession.kt:17-20` — `NoiseClientInfo(deviceName, clientVersion)`. Source of the optional `X-Pyrycode-Device-Name` header (`deviceName`) and the `User-Agent` version (`clientVersion`). Bound in `di/AppModule` from `Build.MODEL`/`BuildConfig.VERSION_NAME` — keeps `data/` `android.*`-free.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConnectionStateSource.kt:1-26` and `app/src/main/java/de/pyryco/mobile/di/AppModule.kt:30-49` — the **dormant-but-tested** pattern. This transport ships dormant: **no Koin binding changes**, `FakeConnectionStateSource` stays bound as `ConnectionStateSource`. Read `AppModule` to confirm what you must **not** touch.
- `~/WorkSpace/Projects/noise-spike/src/main/kotlin/Main.kt:38-69,152-156` — the proven OkHttp dial idiom: ws→http / wss→https scheme conversion via `replaceFirst` (`HttpUrl` rejects `ws`/`wss`), the four headers verbatim, `client.newWebSocket(request, listener)`, and `ws.send(text): Boolean` (false = closed / buffer full). The byte-faithful reference; mirror it.
- `app/build.gradle.kts:79-105` — `okhttp` and `kotlinx-coroutines-core` are already `implementation`; `kotlinx.coroutines.test` is already `testImplementation`. Add **one** line: `testImplementation(libs.okhttp.mockwebserver)`.
- `gradle/libs.versions.toml:14,52` — `okhttp = "4.12.0"` version + the `okhttp` library. Add **one** library: `okhttp-mockwebserver` sharing `version.ref = "okhttp"`. No new version key.
- `docs/specs/architecture/298-noise-ik-session-handshake-aead-transport.md:24-25,166,187` — the layer **above** this one. Its session produces/consumes raw Noise bytes; the WS layer (this ticket) wraps them as `InnerFrameV2(type, base64(bytes))` and pumps them. Confirms the boundary: this transport never decodes `data`. Its § Open questions explicitly defers the **inbound frame-size cap** to the WS layer — i.e. here.
- Vault doc **"Phase 4 — Noise Client Spike Findings"** (QMD `second-brain/1f4cb-projects/2026-05-02-pyrycode-mobile/phase-4-noise-client-spike-findings.md`) — § "Relay path resolution" (dial `/v1/client` **verbatim**, never `/v2/client`; v2 is an inner-frame concern) and § "Proven wire contract" (header contract; `X-Pyrycode-Token` required-non-empty but **ignored** by the relay under v2 — the real token rides inside the encrypted hello, which is #298's concern, not here). The byte oracle; codegraph won't surface it (vault markdown).

## Context

Phase 4 transport — the single-connection WebSocket leg that carries bare `InnerFrameV2` JSON **text** frames between the phone and the paired relay. The relay is protocol-neutral and content-blind: it wraps/unwraps a `{conn_id, frame}` routing envelope on its **binary** leg only — the phone sends/receives bare `InnerFrameV2` text frames and never sees the envelope.

**Transport-only.** No Noise, no reconnect, no `ConnectionStateSource` swap. The Noise_IK handshake / AEAD / encrypted `hello` token is #298's concern; this transport carries opaque frames and never inspects `data`. Auto-reconnect/backoff and the live `ConnectionStateSource` are the sibling supervisor's concern (a separate child split from #301, blocked-by this one). This transport is a **single dial**: one `connect()` = one socket, surfacing raw up/down events for a supervisor to drive a reconnect loop on top.

It ships **dormant** — same pattern as `PairedServerStore`/`NoiseSessionFactory`: the types exist and are unit-tested, but **no Koin binding is added** and nothing consumes them yet. The sibling supervisor ticket wires the singleton client + binding.

This is a data-layer transport primitive — **not UI-visible**, so there is correctly no `## Figma` / `## Design source` section (the ticket body has none; not a PO gap — same shape as #298/#291).

## Design

### Placement & portability

New code lands in `de.pyryco.mobile.data.network` (`app/src/main/java/de/pyryco/mobile/data/network/`), sibling to the wire codec (#273) it sits on and the Noise session (#298) that will sit above it.

**The transport contract stays portable** (`Flow`, `InnerFrameV2`, `PairedServer`, `NoiseClientInfo` — no `android.*`). The OkHttp engine is a JVM dependency (already on the classpath, spike-proven — ADR 0005), not Android-only, so the `data/` portability trigger (CMP walk-back) is satisfied. The only Android-bound values (device name, version) arrive pre-resolved via `NoiseClientInfo` (platform-custodian pattern, [[data-layer-android-import-exception]]).

### Types (2 new production files, 3 exported symbols)

**`RelayTransport.kt`** — the contract + its event type:

```kotlin
/** A single-connection relay WS transport. One connect() = one socket. NOT reconnecting. */
interface RelayTransport {
    /** Hot, connection-scoped, single-consumer stream of received frames. Completes on Down. */
    val inbound: Flow<InnerFrameV2>

    /** Hot, connection-scoped, single-consumer stream of Up/Down transport events. */
    val events: Flow<TransportEvent>

    /** Open one socket (async). Up/Down arrive on [events]. Idempotent-guarded: a second call throws. */
    fun connect()

    /** Serialize (via MobileJson) and enqueue [frame] as a bare text frame. false = socket closed / buffer full. */
    fun send(frame: InnerFrameV2): Boolean

    /** Tear down the socket and complete the streams. Idempotent. */
    fun close()
}

sealed interface TransportEvent {
    data object Up : TransportEvent
    /** [code] = WS close code (1000, 4401, …) or HTTP status on a rejected upgrade; [cause] non-null on abnormal failure. */
    data class Down(val code: Int?, val reason: String?, val cause: Throwable?) : TransportEvent
}
```

`Down` enriches the ticket's `Down(cause)` to also carry the WS **close code** + reason. This is a deliberate, stable choice, not scope creep: the sibling supervisor and #308 (relay auth-gate) need to distinguish a `4401` reject (→ re-pair, stop retrying) from a network blip (→ backoff retry). OkHttp already hands these in `onClosed`/`onFailure`; dropping them would force a breaking contract change downstream. Still one event type — minimal.

**`OkHttpRelayTransport.kt`** — the impl:

```kotlin
class OkHttpRelayTransport(
    private val pairedServer: PairedServer,
    private val clientInfo: NoiseClientInfo,
    private val webSocketFactory: WebSocket.Factory,   // the dial seam (= a configured OkHttpClient)
) : RelayTransport {
    // … connect()/send()/close() + a private WebSocketListener …

    companion object {
        /** The securely-configured shared client for relay dials (timeouts, TLS, 20s ping). See § Network config. */
        fun defaultClient(): OkHttpClient = /* … */
    }
}
```

`webSocketFactory` is a **required** constructor param (no default) — the dial seam the technical notes ask for. Tests pass a plain `OkHttpClient()` (or `defaultClient()`); the supervisor passes the app-wide singleton it builds from `defaultClient()` and binds in Koin. `OkHttpClient implements WebSocket.Factory`, so `webSocketFactory.newWebSocket(request, listener)` is the production call and `MockWebServer` drives the same seam in-process. Keeping the security-critical client config in `defaultClient()` **here** (vs. deferring it to the unwritten DI ticket) makes it unit-testable and reviewable in this ticket while preserving the one-shared-client best practice.

Exported types: `RelayTransport`, `TransportEvent`, `OkHttpRelayTransport` = **3** (≤ 5). The interface is justified now (not deferred like #298's `NoiseSession`): the ticket names three consumers — the supervisor, #278, #302 — that build on this exact surface and will fake it in their tests.

### Connection lifecycle (single-use)

```
NEW ──connect()──▶ DIALING ──(onOpen)──▶ UP ──(onClosed/onFailure | close())──▶ DOWN(terminal)
                      │                   │
                      └── send() ─────────┴── send() enqueues; inbound delivers
```

The transport is **single-use**: `connect()` once, terminal on the first Down. A second `connect()` (socket already created) throws `IllegalStateException` (caller bug, mirrors #298's wrong-order guards). After Down, `inbound`/`events` complete; the supervisor discards the instance and constructs a fresh one to reconnect (Noise ephemerals are per-handshake anyway — no resumable state). This keeps a reconnect state machine **out** of this ticket.

### URL construction (the `/v1/client` verbatim contract)

`connect()` builds the dial URL from `pairedServer.relayUrl`:
1. Scheme-convert for OkHttp's `HttpUrl` (which rejects `ws`/`wss`): `wss://`→`https://`, `ws://`→`http://`; leave `http`/`https` as-is (lets a `ws://localhost` test and a `wss://…` production URL both work). Exactly the spike's `replaceFirst` idiom.
2. Append the path: `base.trimEnd('/') + "/v1/client"`. Built as a single URL **string** passed to `Request.Builder().url(stringUrl)` so the slash stays literal — **never** `addPathSegment("v1/client")` (singular), which percent-encodes the `/` to `%2F`. Never `/v2/client` (the spike settled the relay serves `/v1/*` only; v2 is the inner-frame `"v":2`).

A malformed `relayUrl` (HttpUrl parse failure) does **not** throw from `connect()`; it is caught and surfaced as a `Down(code=null, reason=…, cause=IllegalArgumentException)` so the supervisor handles all failures uniformly via `events`. (Wrong-*order* calls still throw `IllegalStateException` — that's a caller bug, distinct from bad data.)

### Header construction

On the upgrade `Request` (read from `pairedServer` + `clientInfo`):

| Header | Value | Rule |
|---|---|---|
| `X-Pyrycode-Server` | `pairedServer.serverId` | required, always set |
| `X-Pyrycode-Token` | `pairedServer.token` | required non-empty; relay **ignores** the value under v2 (real token is in the encrypted hello, #298) |
| `User-Agent` | `clientInfo.clientVersion` (or a `pyrycode-mobile/<version>` form) | always set |
| `X-Pyrycode-Device-Name` | `clientInfo.deviceName` | **set only when `clientInfo.deviceName.isNotBlank()`**; omit the header entirely otherwise |

`deviceName` is sourced from `NoiseClientInfo`, **not** `PairedServer` (which has no such field). `Build.MODEL` is a non-nullable `String`, so "available" = **non-blank** (`isNotBlank()`, so a whitespace-only value is also omitted), not non-null. OkHttp's `Request.Builder.header(name, value)` rejects CR/LF/control chars, so a hostile QR-sourced `serverId`/`token` cannot inject extra headers (it throws at build → surfaced as `Down`).

### Frame I/O

- **Outbound — `send(frame)`:** `webSocket?.send(MobileJson.encodeToString(frame)) ?: false`. `MobileJson` is mandatory (defaulted-field omission is wire-breaking). Returns OkHttp's enqueue boolean.
- **Inbound — `onMessage(text)`:** size-guard (§ Network config), then `MobileJson.decodeFromString<InnerFrameV2>(text)`, then hand the typed frame to the `inbound` stream. `data` is passed through **opaquely** — never base64-decoded or interpreted (it's Noise ciphertext, authenticated one layer up).
- **Inbound binary / malformed / oversized:** a binary frame, a `SerializationException`, or an over-cap frame is a protocol violation → close the socket (recommended codes: `1002` protocol error / `1009` message too big) and emit a single terminal `Down(cause = …)`. An ordered encrypted stream cannot skip a frame, so the transport tears down rather than dropping — the supervisor reconnects with a fresh handshake.

### Streams (hot, Channel-backed, single-consumer)

Both `inbound` and `events` are backed by a `Channel` exposed via `receiveAsFlow()` — hot, connection-scoped, buffered, single-consumer:

- **`inbound`**: `Channel<InnerFrameV2>(capacity = Channel.BUFFERED)`. The listener feeds it with `trySendBlocking(frame)` — when the buffer fills, this **blocks the OkHttp reader thread**, which is the intended backpressure (OkHttp won't deliver the next message until `onMessage` returns → TCP flow-control), so frames are **never silently dropped** (a drop would break the Noise nonce sequence one layer up). On Down, `close()` the channel — buffered frames drain to the consumer before the Flow completes (no truncation).
- **`events`**: `Channel<TransportEvent>(capacity = Channel.BUFFERED)`. Sparse traffic; `trySend` is fine. `Up` on `onOpen`; the single terminal `Down` on `onClosed`/`onFailure`/local-violation/`close()`; then close the channel.

Channel (not `SharedFlow`): a transport stream wants exactly one logical consumer (the Noise pump for `inbound`, the supervisor for `events`); a `Channel` buffers an early `Up` so a supervisor subscribing right after `connect()` cannot miss it, and gives clean completion on Down. No fan-out is wanted.

### DI wiring — NONE

Per the AC, **no `AppModule` change**. `FakeConnectionStateSource` stays bound. The supervisor ticket adds the singleton `OkHttpClient` (from `defaultClient()`) + the transport wiring. (There is no "Phase-4 flag" to gate on — [[phase4-no-central-flag-gate-per-piece]]; dormant-by-no-consumer is the gate.)

## State + concurrency model

- **Not a ViewModel** — no `StateFlow`/`UiState`/`Event`. An imperative transport object with two hot `Flow`s.
- **No coroutine scope owned by the transport.** OkHttp owns its reader/dispatcher threads; the `Channel`s bridge those callbacks to `Flow`. Consumers collect on their own dispatchers. Nothing to leak or cancel on the transport's side.
- **Shared mutable state:** `@Volatile var webSocket: WebSocket?` (written in `connect()`, read in `send()`/`close()`), and an `AtomicBoolean terminated`. The terminal Down + channel-close path runs **exactly once** via `terminated.compareAndSet(false, true)` — guarding the race between `onClosed`, `onFailure`, a local protocol-violation close, and an app `close()`.
- **Dispatcher / liveness:** no `readTimeout`-based liveness on the long-lived socket; `pingInterval` (§ Network config) drives keepalive + dead-peer detection. `send()`/`connect()`/`close()` are non-suspend and safe to call from any dispatcher.
- **Shutdown / cancellation:** `close()` → `webSocket?.close(1000, null)` + terminal path. In-flight outbound frames still in OkHttp's buffer may be lost on an abrupt close — acceptable, because a dropped/half-sent frame forces the supervisor to reconnect with a fresh handshake (same non-resumable contract as #298). No durable state, so process death mid-send leaves nothing partial.

## Network config (`defaultClient()`)

The production `OkHttpClient` (built in `defaultClient()`, unit-asserted) — values are the contract; the developer finalizes exact numbers:

- **`connectTimeout` = 15s** — bound the initial dial against a slow/hostile relay.
- **`writeTimeout` = 15s** — bound a stuck send.
- **`pingInterval` = 20s** — WS keepalive; a missed pong fails the connection (→ `onFailure` → `Down`). This is the liveness mechanism for the long-lived socket (the spike used 20s).
- **`readTimeout` = 0 (disabled)** and **`callTimeout` = 0 (unset)** — deliberately. A non-zero `readTimeout` would kill a healthy idle WS between frames; a non-zero `callTimeout` caps the *whole call* and would tear down the long-lived connection after that duration. Both are WS footguns; `pingInterval` provides liveness instead. (This is the security checklist's "if the spec rejects a default, say why".)
- **`connectionSpecs` = `[MODERN_TLS, CLEARTEXT]`** — TLS 1.2+ with strong ciphers for `wss://`; `CLEARTEXT` retained for `ws://` local/dev relays (the spike's `--insecure-listen`). `COMPATIBLE_TLS` (legacy TLS 1.0/1.1) is dropped.
- **No `HttpLoggingInterceptor`** on the production client — it would log the `X-Pyrycode-Token` header. If one is ever added for debug builds, it MUST redact that header.

**Inbound frame-size cap.** The wire contract caps **plaintext** at 65519 B; the corresponding **wire** `InnerFrameV2` text frame (base64 of plaintext+16, plus JSON envelope) is ≈ 87.5 KB at the maximum. Define a single constant `MAX_INBOUND_FRAME_CHARS` (suggest **131072 = 128 KiB**, comfortable headroom over a max legitimate frame) and reject any inbound text frame exceeding it (the wire frame is pure ASCII — JSON + base64-std — so `text.length` ≈ byte count; no re-encode needed). Limitation, surfaced honestly: OkHttp's stable API reads the full message into memory before `onMessage`, so the cap is a **post-receive** guard — it prevents propagating an oversized frame and prevents repeated growth, but the single offending allocation already happened; `connectTimeout`/`pingInterval` bound the slow-loris variant, and the relay is the user's own paired infrastructure (semi-trusted). True read-layer capping would need a non-stable OkHttp hook — out of scope.

## Error handling

| Failure | Source | Surfaced as |
|---|---|---|
| Dial refused / DNS / TLS / non-101 upgrade | `onFailure(t, response)` | `Down(code = response?.code, reason, cause = t)` |
| Server graceful close (incl. `4401`/`4421`/`4426`/`4429`) | `onClosing`→ack→`onClosed(code, reason)` | `Down(code, reason, cause = null)` |
| Malformed inbound JSON / wrong shape | `MobileJson.decodeFromString` → `SerializationException` | local `close(1002,…)` + `Down(cause = SerializationException)` |
| Oversized inbound frame | size guard | local `close(1009,…)` + `Down(cause = ProtocolException)` |
| Unexpected inbound binary frame | `onMessage(bytes)` | local `close(1003,…)` + `Down(cause = ProtocolException)` |
| Malformed stored `relayUrl` | `HttpUrl` parse in `connect()` | caught → `Down(cause = IllegalArgumentException)` (does not throw) |
| `send()` after close / before open | `webSocket == null` | returns `false` (no throw) |
| `connect()` twice | state guard | `IllegalStateException` (caller bug) |

No `Log`/`Timber`/`println` anywhere in the transport. Exception/`Down.reason` strings are category-only — never the token, the dial URL, or frame bytes. `PairedServer.toString()` already redacts the token; the transport never logs it regardless.

## Testing strategy

JVM unit test `app/src/test/java/de/pyryco/mobile/data/network/OkHttpRelayTransportTest.kt` (`./gradlew test`), driven by `okhttp3.mockwebserver.MockWebServer` with `MockResponse().withWebSocketUpgrade(serverListener)` — an in-process WS server, the technical-notes "in-process server" for the dial seam. No device, like the existing `data/network` JVM tests. Bridge the real OkHttp threads with `runTest`/`runBlocking` + `withTimeout(…)` collecting `inbound.first()` / `events.take(n).toList()` — do **not** rely on `runTest` virtual-time to advance the network (real threads feed the channels).

Scenarios (developer writes bodies in the project idiom — bullet = setup → assertion):

- **Connect path + required headers (AC #1, #4).** `relayUrl = "ws://${server.hostName}:${server.port}"`, `serverId="srv-1"`, `token="tok"`, `NoiseClientInfo("Pixel-Test","1.2.3")`. After `connect()` + open, `server.takeRequest()`: assert `path == "/v1/client"`, `X-Pyrycode-Server == "srv-1"`, `X-Pyrycode-Token == "tok"`, `User-Agent` carries `"1.2.3"`, `X-Pyrycode-Device-Name == "Pixel-Test"`.
- **Device-name omitted when blank (AC #1, #4).** `NoiseClientInfo(deviceName = "")` (and a whitespace-only `"  "` case) → assert the `X-Pyrycode-Device-Name` header is **absent** (`takeRequest().getHeader(...) == null`).
- **Outbound serialize → bare text frame (AC #2, #4).** `connect()`, `send(InnerFrameV2(type="noise_init", data="AAAA"))`; the server listener's received text equals `MobileJson.encodeToString(frame)` and `MobileJson.decodeFromString<InnerFrameV2>` of it round-trips (v=2, type, data preserved). Assert `send` returned `true`.
- **Inbound text frame → InnerFrameV2 (AC #2, #4).** Server sends `MobileJson.encodeToString(InnerFrameV2(type="noise_resp", data="BBBB"))`; `inbound.first()` deserializes to the expected frame with `data` passed through **unchanged** (opaque).
- **Up on open / Down on close (AC #3).** `events.first() == Up` after open; after `server` closes with code `4401`, `events` emits `Down(code = 4401, …)` (validates close-code surfacing for #308).
- **Down on dial failure (AC #3).** Point at a shut-down server / refused port; `connect()` → `events` emits `Down(cause != null)`.
- **`connect()` twice throws; `send()` after `close()` returns false.** State-guard coverage.
- **`defaultClient()` config.** Assert `connectTimeoutMillis == 15_000`, `pingIntervalMillis == 20_000`, `readTimeoutMillis == 0`, and `connectionSpecs` excludes `COMPATIBLE_TLS` (TLS config is security-load-bearing).

## Open questions

- **Outbound frame-size cap.** A defensive `send()` guard rejecting an oversized outbound frame is cheap, but we generate well-formed frames upstream — deferred (evidence-based: no observed failure). The inbound cap is in scope (above); outbound is the Noise/app layer's to keep within 65519 B plaintext.
- **Exact `MAX_INBOUND_FRAME_CHARS` value.** 128 KiB is a headroom-comfortable default over the ≈87.5 KB max legitimate wire frame; the developer may tighten to e.g. 96 KiB. Either satisfies the cap intent.
- **Shared client ownership.** `defaultClient()` defines the secure config here; the *singleton binding* is the supervisor ticket's (this ticket adds no Koin binding). The supervisor must bind one app-wide client and pass it in (constructing a fresh `OkHttpClient` per reconnect would leak thread pools).

## Sizing

**S — confirmed** (PO sized S; not overriding to XS — a genuine async WS transport with a real `MockWebServer` harness and a security surface). Production source files: `RelayTransport.kt` (new), `OkHttpRelayTransport.kt` (new) = **2** (< 5). New exported types: `RelayTransport`, `TransportEvent`, `OkHttpRelayTransport` = **3** (≤ 5). Total written ≈ 40 (interface + event) + ~180 (impl: connect/send/close + listener + URL/scheme + headers + channels + terminal CAS + `defaultClient`) + ~230 (test: harness + ~8 scenarios) + 2 build-file lines ≈ **~450** (≤ ~600). Edit fan-out: 2 new files + 2 additive build-file lines, **no rename, no consumer cascade** (dormant, no DI binding) ≈ **0** (≤ 10). Error/reject branches: 8 (table above), < 10. New dependency: `mockwebserver` is `testImplementation`-only, version-shared with the existing `okhttp` (one `libs.versions.toml` library line + one `build.gradle.kts` line) — no new production dep, no `settings.gradle.kts`. Within every red line; ships as one ticket.

## Security review

**Verdict:** PASS

The asset is the phone↔relay transport channel. The relay is **untrusted and content-blind** by design (ADR 024 / `protocol-mobile.md` § Security model): end-to-end authenticity + confidentiality come from the Noise_IK session one layer up (#298), not from this transport. This transport's security job is narrow: treat all inbound bytes as untrusted, bound them, never leak the token, and dial over strong TLS. Two findings were folded into the spec before this verdict (the inbound frame-size cap → § Network config; the no-logging-interceptor rule); the checklist was then re-walked from the top.

**Findings:**

- **[Trust boundaries]** The single untrusted→trusted crossing is `onMessage(text)` — attacker-controllable bytes from the untrusted relay. It is **explicit and single-function**. The transport trusts only the *structural envelope* (`MobileJson.decodeFromString<InnerFrameV2>` succeeds + size ≤ cap); `data` stays **untrusted and opaque** (never decoded here — it is Noise ciphertext the #298 layer authenticates via MAC). A malformed/oversized/binary frame is rejected (close + `Down`), never passed up. Downstream knows `data` is untrusted until Noise-decrypt succeeds. No finding.
- **[Tokens / secrets]** The token is read from `PairedServer.token` and placed only in the `X-Pyrycode-Token` header — which the relay **ignores under v2** (the real authenticating token rides inside #298's encrypted hello, not here). It is never logged: no `Log`/`Timber`, exception/`Down.reason` strings are category-only, `PairedServer.toString()` redacts it, and the production client installs **no `HttpLoggingInterceptor`** (which would dump request headers incl. the token) — folded into § Network config as a hard rule. Header-injection via a hostile QR-sourced `serverId`/`token` is blocked: OkHttp's `header()` rejects CR/LF/control chars (throws → `Down`). Token lifecycle (creation/rotation/revocation) is #294/#277's; revocation propagates as a server WS close (`4401`), whose code this transport now surfaces via `Down.code`. No residual finding.
- **[File / storage]** N/A by design — this ticket performs **no** file/storage I/O. It reads an already-loaded `PairedServer` value object; the encrypted-at-rest custody is `PairedServerStore`'s (#294). No path concatenation from untrusted input (the only string-built path is the constant `"/v1/client"` appended to the paired relay base; `serverId`/`token` go into headers, never a filesystem path). No new at-rest artifact, no backup surface.
- **[Inter-process / Android surface]** N/A by design — the transport is a portable, `android.*`-free object. No exported `Activity`/`Service`/`Receiver`, no `<intent-filter>`, no deep link, no `PendingIntent`, no `ContentProvider`, no `WebView`. The only Android-origin values (`Build.MODEL`, version) arrive pre-resolved via `NoiseClientInfo` at the Koin module (out of this file), are not attacker-controlled, and carry no secret.
- **[Cryptographic primitives]** No hand-rolled crypto here. Transport confidentiality is OkHttp/JSSE TLS with **`MODERN_TLS` (TLS 1.2+, strong ciphers)**; `COMPATIBLE_TLS`/legacy TLS dropped; `CLEARTEXT` retained only for `ws://` local/dev relays (an explicit, documented choice). **Certificate pinning is intentionally NOT applied**: the relay is untrusted by the threat model, and the trust anchor is the QR-pinned **Noise server static key** (`rs`) verified end-to-end in #298 — pinning the relay's TLS cert would add rotation pain for no security gain (a malicious relay is already defeated by Noise). No RNG on any path in this file. No secret-equality compare (the `X-Pyrycode-Token` value is opaque to the relay; no token comparison happens client-side). No finding.
- **[Network & I/O]** SHOULD/MUST-class items folded in. **Frame-size cap:** inbound frames are capped (`MAX_INBOUND_FRAME_CHARS`, § Network config) — the #298 deferral lands here. **Timeouts:** `connectTimeout`/`writeTimeout`/`pingInterval` are set; `readTimeout`/`callTimeout` are deliberately 0 for the long-lived WS with `pingInterval` as the liveness mechanism (justified in-spec). **TLS:** MODERN_TLS+CLEARTEXT as above. Residual, surfaced honestly: OkHttp reads a full inbound message into memory before `onMessage`, so the cap is post-receive — a single oversized allocation can occur before rejection; bounded by `connectTimeout`/`pingInterval` and the semi-trusted (user-paired) relay. Read-layer capping needs a non-stable OkHttp hook — OUT OF SCOPE. Replay/reorder by an active relay is detected by the Noise monotonic nonce one layer up, not here. No residual MUST-FIX.
- **[Error messages / logs / telemetry]** No logging of any kind in the transport (no Logcat leak in release). `Down`/exception messages are category-only — no token, no dial URL, no frame bytes, no headers. No telemetry/analytics added. No finding.
- **[Concurrency]** The transport launches **no coroutine** and owns **no scope** (OkHttp owns threads; `Channel`s bridge to `Flow`) → no scope leak, no cancellation hazard. The terminal Down + channel-close runs exactly once via `terminated.compareAndSet` — closing the TOCTOU race across `onClosed`/`onFailure`/local-violation/`close()`. Inbound backpressure is deterministic (`trySendBlocking` blocks the OkHttp reader → TCP flow-control; no silent drop, which would break the upstream Noise nonce sequence). `inbound`/`events` are hot, single-consumer, connection-scoped Channels — no cross-screen leak (and frames carry only opaque ciphertext, no plaintext secret). `@Volatile webSocket` covers the connect/send/close visibility. No finding.
- **[Threat model alignment]** Aligned with `protocol-mobile.md` § Security model / ADR 024: this transport keeps the relay **untrusted and content-blind** — it carries opaque frames, never trusts `data`, and real authentication is Noise (#298, QR-pinned `rs`). Active-relay MITM (drop/reorder/inject) is detected upstream by the Noise MAC → reconnect; passive wire eavesdropping is blocked by MODERN_TLS. Mobile-specific threats (screenshot/overlay/accessibility/deep-link/keyboard-logging) are **N/A** — headless data-layer code, no UI, no input fields, no deep links; the token is not entered here. Out of scope, named: Noise handshake/AEAD (#298), reconnect/backoff + `4401` re-pair policy (sibling supervisor / #302 / #308), token lifecycle (#294/#277).

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-05-31

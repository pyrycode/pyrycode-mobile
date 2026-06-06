# Spec: Re-register the FCM push token on every reconnect (#365)

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinator.kt:41-110` — the whole coordinator. **Load-bearing:** the `onConnection` doc comment (`:69-75`) declares it **non-suspending by design** (cancellation-atomicity / key-wipe invariant). The hook is launched *from* here but its suspending body runs on the child scope, never inline.
- `app/src/main/java/de/pyryco/mobile/data/repository/SessionPump.kt:32-48` — the `ManagedSessionPump` interface (the coordinator's lifecycle view of the pump). Add `state` here. Note it already imports `Envelope` from `data/network/`, so importing `PumpState` from the same package is consistent.
- `app/src/main/java/de/pyryco/mobile/data/network/NoiseSessionPump.kt:59-62` — `mutableState` + the **already-public** `val state: StateFlow<PumpState>`. Only change: add `override`.
- `app/src/main/java/de/pyryco/mobile/data/network/NoiseSessionPump.kt:287-301` — `PumpState` (`Handshaking → Open(connId) → Closed(cause?)`). The state machine never revisits `Handshaking`; re-key stays `Open`. So "first transition out of `Handshaking`" is exactly-once per connection.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:65-83` — constructor `(pump, scope, deviceName: String = "")`. The `deviceName` param **already exists, defaulted to `""`** (#359). This slice closes that defer by passing the live value — no signature change.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:541-573` — `registerPushToken(token)`. **Suspend.** Throws `RelayErrorException` on server `error`, `IllegalStateException` when the session is not Open (`send` returned `false`). Never logs the token. Not on the `ConversationRepository` interface → reachable only via the concrete handle.
- `app/src/main/java/de/pyryco/mobile/data/network/NoiseIkSession.kt:16-20` — `NoiseClientInfo(deviceName, clientVersion)`; `deviceName` is `Build.MODEL` (wired in AppModule).
- `app/src/main/java/de/pyryco/mobile/data/preferences/AppPreferences.kt:73-78` — `val pushToken: Flow<String?>` (#364, merged) + `setPushToken`. The connect-time read is `pushToken.first()`.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt:76-82` — the single production construction site of the coordinator. Lines 47, 50 already resolve `AppPreferences` and `NoiseClientInfo` as singletons.
- `app/src/test/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinatorTest.kt` — the whole file. `FakeManagedPump:311-341` (needs a `state`), `newEnv:249-260` (needs the two new params), the `runCurrent()`-driven `StandardTestDispatcher` idiom.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt:1242-1320` — #359's `registerPushToken` tests. Copy the **ack/error reply correlation** shape: read `pump.sent.single { it.type == "register_push_token" }`, then `push(ackEnvelope(sent.id))` / `push(errorEnvelope(sent.id, code, retryable))`. Those helpers are `private` to that class; replicate the ~3-line envelope builders in the coordinator test (an `ack` is `Envelope(type="ack", inReplyTo=sent.id, payload={})`).

## Context

Phase 4 FCM push. #359 (merged) shipped the wire sender `RemoteConversationRepository.registerPushToken(token)`; #364 (merged) shipped `AppPreferences.pushToken` persistence. Per the daemon's contract (pyrycode `docs/protocol-mobile.md` § Phone background behaviour — a server-repo doc), the phone **re-registers on every WS connect**: the server de-duplicates the `(platform, token, device_name)` triple, so a repeat is a cheap (~100 B) no-op that self-heals registry drift across app restarts and connection drops.

This slice adds the connect-time orchestration: when a fresh Noise session reaches `Open`, re-send the registration via #359's sender using the persisted token and the live device name. It is **dormant** until a token is stored (by Firebase #361 via #364): with no stored token the hook sends nothing.

This slice also **closes the #359 `device_name: ""` defer.** #359 left `RemoteConversationRepository`'s `deviceName` param defaulted to `""` and flagged that whichever slice adds the live caller must thread the real name — else the first real registration sends `device_name: ""` and pollutes the server's dedup triple (QMD `knowledge/codebase/250.md`: `Name` is part of the dedupe key, so an empty name forks the registry entry). This is the only Phase 4 FCM slice that touches coordinator construction, so it owns the threading.

## Design

Four production edits + one test file. No new files, no new types.

### 1. Expose pump lifecycle `state` on the `ManagedSessionPump` interface

`SessionPump.kt` — add to the **`ManagedSessionPump`** interface (the coordinator's richer lifecycle view), *not* `SessionPump`:

```kotlin
/** The pump's lifecycle state; the coordinator awaits Open before sending the connect-time
 *  push-token re-registration, and aborts on a pre-Open Closed. */
val state: StateFlow<PumpState>
```

Add imports: `kotlinx.coroutines.flow.StateFlow` and `de.pyryco.mobile.data.network.PumpState`. Keeping `state` on `ManagedSessionPump` (not `SessionPump`) means `RemoteConversationRepository` (consumes `SessionPump`) is untouched — only the coordinator gains visibility.

`NoiseSessionPump.kt:62` — add `override` to the already-public `val state`. No behavioural change (it backs lifecycle today).

### 2. Two defaulted constructor params on `RelayRepositoryCoordinator`

```kotlin
class RelayRepositoryCoordinator(
    private val connections: StateFlow<RelayTransport?>,
    private val createPump: (RelayTransport) -> ManagedSessionPump,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val deviceName: String = "",
    private val pushToken: suspend () -> String? = { null },
)
```

The defaults keep the existing test setup and the single production construction site compiling. `pushToken` is a `suspend () -> String?` (a thunk, not a `Flow`) so the coordinator stays decoupled from `AppPreferences` and DataStore — the lambda is supplied by AppModule.

### 3. Thread `deviceName` + launch the connect-time hook in `onConnection`

`onConnection` stays **non-suspending** (do not violate the `:69-75` invariant). Capture the concrete repo in a local `val`, assign it to `currentRepository`, then `childScope.launch { … }` the hook — `launch` returns immediately; all suspending work runs on the child scope, off the critical path.

```kotlin
private fun onConnection(transport: RelayTransport?) {
    teardownActive()
    if (transport == null) return
    val childScope = CoroutineScope(SupervisorJob(job) + dispatcher)
    val pump = createPump(transport).also { it.start() }
    active = Connection(pump, childScope)
    val repo = RemoteConversationRepository(pump, childScope, deviceName)   // (a) thread deviceName
    mutableRepository.value = repo
    childScope.launch { reregisterPushTokenOnOpen(pump, repo) }            // (b) connect-time hook
}
```

Two merged constraints this satisfies:
- `registerPushToken` is **not** on the `ConversationRepository` interface and `currentRepository` is interface-typed, so the hook must use the **concrete** `repo` handle captured here — never an adjacent observer of `currentRepository`.
- The hook reuses the **one** `onConnection` collector + the pump's existing `state`; it does **not** add a second connection-state subscription or a second `currentRepository` observer.

The hook (private suspend helper). Signature + behavior, not a transcription:

```kotlin
private suspend fun reregisterPushTokenOnOpen(pump: ManagedSessionPump, repo: RemoteConversationRepository)
```

Behavior, in order:
1. Await the first transition out of `Handshaking`: `pump.state.first { it is PumpState.Open || it is PumpState.Closed }`. `StateFlow.first {}` checks the current value first, so an already-`Open` pump fires immediately (no missed-edge race).
2. If that terminal value is **not** `Open` (i.e. `Closed` before Open) → `return` (abort; nothing to register).
3. Read the token: `val token = pushToken() ?: return` — **no-op when null** (AC #2: dormant until Firebase stores a token).
4. Call `repo.registerPushToken(token)` exactly once, swallowing failure (see Error handling). The `Open → Closed` re-key path never revisits `Handshaking`, and each connection builds a fresh pump + child scope + hook, so this fires **exactly once per connection** (AC #1, AC #3).

`active.scope` owns the hook coroutine, so connection drop / `close()` cancels an in-flight registration — desirable (abort if the connection died mid-call).

### 4. Wire live values in `AppModule.kt`

At the coordinator construction site (`:76-82`):

```kotlin
RelayRepositoryCoordinator(
    connections = get<RelayConnectionSupervisor>().currentConnection,
    createPump = { transport -> NoiseSessionPump(transport, sessionFactory) },
    deviceName = get<NoiseClientInfo>().deviceName,
    pushToken = { get<AppPreferences>().pushToken.first() },
).also { it.start() }
```

`AppPreferences` (`:47`) and `NoiseClientInfo` (`:50`) are already resolvable singletons. Add import `kotlinx.coroutines.flow.first`. `pushToken.first()` collects the current persisted value of the (non-completing) DataStore flow and cancels — the correct one-shot read.

## State + concurrency model

- **Single source of state** unchanged: `currentRepository: StateFlow<ConversationRepository?>` remains the coordinator's only outward seam. The hook reads `pump.state` and the concrete `repo`; it publishes nothing new.
- **Scope ownership:** the hook runs on the per-connection `childScope` (`SupervisorJob(job) + dispatcher`), the same scope stored in `active` and cancelled by `teardownActive()`. A failed/cancelled hook cannot affect sibling work (SupervisorJob) and cannot outlive its connection.
- **Dispatcher:** the coordinator's `dispatcher` (prod: `Dispatchers.Default`; test: `StandardTestDispatcher`). No new dispatcher.
- **Exactly-once:** guaranteed structurally — one fresh hook coroutine per `onConnection`, awaiting one `Handshaking →` transition, sending at most one frame. No client-side dedup (the server dedupes the triple).
- **No new hot/cold flows.** `pump.state` is an existing hot `StateFlow`; `AppPreferences.pushToken` is an existing cold flow read once via `first()`.

## Error handling

| Failure | Surfacing | Hook behavior |
|---|---|---|
| No token stored (`pushToken()` returns `null`) | none | `return` before sending (AC #2) — not an error |
| Pre-Open `Closed` (handshake fault, transport Down) | `pump.state` = `Closed` | `return` after the await — never sends |
| Server `error` reply | `registerPushToken` throws `RelayErrorException` | **swallowed** (AC #4): daemon retries on next connect by contract |
| Session not Open at send time | `registerPushToken` throws `IllegalStateException` | **swallowed** (AC #4) |
| Connection dropped mid-call | `childScope` cancelled → `CancellationException` | **re-thrown**, not swallowed (see below) |

Swallow with a narrow catch that **re-throws `CancellationException`** so connection-drop cancellation isn't absorbed (absorbing it would break structured concurrency on teardown):

```kotlin
try {
    repo.registerPushToken(token)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    // swallowed — the daemon re-registers on the next connect by contract.
}
```

**No logging anywhere in the hook** — preserves the coordinator's documented "emits no logs" posture (`:39`) and the AC #4 requirement that the token is never logged. Do not log the swallowed exception (its message carries no token today, but the no-log rule is the durable invariant). Import `kotlin.coroutines.cancellation.CancellationException` (or `kotlinx.coroutines.CancellationException`).

## Testing strategy

Unit only (`./gradlew testDebugUnitTest`), JVM, hand fakes, no MockK / Firebase / device — mirroring the existing `RelayRepositoryCoordinatorTest`. Run a single class with `./gradlew testDebugUnitTest --tests "de.pyryco.mobile.data.repository.RelayRepositoryCoordinatorTest"` (bare `test --tests` fails — `test` is the aggregate task).

**Fixture changes:**
- `FakeManagedPump`: add `override val state: StateFlow<PumpState>` backed by a `MutableStateFlow(PumpState.Handshaking)`, plus a helper to drive it (e.g. `fun open(connId: String = "c1")` setting `Open`). Default `Handshaking` keeps the 6 existing tests green — they never drive `state` to `Open` and `newEnv`'s default `pushToken = { null }` makes the hook a no-op, so `pump.sent.single()` assertions stay valid (no register frame appears).
- `newEnv`: add `deviceName: String = ""` and `pushToken: suspend () -> String? = { null }` params, forwarded to the coordinator constructor.

**New scenarios (bullet form — developer writes the bodies in the project idiom):**
- **AC #1 — token stored, on Open, registers once with device name.** `newEnv(deviceName = "Pixel-8", pushToken = { "fcm-tok" })`; connect; drive `pump.open()`; `runCurrent()`. Assert `pump.sent.single { it.type == "register_push_token" }.payload` equals `{"platform":"fcm","token":"fcm-tok","device_name":"Pixel-8"}`. (Asserts device_name is the live value, never `""`.) Push `ack(sent.id)` so the awaiting coroutine completes cleanly before `close()`.
- **AC #2 — no token stored is a no-op.** `newEnv(pushToken = { null })` (or default); connect; `pump.open()`; `runCurrent()`. Assert no `register_push_token` in `pump.sent` and the coordinator still publishes a non-null repo (no error).
- **AC #3 — reconnect re-registers, once per connection.** Drive connection 1 to `Open` with a token → one register frame on pump 1; drop (`connections.value = null`); reconnect over a fresh transport, drive pump 2 to `Open` → one register frame on pump 2. Assert each pump sent exactly one `register_push_token` (proves fresh-hook-per-connection, not a leaked single-fire).
- **AC #4 — registration failure does not crash or wedge.** Connect; `pump.open()`; `runCurrent()`; read `sent.id`; `push(errorEnvelope(sent.id, code = "server.binary_busy", retryable = true))`; `runCurrent()`. Assert no exception escaped, `currentRepository.value` is still non-null, and a subsequent drop/reconnect still works (connection not wedged).
- **(Optional) pre-Open Closed aborts.** Connect; drive `pump.state` straight to `Closed(null)` without `Open`; `runCurrent()`. Assert no `register_push_token` sent.

Keep every test ending in `coordinator.close()` so the perpetual `connections.collect` doesn't hang `runTest`.

## Security considerations (security-sensitive ticket)

See the dedicated pass below. In brief:
- **Token confidentiality.** The FCM token is a wake-target secret. The hook never logs it or the swallowed exception; it flows only into #359's `registerPushToken`, which already encodes it over the authenticated Noise_IK session and never logs it. No new sink.
- **Dedup-triple integrity.** Threading the live `deviceName` (Build.MODEL) closes the `device_name: ""` defer; an empty name would fork the server's `(platform, token, device_name)` registry entry. Tested by AC #1's exact-payload assertion.
- **Trust boundary.** Both inputs are device-local (DataStore token, `Build.MODEL`); the ack/error reply arrives over the IK-authenticated session. No untrusted network input enters the hook. No change to the key-wipe / single-use-pump invariants — the hook only *reads* `pump.state` and calls an existing sender.

## Open questions

- None blocking. The hook deliberately does not retry on failure within a connection (the daemon's "re-register on next connect" contract is the retry); if observed registry drift later proves a single connect-time attempt insufficient, a bounded retry is a follow-up, not this slice (evidence-based: no such failure observed).

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries]** No findings. Both inputs are device-local: the token is read from app-private DataStore (`AppPreferences.pushToken`, #364) and `deviceName` is `Build.MODEL`. No network-controlled data enters the hook. The token is sent **only after** `pump.state` reaches `Open`, which by construction is *after* the Noise_IK handshake completes and the server's static key is verified — so the token never crosses to an unauthenticated peer. The ack/error reply arrives inside the AEAD-authenticated session, so it cannot be forged without breaking Noise. Awaiting `Open` (not `Handshaking`) is the load-bearing boundary guarantee, stated in Design §3.
- **[Tokens, secrets, credentials]** No MUST FIX. The FCM token is never logged: the hook emits no logs, `registerPushToken` (#359) is contractually no-log, the swallowed exception is not logged, and the coordinator's "emits no logs" posture (`RelayRepositoryCoordinator.kt:39`) is preserved (Error handling §). Token *generation* is Firebase's; *propagation on connect* (rotation story) is exactly what this slice adds; *revocation* is server-side via dedup of a rotated triple. Token storage-at-rest is **OUT OF SCOPE** (owned by merged #364 — plaintext app-private DataStore; an FCM registration token is a routing identifier, not an access credential, so plaintext app-private storage is the standard, accepted choice).
- **[File / storage operations]** N/A — this slice performs no filesystem operations (it reads the token through the DataStore abstraction and sends a frame). Auto-backup exclusion of the `app_prefs` DataStore file is **OUT OF SCOPE** (manifest/`dataExtractionRules` config, not touched here; owned by #364 / app-manifest config — a restored stale token is server-deduped and Firebase re-issues on the new device).
- **[Inter-process / Android attack surface]** N/A — no `Activity`/`Service`/`BroadcastReceiver`/deep-link/`PendingIntent`/`ContentProvider`/`WebView` is added or touched. Pure `data/`-layer coordination.
- **[Cryptographic primitives]** N/A — no new crypto. The hook reuses the established Noise_IK session via #359's sender; session crypto and key-wipe invariants (`NoiseSessionPump`/`NoiseIkSession`) are unchanged. The request `id` is an `AtomicLong` correlation counter, not a secret or nonce. No RNG in this slice.
- **[Network & I/O]** SHOULD FIX (non-gating). `registerPushToken` → `sendAndAwaitReply` (#359) awaits a correlated reply with no per-call timeout; if the server accepts the frame but never replies, the hook coroutine parks until disconnect. **Bounded and non-wedging:** it is a single leaf coroutine on `childScope`, holds no lock, blocks no other connection work (the data-path collectors are independent), and is cancelled by `teardownActive()` on drop. The developer *may* wrap the call in `withTimeoutOrNull` to bound it, but reply-timeout policy belongs to #359 (the ticket mandates reusing its sender unchanged), so this is a note, not a gate. The pre-`Open` await cannot hang: the pump's 10 s handshake timeout (`HANDSHAKE_TIMEOUT_MS`) guarantees a terminal `Closed` transition. The hook does **not** retry within a connection, so it cannot create a token-exhaustion / error-amplification loop against a failing daemon — retry is the supervisor's backed-off reconnect. TLS / cert-pinning / frame-size / timeouts are **OUT OF SCOPE** (transport layer, `OkHttpRelayTransport` #276, unchanged).
- **[Error messages, logs, telemetry]** No findings. No log/telemetry call anywhere in the hook; the swallowed `Exception` is discarded without logging; no token, payload, or header reaches any sink. Enforced by AC #4 and the coordinator's existing no-log posture.
- **[Concurrency]** No findings. The hook is owned by the per-connection `childScope` (`SupervisorJob(job) + dispatcher`), cancelled by `teardownActive()` — no application-scope leak. `CancellationException` is re-thrown (only `Exception` swallowed), preserving structured-concurrency teardown (Error handling §). `onConnection` stays non-suspending — `launch` schedules and returns, so the cancellation-atomicity / key-wipe invariant (`:69-75`) is intact. The hook neither check-then-mutates shared state nor adds a second `currentRepository`/inbound subscription. Exactly-once-per-connection holds structurally (one hook per `onConnection`, one `state.first {}` resolution, one send; `StateFlow` conflation + idempotent `start()` prevent duplicate fires).
- **[Threat model alignment]** No findings. The design implements protocol-mobile.md § Phone background behaviour (re-register on every connect; server dedupes `(platform, token, device_name)`). The one real integrity threat — an empty `device_name` forking the dedup triple (QMD `knowledge/codebase/250.md`) — is directly closed by threading the live `Build.MODEL` and asserted by AC #1's exact-payload test. No UI surface ⇒ screenshot/overlay/accessibility/deep-link mobile threats N/A.

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-06-06

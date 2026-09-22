# #841 — Report a rejected pairing as its own connection state

## Files read

- `app/src/main/java/de/pyryco/mobile/data/model/RelayLinkStatus.kt` → `RelayLinkStatus` — the sealed relay-leg type that gains the new case.
- `app/src/main/java/de/pyryco/mobile/data/network/RelayConnectionSupervisor.kt` → `runLoop`, `backoff`, `collapsibleWait`, `retry`, `connect`, `close`, `toConnectionState`, companion `RELAY_NO_DAEMON_CLOSE` — the `#308 seam` Down arm this ticket completes, the stale-retry drain (#498) the halt must reuse, and the legacy mapping.
- `app/src/main/java/de/pyryco/mobile/data/network/NoiseSessionPump.kt` → `drive`, `teardown`, `PumpState` — how a phone-side `noise_resp` verify failure surfaces (see Context).
- `app/src/main/java/de/pyryco/mobile/data/network/OkHttpRelayTransport.kt` → `close` — a local close terminates with `Down(code = WS_NORMAL_CLOSURE)`.
- `app/src/main/java/de/pyryco/mobile/data/network/RelayTransport.kt` → `TransportEvent.Down` — `code` is the peer WS close code, the HTTP status of a rejected upgrade, or `null`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConnectionStatusLine.kt` → `RelayLinkStatus.toLegVisual` — Settings status-line mapping.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationTreeRows.kt` → `RelayLinkStatus.isDisconnected` — host-row disconnected treatment and reconnect control (#840).
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/PairCodeViewModel.kt` → `persist` — the pair-code flow's terminal-status predicate, the fourth place the relay leg is classified.
- `app/src/main/java/de/pyryco/mobile/di/RelayConnectionRegistry.kt` → `pairingStatus`, `retryHost`, `reconcile`, `onForeground`/`onBackground` — the retry-once set, per-host routing of retry, and the foreground `connect()` / background `close()` pairing.
- `app/src/main/java/de/pyryco/mobile/lifecycle/LifecycleConnectionDriver.kt` — foreground `connect()` always follows a background `close()`; push-wake `connect()` is idempotent.
- `app/src/test/java/de/pyryco/mobile/data/network/RelayConnectionSupervisorTest.kt` → `nonDaemonClose_followsExistingReconnectPath_neverDaemonAbsent` (lists `4401` today and must drop it), `FakeRelayTransportFactory`, `newPairedSupervisor`.
- `docs/knowledge/features/relay-link-status.md`, `docs/knowledge/features/relay-reconnect-supervisor.md` — the #391 pattern (static data object, code compared against a constant, never logged) and the #498 drain placement lesson: the drain runs once per drop before the first wait, never inside the per-second wait.
- `../pyrycode/docs/protocol-mobile.md` § Noise_IK handshake → "Failure modes", "Token-validation gating" — the `4401` / `4426` contract.

## Design source

**Figma:** N/A — data-layer state. The only visible effect is a new label on the existing Settings connection-status line, drawn with the existing `Down` visual (`MaterialTheme.colorScheme.error` dot). Host-row and composer treatments are later tickets.

## Context

Today every relay close except `4404` goes through the uniform backoff. Per the protocol, a `4401` (token invalid/expired/revoked) and a `4426` (handshake failed: stale saved server static key) cannot recover without a new pairing, so redialling them forever presents a refused credential every ≤30 s and hides the real cause behind "Offline".

**Phone-side `noise_resp` verify failure — not distinguishable at the supervisor.** `NoiseSessionPump.drive` catches the `readResp` failure and calls `teardown(e)`, which records `PumpState.Closed(cause)` and calls `transport.close()`. The OkHttp transport's `close` terminates with `Down(code = 1000)`, the same code as every other local close. The pump's `cause` is a category-only `NoiseSessionException` shared with the handshake timeout, a wrong first frame, the re-key watchdog, and a malformed open-state frame, and the supervisor does not observe the pump at all. So this ticket classifies only the two peer close codes; the PR records that the phone-side case is not distinguishable. Making it distinguishable (a typed pump cause, or the phone closing with `4426` as the contract describes) is a separate ticket.

No ADR needed: this is the second branch of the existing `#308 seam`.

## Design

**`RelayLinkStatus`** gains `data object PairingRejected` — a static object carrying no relay-supplied data, like `DaemonAbsent`. KDoc: the host refused this pairing's credential (`4401` / `4426`); only a re-pair can recover; the supervisor has stopped redialling.

**`RelayConnectionSupervisor`**

- Companion constants `RELAY_TOKEN_REJECTED_CLOSE = 4401` and `HANDSHAKE_FAILED_CLOSE = 4426`.
- In the Down arm, a loop-local `pairingRejected` is set from `event.code == 4401 || event.code == 4426`, beside the existing `daemonAbsent` compare. Integer equality only; never logged.
- After the `finally` and the existing `attempt` bookkeeping: if `pairingRejected`, call a new `haltUntilRetry()` instead of `backoff(...)`; then the loop continues to the next dial as usual.
- `private suspend fun haltUntilRetry()`: drains stale retry signals, sets `state = PairingRejected`, then suspends on `retrySignal.receive()` with no timeout. It consumes no `Random`, so the jitter sequence for later backoffs is unaffected.
- The #498 drain loop is extracted into `private fun drainStaleRetrySignals()`, called at the top of both `backoff` and `haltUntilRetry` — same placement rule: once per drop, before the first wait.
- `attempt` is still incremented for a rejected drop; `retry()` still does not reset it (the anti-storm property holds if the redial then fails on the network).

Resume paths (AC 2):
- **Explicit retry** — `retry()` calls `connect()` (a no-op, loop is active) and `trySend(Unit)`; the halted `receive()` returns and the loop dials exactly once. If that dial is rejected again, it halts again.
- **Next foreground** — the registry and the lifecycle driver `close()` on background, which cancels the halted loop and sets `Idle`; the foreground `connect()` starts a fresh loop that dials once. A push-wake `connect()` while already foreground is an idempotent no-op and does not dial; this is the existing `connect()` contract, unchanged.
- **Re-pair** — a new record makes `RelayConnectionRegistry.reconcile` close this bundle and build a fresh one; untouched.
- Other hosts: each host owns its own supervisor instance and state; nothing is shared.

**`toConnectionState`**: `PairingRejected -> ConnectionState.Offline`.

**`toLegVisual`**: `PairingRejected -> ConnectionLegVisual(Down, "Pairing rejected", "Relay: pairing rejected")`.

**`isDisconnected`**: `PairingRejected -> true`, so the host row draws the disconnected treatment and its reconnect control, which routes through `retryHost` → `retry()` → one dial.

**`PairCodeViewModel.persist`**: `PairingRejected` joins `DaemonAbsent` / `Offline` in the terminal predicate, so a just-saved pairing that the host refuses fails immediately with the existing "Pairing saved. Host unavailable." message instead of waiting out the 30 s deadline. No new copy.

**`RelayConnectionRegistry.pairingStatus` — `PairingRejected` stays out of the retry-once set; no code change.** The set exists because a bundle's *first* status can be the stale terminal result of an attempt made before the flow subscribed. The bundle is looked up by the exact saved record, so an initial `PairingRejected` on it means this exact token and server key were refused; a retry re-presents the same credential and cannot succeed, and an automatic retry would defeat the halt. Any credential change replaces the record, and `reconcile` builds a fresh bundle that starts from `Idle`, so a stale rejection from an older credential can never reach this flow.

## State + concurrency model

No new jobs, scopes, or flows. The halt is a suspension inside the existing `loopJob` on the supervisor's own scope; `close()` cancels it (the `receive()` is cancellable). `retrySignal` stays `CONFLATED`. The halt holds no socket: the `finally` has already released the transport and compare-and-cleared `liveConnection` before the halt begins.

## Error handling

The new state is the error surface: a typed, static `RelayLinkStatus`. No exception crosses layers; no new log line. The close `code`, `reason`, and `cause` are never logged or stored.

## Testing strategy

Unit tests only (`./gradlew testDebugUnitTest`); no UI test, because the visible effect is a label in an existing mapping already covered by JVM tests. No rung-3 scenario: this is not an operator happy-path flow, and a real rejection needs a revoked token on the host.

`RelayConnectionSupervisorTest`:
- `4401` and `4426` each → `relayStatus == PairingRejected`, legacy `state() == Offline`; after advancing well past the 30 s cap, no further transport was created.
- A `retry()` issued while `Connected`, before the rejecting close, does not pre-collapse the halt (drain reused).
- `retry()` while halted → exactly one new dial (`Connecting`); a second rejection on it halts again with no further dial.
- `close()` then `connect()` while halted → exactly one new dial.
- Two supervisors: one rejected and halted, the other on `1006` keeps its normal reconnect countdown.
- `nonDaemonClose_followsExistingReconnectPath_neverDaemonAbsent` drops `4401` from its list and gains the neighbouring codes `4400` and `4427`, which still reconnect.
- `toConnectionState` maps `PairingRejected` to `Offline`.

`ConnectionStatusLineTest`: `PairingRejected` → `Down`, "Pairing rejected", "Relay: pairing rejected".
`RelayLinkDisconnectedTest`: `PairingRejected` reads disconnected.
`PairCodeViewModelTest`: a `PairingRejected` status after save ends `Connecting` immediately with the "Pairing saved" error.

## Documentation handoff

Pending for the documentation stage:
- `docs/knowledge/features/relay-link-status.md` — the type listing, the "4401 halt/re-pair is out of scope" note in § Security, and the mapping to `PairingRejected`.
- `docs/knowledge/features/relay-reconnect-supervisor.md` — the state machine, the relay-leg → banner table, and the halt/resume paths.
- `docs/knowledge/features/connection-status-line.md` — the new `toLegVisual` row.

## Open questions

- None blocking. The phone-side `noise_resp` failure is recorded above as not distinguishable.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings — the relay-controlled close `code` crosses into trusted state only at the Down arm's two integer comparisons in `runLoop`, beside the existing `4404` compare. `null`, HTTP statuses on a rejected upgrade, and every other code cannot reach `PairingRejected`. `reason` and `cause` never flow into the model; `PairingRejected` is a static object with no daemon-authored text.
- [Trust boundaries / hostile relay] Accepted, no fix — a hostile relay can forge a `4401`/`4426` close and halt redial for that host. It could already deny service by dropping or refusing connections; the halt is bounded by the next explicit retry or the next foreground, and the design deliberately does **not** delete or mutate the saved pairing on rejection, so a forged code can never destroy a credential. `PairedServerStore` is not touched.
- [Tokens] No findings — no token is read, stored, rotated or revoked here. Revocation propagates from the daemon as the `4401` the design now honours by stopping re-presentation of the refused token (the halt reduces token re-presentation from every ≤30 s to one per explicit user action or foreground).
- [File / storage] N/A — no file or storage operation is added.
- [Android surface] N/A — no intent, deep link, pending intent, push path, provider or WebView.
- [Crypto] N/A — no primitive is touched; the Noise session and its `4426` detection stay in `NoiseIkSession` / the daemon.
- [Network & I/O] No findings — the halt is strictly less dialling than today. The resumed dial after `retry()` keeps the escalated `attempt`, so a rejection followed by network failures cannot restart backoff at 1 s. Rapid retry taps collapse to one pending wake-up through the `CONFLATED` channel, and each rejected dial halts again, so taps cannot become a dial storm faster than the user taps.
- [Logs] No findings — the supervisor's no-log contract holds; no new log line. `PairCodeViewModel` reuses its existing static `unavailable` log code.
- [Concurrency] No findings — the halt suspends inside the existing `loopJob`, cancelled by `close()`. The transport is released and `liveConnection` compare-and-cleared before the halt, so a halted host holds no socket. The drain before the halt stops a retry issued during `Connected` from silently skipping the halt (#498 shape). A retry arriving during the halt is received, not lost, because the loop is still active and `connect()` does not start a second loop.
- [Threat model] OUT OF SCOPE — phone-side detection of a stale server key (`noise_resp` MAC failure) is not distinguishable at the supervisor today (see Context); a follow-up ticket would type the pump's teardown cause or close with `4426`. Surfacing the re-pair affordance on the host row and composer is the follow-up tickets split from #675.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-23

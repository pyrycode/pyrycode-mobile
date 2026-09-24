# #1008 — Stop redialling a host that rejects the app as too old

## Files read

- `app/src/main/java/de/pyryco/mobile/data/model/RelayLinkStatus.kt` → `RelayLinkStatus` — the sealed relay leg; `PairingRejected` (#841) is the terminal-state precedent the new case sits beside.
- `app/src/main/java/de/pyryco/mobile/data/network/RelayConnectionSupervisor.kt` → `runLoop`, `haltUntilRetry`, `drainStaleRetrySignals`, `toConnectionState`, the close-code constants — where a `4412` close is classified and the redial halted.
- `app/src/main/java/de/pyryco/mobile/data/network/NoiseSessionPump.kt` → `onOpenFrame` — the single point that decrypts every open-state `noise_msg`; the only place the sealed `error` is visible before it enters the single-consumer `inbound`.
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireModels.kt` → `ErrorPayload` — gains the optional `min_client_version`.
- `app/src/main/java/de/pyryco/mobile/di/RelayConnectionFactory.kt` → `RelayConnectionBundle` — builds supervisor, pump factory and coordinator per host; the wiring point between pump and supervisor.
- `app/src/main/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinator.kt` → `onConnection`, `createPump` — the pump is built per live transport, the same object the supervisor dialled; the coordinator never reads `inbound` itself.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` → the `TYPE_ERROR` arm — an `error` with no matching `in_reply_to` is already a no-op, so forwarding the rejection error stays harmless.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConnectionStatusLine.kt` → the relay-leg visual mapping (`PairingRejected` → Down, "Pairing rejected").
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationTreeRows.kt` → `relayLinkDisconnected` — the host row's disconnected treatment.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt` → the tree row's `onReconnectTapped` — only `PairingRejected` routes to re-pair, so the new state keeps dispatching `TreeHostReconnectTapped` (a retry) with no change, as the ticket requires.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/PairCodeViewModel.kt` → `persist` — the connection wait's terminal predicate.
- `app/src/main/java/de/pyryco/mobile/di/RelayConnectionRegistry.kt` → `pairingStatus` — its initial-retry rule names `DaemonAbsent` / `Offline` only; the new state is left out, like `PairingRejected`.
- `../pyrycode/docs/protocol-mobile.md` § Compatibility → "The app-too-old rejection", § Error codes (`client.update_required`, `4412`), § Connection lifecycle (token-failure shape `noise_resp` → sealed `error` → close) — the wire contract; not restated here.
- `app/src/test/java/de/pyryco/mobile/data/network/RelayConnectionSupervisorTest.kt` → the #841 `rejectedPairing*` tests — the shape the new supervisor tests mirror.
- `app/src/test/java/de/pyryco/mobile/data/network/NoiseSessionPumpTest.kt` → `Fixture`, `openSession`, `noiseMsg` — real-IK-responder fixture for sealed inbound frames.

In-flight overlap: `feature/878` edits `ConversationTreeRows.kt` (the conversation row's dot), not `relayLinkDisconnected`. Not a dependency; my edit there is one added `when` arm.

## Design source

**Figma:** N/A — data-layer state. The only visible effect is a new label on the existing connection-status line, drawn with the existing "down" visual (the #841 precedent). The host-row treatment with the minimum and a Play Store button is a separate ticket needing its own frame. Visual-fidelity check intentionally skipped.

## Context

pyrycode#2576 reserved the app-too-old rejection: after the handshake the daemon sends a sealed `error` `client.update_required` (`retryable: false`, optional `min_client_version`), then asks the relay to close with `4412`. Today the supervisor treats `4412` as a generic close and redials forever. This ticket makes it a per-host terminal state like `PairingRejected`, carrying the minimum when the sealed error supplied a valid one. No daemon sends it yet; everything is proven with fakes.

## Design

### `RelayLinkStatus.UpdateRequired`

```kotlin
data class UpdateRequired(val minClientVersion: String?) : RelayLinkStatus()
```

Terminal, beside `PairingRejected`. `minClientVersion` is either `null` or a string that passed `validMinClientVersion` (below); the supervisor's `recordClientMinimum` is the only production code that builds it with a non-null value, and it validates there. Not a `data object`, so consumers match with `is`.

### Wire: `ErrorPayload.minClientVersion` + validator (`MobileWireModels.kt`)

- `ErrorPayload` gains `@SerialName("min_client_version") val minClientVersion: String? = null` (omitempty on the wire; decode-only type, so the default only matters for decode).
- `internal fun validMinClientVersion(raw: String?): String?` — returns `raw` only when it is exactly three `.`-separated parts of ASCII digits `[0-9]`, each 1–6 digits (so ≤ 20 chars total); anything else (null, empty, `v1.4.0`, `1.4`, `1.4.0.1`, `1.4.0-beta`, non-ASCII digits, overlong parts, surrounding whitespace, trailing newline) → `null`. Whole-string match (`Regex.matches`), so no partial or multiline acceptance.
- `internal const val ERROR_CLIENT_UPDATE_REQUIRED = "client.update_required"` beside it.

### Capture: `NoiseSessionPump`

- New constructor parameter `onClientMinimum: (String) -> Unit = {}` (after `dispatcher`, defaulted so every existing construction site is untouched).
- In `onOpenFrame`'s `noise_msg` arm, after a successful decrypt + parse and before forwarding: if `envelope.type == "error"`, a private `captureClientMinimum(envelope)` decodes `ErrorPayload` (catching `SerializationException` / `IllegalArgumentException` — a malformed error must not tear the session down, which the existing throw-on-bad-frame path would do), and when `code == ERROR_CLIENT_UPDATE_REQUIRED` and `minClientVersion` is present, invokes `onClientMinimum(raw)`. Otherwise it does nothing. The pump does not validate; the supervisor does, where the state is built.
- The envelope is still forwarded to `inbound` unchanged (the repository ignores an uncorrelated `error`). Not gated on `in_reply_to` or `retryable` — the code is the signal.
- No log.

### Halt: `RelayConnectionSupervisor`

- New constant `CLIENT_UPDATE_REQUIRED_CLOSE = 4412`.
- `Down` classification adds `updateRequired = event.code == CLIENT_UPDATE_REQUIRED_CLOSE`. The close **reason** is never read.
- After the dial: `pairingRejected` → halt with `PairingRejected`; else `updateRequired` → halt with `UpdateRequired(dialMinimum)`; else `backoff` as today.
- `haltUntilRetry` takes the halted state as a lambda (`halted: () -> RelayLinkStatus`) evaluated under `dialLock` (see below), keeping one halt path for both terminal states. Same drain-then-wait semantics (#498, #841).
- Per-dial minimum latch, guarded by a private `dialLock`:
  - `dialTransport: RelayTransport?` and `dialMinimum: String?` — set to (the new transport, `null`) right after `transportFactory.create`, so every dial starts with no minimum.
  - `internal fun recordClientMinimum(transport: RelayTransport, minClientVersion: String)` — first `validMinClientVersion(minClientVersion)`; an invalid value is dropped (treated as absent). Then under `dialLock`: ignore unless `transport === dialTransport` (a stale connection cannot write into a later dial); store `dialMinimum`; if `state.value is UpdateRequired`, replace it with `UpdateRequired(minClientVersion)`. This handles both orders: minimum decrypted before the `4412` Down is processed (picked up by the halt), or after (upgrades the already-halted state). It never *enters* `UpdateRequired` — only a `4412` does (AC 1), so the error alone never halts.
  - The halt's state write happens inside `dialLock`, so a concurrent `recordClientMinimum` cannot fall between reading `dialMinimum` and publishing the state.
- `toConnectionState`: `is UpdateRequired -> ConnectionState.Offline`.
- KDoc no-log contract extended: `4412` compared, never logged; the minimum never logged.

### Wiring: `RelayConnectionBundle`

`createPump = { transport -> NoiseSessionPump(transport, sessionFactory, dispatcher, onClientMinimum = { supervisor.recordClientMinimum(transport, it) }) }`. The closure binds each pump to its own transport, which is the identity the supervisor dialled (the coordinator builds a pump per `currentConnection` value, published by the same supervisor). Per-host isolation holds structurally: each bundle has its own supervisor, pump factory and coordinator.

### UI

- `ConnectionStatusLine` relay mapping: `is RelayLinkStatus.UpdateRequired -> ConnectionLegVisual(ConnectionLegCategory.Down, "Update required", "Relay: update required")`. The minimum is not shown (follow-up ticket).
- `relayLinkDisconnected` (`ConversationTreeRows.kt`): `is RelayLinkStatus.UpdateRequired` → `true`.
- `PairCodeViewModel.persist`: `it?.relay is RelayLinkStatus.UpdateRequired` is terminal; on it, `fail("This app is too old for this host. Update the app, then retry.", "update_required")` instead of the generic "Host unavailable" copy, since retrying without an update cannot help. Log carries the static code only.
- `ChannelListScreen`, `RelayConnectionRegistry.pairingStatus`, `AppModule.pairingRejected`: unchanged (see Files read).

## State + concurrency model

- No new coroutines or scopes. `recordClientMinimum` runs on the pump's scope (per-connection, cancelled by teardown); it is synchronous and non-suspending.
- `state` stays the supervisor's single `MutableStateFlow`; the new writes happen under `dialLock`, the loop's other writes are unchanged. A `close()` → `Idle` concurrent with a late `recordClientMinimum` is harmless: the record only rewrites a state that `is UpdateRequired`.
- Halt exit: `retry()` → one dial (state `Connecting`, a fresh dial clears the latch); `close()` cancels the wait, next `connect()` starts a fresh loop and dials — so a relaunch after updating the app dials normally.
- Race acknowledged: `4412` may be processed before the pump decrypts the error, and the coordinator's teardown of the pump on `currentConnection = null` can cancel the pump before it reads a buffered error frame. The state then stays `UpdateRequired(null)` — the ticket's "no minimum" case, correct but less informative. Acceptable: the minimum is best-effort by contract.

## Error handling

- Malformed or non-`ErrorPayload` `error` payload → no capture, session unaffected, envelope still forwarded.
- Invalid `min_client_version` → treated as absent.
- `4412` with no readable error (daemon seal failed; or `readResp` failed and the pump never opened) → `UpdateRequired(null)`.
- No new user-facing error surface beyond the status label and the pair-code failure copy.

## Testing strategy

Unit tests (`testDebugUnitTest`), fakes only:

- `RelayConnectionSupervisorTest`
  - `4412` close → `UpdateRequired(null)`, `ConnectionState.Offline`, no redial after 10 min, even with a stale pre-drop `retry()`.
  - minimum recorded for the live transport before the Down → `UpdateRequired("1.4.0")`.
  - minimum recorded after the halt → state upgrades to carry it.
  - minimum recorded for a previous dial's transport → ignored.
  - minimum recorded with a non-`4412` close → reconnects as today (error alone does not halt).
  - explicit `retry()` dials once; a repeat `4412` halts again with the latch cleared (no carry-over minimum).
  - `close()` + `connect()` dials once.
  - one host in `UpdateRequired`, another keeps redialling.
  - `toConnectionState` maps `UpdateRequired` → `Offline`.
- `NoiseSessionPumpTest`: sealed `client.update_required` with a minimum → callback gets the raw value and the envelope is still forwarded; absent minimum / other code / malformed payload → no callback, pump stays `Open`.
- supervisor: an invalid recorded minimum (e.g. `"1.4"`) leaves `UpdateRequired(null)`.
- `ErrorPayload` / validator test (new `ErrorPayloadTest` under `data/network`): decode with and without `min_client_version`; validator accept/reject table.
- `ConnectionStatusLineTest`: `UpdateRequired` → Down, "Update required".
- `RelayLinkDisconnectedTest`: `UpdateRequired` → disconnected.
- `PairCodeViewModelTest`: `UpdateRequired` fails immediately (before the 30 s deadline) with the update copy.
- Bundle wiring (pump → supervisor) is covered by composition: the closure is one line; the two halves are each proven. No rung-3/4 scenario: no daemon sends `4412` yet, and this is not an operator-happy-path flow.

## Open questions

- Does the daemon's rejection `noise_resp` carry a `hello_ack` that `NoiseIkSession.readResp` accepts? If not, the pump never opens and the minimum is never read; the halt still happens with no minimum. The protocol's token-failure text says the binary "still sends the `noise_resp` so the AEAD channel exists". Resolution for this ticket: design tolerates either; the daemon ticket that wires the sender confirms it.

## Documentation handoff

None named by the ticket. Pending for the documentation stage: the relay-connection / connection-status feature overview should gain the `UpdateRequired` terminal state beside `PairingRejected` (the `4412` halt, the per-dial minimum latch and its best-effort ordering).

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings after revision. Two untrusted inputs: the WS close code (relay-forwarded, forgeable by an on-path relay) and the sealed `min_client_version` (daemon-authored, authenticated by the Noise session). The close code is only compared to `CLIENT_UPDATE_REQUIRED_CLOSE`; the close reason is never read. The minimum is decoded in `NoiseSessionPump`'s `captureClientMinimum` only after a successful AEAD decrypt and envelope parse, and validated in exactly one place, `RelayConnectionSupervisor.recordClientMinimum` via `validMinClientVersion`, which is the only production path that builds `UpdateRequired` with a non-null value. The first draft validated in the pump and passed a bare `String` to the supervisor, which left the "validated" contract unenforced at the state constructor; revised so the state-building site validates. The accepted shape (three 1–6 digit ASCII parts) is safe to render as text in the follow-up UI ticket.
- [Trust boundaries / hostile relay] Accepted risk, same as the #841 `4401` precedent: a hostile relay can forge a `4412` close and park the host at "Update required" until an explicit retry or the next foreground. It cannot forge a minimum (the minimum only arrives inside the AEAD session, and only for the same dial by transport identity), and it can already deny service by dropping frames; halting reduces dial load rather than adding any.
- [Tokens, secrets] No findings — no token, key, or credential path is touched.
- [File / storage] No findings — nothing is persisted. The state is in-memory per bundle, so a relaunch after updating the app dials normally.
- [Android attack surface] No findings — no intents, deep links, pending intents, providers, or WebViews.
- [Cryptographic primitives] No findings — the decrypt/parse path in `onOpenFrame` is unchanged; capture reads an already-decrypted envelope and cannot touch nonces or keys. `captureClientMinimum` catches its own decode failures, so a malformed `error` payload cannot trip the existing teardown-on-bad-frame path (which would otherwise make a malformed error a session killer it is not today).
- [Network & I/O] No findings — backoff for every other close is unchanged; the halt removes a redial loop. The `error` alone never halts (only `4412` does), so there is no new halt trigger reachable from inside the session. The envelope is still forwarded to the repository, which already ignores an uncorrelated `error`.
- [Logs] No findings — no new log of the close code or the minimum. The only new log is `PairCodeViewModel`'s existing `pair_code_failed` event with the static code `update_required`. `UpdateRequired`'s data-class `toString` would include the minimum, but no log site formats a `RelayLinkStatus` (checked: no `RelayLog` call interpolates a relay status); the verifier should keep it that way.
- [Concurrency] No findings — `dialLock` guards `dialTransport`, `dialMinimum` and the two `UpdateRequired` writes; no suspension happens inside it (`retrySignal.receive()` stays outside). A late `recordClientMinimum` from a previous dial is rejected by transport identity; one arriving after `close()` only rewrites a state that `is UpdateRequired`, so it cannot resurrect a halted state over `Idle` or `Connecting`.
- [Threat model] OUT OF SCOPE — rendering the minimum and a Play Store action (the follow-up host-row UI ticket, split from #1004, needing its own Figma frame) must render the validated value as text only. The daemon-side sender is pyrycode's later ticket after #2576.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-24

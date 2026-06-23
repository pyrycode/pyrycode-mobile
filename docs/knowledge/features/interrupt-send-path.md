# Interrupt send path — the bare v2 `interrupt` control frame (remote Esc)

The phone's **outbound send path** for stopping a running turn remotely — the wire half of pressing **Esc**.
Phase 3 (epic pyrycode#597) lets a paired phone interrupt the supervised claude; the server side shipped in
pyrycode#707, where the daemon maps an inbound `interrupt` control frame to the neutral `turnevent.Cancel`
and routes it to claude as a single Esc keystroke. This is the phone half: a thin vertical slice
(repository → coordinator → DI → ViewModel action) landed in [#458](../codebase/458.md). It carries **no UI**
— the visible busy-state affordance that calls it is sibling **#459** (`blockedBy #458`).

It is the structural twin of the [`modal_cancel` outbound slice](modal-answer-flow.md)
([#451](../codebase/451.md)), with two departures the fire-and-forget wire contract forces: **plain
`pump.send`** (no awaited reply) and **inert empty-catch swallowing** (no error channel, no log).

## Wire contract (SSOT: pyrycode `docs/protocol-mobile.md` § Interrupt v2, pyrycode#707)

- `interrupt` — phone → binary (encrypted relay frame). **No reply, no ack, no broadcast.**
- **Bare frame.** `{type: "interrupt"}` with **no payload**: no `conversation_id`, no `interrupt_id`, no
  nonce, no idempotency key. (Contrast modals, which carry `modal_id` + an `answer_token`.) No payload DTO.
- **Replay-safe.** A replayed `interrupt` sends another Esc; an Esc with no running turn is a no-op in
  claude. Claude serialises turns ⇒ at most one running turn ⇒ a bare connection-level frame is
  unambiguous. Not part of the reconnect-replay ring; needs no correlation key.
- **Interactive-capability-gated server-side.** A non-interactive connection's `interrupt` is dropped by the
  daemon. The phone already advertises `interactive` in its `hello` ([#401](../codebase/401.md)).
- **Permission-gate-exempt** — interrupting one's own paired session is a normal paired action.

## The path

```
ThreadViewModel.onInterrupt()           # the action #459's affordance calls (no args, no guard)
   └─ sendInterrupt()                    # viewModelScope.launch { try { interrupt() } catch … }  (empty catches)
       └─ interrupt: suspend () -> Unit  # DI-injected lambda = coordinator::interrupt (defaulted no-op {})
           └─ RelayRepositoryCoordinator.interrupt()      # null-guard passthrough → throws ISE if no connection
               └─ RemoteConversationRepository.interrupt() # check(pump.send(interruptRequest())) — fire-and-forget
                   └─ pump.send(Envelope{type:"interrupt", payload:{}})  # over the Open Noise_IK session
```

- **`RemoteConversationRepository.interrupt()`** — `check(pump.send(interruptRequest()))`. Plain `pump.send`
  (Boolean), **not** [`sendAndAwaitReply`](remote-conversation-repository.md) (the daemon sends no reply;
  awaiting one would hang). `interruptRequest()` builds a bare `Envelope` with `payload =
  JsonObject(emptyMap())` (the `listConversationsRequest()` empty-payload precedent). The `check` throws
  `IllegalStateException` when the pump is not `Open`, reusing the not-connected idiom so the caller can
  swallow it. Companion const `TYPE_INTERRUPT = "interrupt"`. See
  [Remote conversation repository § `interrupt()`](remote-conversation-repository.md).
- **`RelayRepositoryCoordinator.interrupt()`** — the exact `cancelModal` mirror: `activeRemoteRepo.value ?:
  throw IllegalStateException("no active connection")`, then `repo.interrupt()`. Null-guard only; never logs.
  See [Relay repository coordinator § Outbound interrupt passthrough](relay-repository-coordinator.md#outbound-interrupt-passthrough-458).
- **DI** — `AppModule` binds `interrupt = coordinator::interrupt` in the `ThreadViewModel` factory, alongside
  `answerModal`/`cancelModal`. No new Koin binding (fetched off the concrete coordinator singleton).
- **`ThreadViewModel`** — a defaulted `interrupt: suspend () -> Unit = {}` ctor param (the VM holds only the
  lambda, never the coordinator/concrete repo), a public `onInterrupt()` action, and a private
  `sendInterrupt()` launcher. `onInterrupt()` takes **no args** and has **no guard** (unlike `onModalCancel`,
  there is no per-VM state to gate on — it always attempts the send; the server is authoritative on whether a
  turn is running). Show/hide gating of the affordance is #459's.

## Design decisions

- **Fire-and-forget, not request/reply.** No ack to await ⇒ plain `pump.send`. This is the one repo-layer
  difference from the `cancelModal` template.
- **Connection-level — no `conversationId` in the send path.** The action lives on the per-conversation
  `ThreadViewModel` (where #459's busy turn is visible), but the frame stays bare and connection-level
  ("the one running turn").
- **Always send; no client-side `interactive` suppression.** The gate is server-authoritative and
  fail-closed. A minimal client sends an Esc-only frame; the daemon ignores it on a non-interactive
  connection.
- **Inert on failure — empty catch bodies, no error channel, no log.** `sendInterrupt()` is the `sendCancel`
  twin but swallows `IllegalStateException` (not-connected / pre-`Open`) and `RelayErrorException`
  (unreachable on the real fire-and-forget path — retained for parity + the AC #4 test, documented in KDoc).
  The `catch (CancellationException) { throw e }` **precedes** the typed catches so structured cancellation
  is preserved (`j.u.c.CancellationException extends IllegalStateException` on the JVM —
  [[catch-illegalstate-swallows-cancellation]]).

## Error handling

| Failure | Surfaces at | Result |
|---|---|---|
| No active connection (`activeRemoteRepo == null`) | coordinator `interrupt()` | `IllegalStateException` → VM swallows → **inert** |
| Connected but pump pre-`Open` (`pump.send` → `false`) | repo `check(pump.send(...))` | `IllegalStateException` → VM swallows → **inert** |
| Relay/server `error` | not reachable (no reply awaited) | catch retained for parity + AC #4 test → **inert** |
| `viewModelScope` cancelled mid-send | `CancellationException` | rethrown **before** typed catches → propagates (structured cancellation preserved) |

No banner, no dialog, no log, no error channel. The send fails silently inert.

## Security

`security-sensitive`, architect § Security review **PASS** + code review confirmed. Outbound-only with no
untrusted parse (zero-arg, compile-time-constant empty payload, no injection surface). `pump.send` transmits
only over an `Open` (authenticated Noise_IK) session — a pre-`Open`/null pump throws and the interrupt cannot
ride an unauthenticated channel. No token/nonce by design (replay-safe). Permission-gate-exempt
(interrupting one's own paired session is a normal paired action, pyrycode#707). Never-log. See
[#458 § Security](../codebase/458.md#security-security-sensitive).

## Related

- Ticket: [#458](../codebase/458.md) — files, line refs, the test-teeth lesson, full security walk.
- Mirror / template: [Modal answer flow](modal-answer-flow.md) ([#451](../codebase/451.md), the `modal_cancel`
  outbound slice this is the twin of); the concrete sends [#438](../codebase/438.md).
- Hosts the send: [Remote conversation repository](remote-conversation-repository.md) (`interrupt()`);
  the passthrough: [Relay repository coordinator](relay-repository-coordinator.md).
- Consumer (downstream, **shipped**): [Interrupt affordance](interrupt-affordance.md)
  ([#459](../codebase/459.md), `blockedBy` #458) — the busy-turn interrupt control (Figma 16-8), its
  `isBusy` show/hide gating, and the AC#4 screen test. `onInterrupt()` (this slice) is the tap target.
- Placement counterpoint: [#466](../codebase/466.md) (`dropQueuedMessage` — the interface-method shape for a
  `conversation_id`-carrying frame; interrupt is the connection-level injected-lambda branch).
- Server SSOT: pyrycode#707, `docs/protocol-mobile.md` § Interrupt (v2), ADR 025, EPIC pyrycode#597.
</content>

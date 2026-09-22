# Interrupt send path — stop the open conversation

Stop carries the open thread's saved `conversationId` from `ThreadViewModel` to
the daemon in `interrupt.payload.conversation_id` (#626). The
[busy-turn affordance](interrupt-affordance.md) calls this path while the viewed
conversation is thinking or responding. Sending leaves local turn state unchanged;
the control disappears only when that conversation's inbound events clear `isBusy`.

## Wire contract

The authoritative contract is upstream's
[Interrupt (v2)](https://github.com/pyrycode/pyrycode/blob/main/docs/protocol-mobile.md#interrupt-v2).
Mobile sends a single payload field, `conversation_id`, encoded as a `JsonPrimitive`.
An empty payload lets the daemon's process-wide follow-active cursor choose the
conversation. Another device's activity can move that cursor, so Stop must name
the viewed conversation on every call.

The id is a daemon-validated lookup key, not authorization. A named target that
the daemon cannot act on is silently ignored, without falling through to another
conversation. The daemon enforces the `interactive` capability and paired-device
authorization; there is no extra client capability gate.

Interrupt has no synchronous ack or error reply. The client observes completion
through the conversation's existing turn events. There is no idempotency key or
reconnect replay: an interrupt with no running turn is a no-op.

## The path

```text
ThreadViewModel.onInterrupt()
  └─ sendInterrupt(): viewModelScope.launch { interrupt(conversationId) }
      └─ interrupt: suspend (String) -> Unit = coordinator::interrupt
          └─ RelayRepositoryCoordinator.interrupt(conversationId)
              └─ RemoteConversationRepository.interrupt(conversationId)
                  └─ pump.send(Envelope{type:"interrupt", payload:{conversation_id: B}})
```

- **ViewModel:** `onInterrupt()` takes no UI argument; `sendInterrupt()` supplies
  the id saved for this thread. The injected callback is defaulted to a no-op for
  fixtures. The action always attempts the send; visibility is governed by
  `isBusy`, while the daemon decides whether there is a running turn to stop.
- **DI and coordinator:** `AppModule` binds `interrupt = coordinator::interrupt`.
  The [passthrough](relay-repository-coordinator-seams-and-passthroughs.md#outbound-interrupt-passthrough-458)
  reads the active connection's concrete repository and forwards the id unchanged.
  The connection selects the transport, while the argument selects the conversation.
- **Repository:** [`interrupt(conversationId)`](remote-conversation-repository-control-sends.md#interruptconversationid--explicitly-targeted-v2-interrupt)
  makes one plain `pump.send(interruptRequest(conversationId))` call. Awaiting
  `sendAndAwaitReply` would hang because this verb has no reply.

## Design decisions

- Preserve the existing suspend callback seam. Adding a target does not require
  moving interrupt onto `ConversationRepository` or its stable facade; the
  ViewModel continues to hold only the callback.
- Do not optimistically clear `isBusy`, reset messages or claim success after a
  send. Both accepted and failed sends leave local state alone; inbound
  `turn_state`/`turn_end` events for the open conversation own the busy flag.
- Do not queue or retry the operation. Connection and send failures remain silent,
  with no snackbar, error channel or log.

## Error handling

| Failure | Boundary | Result |
|---|---|---|
| No active connection | Coordinator null guard | `IllegalStateException`, swallowed by the ViewModel |
| `pump.send` returns `false` | Repository `check` | `IllegalStateException`, swallowed by the ViewModel |
| Injected callback throws `RelayErrorException` | ViewModel | Swallowed; retained test seam, unreachable through the real fire-and-forget send |
| Send coroutine is cancelled | ViewModel | `CancellationException` is rethrown before the typed catches |

On the JVM, `CancellationException` extends `IllegalStateException`. Catch ordering
therefore matters even when failure handlers have empty bodies. The send stays
owned by `viewModelScope` and is cancelled on ViewModel teardown.

## Security

The id is serialized as JSON data without interpolation. Transport remains the
authenticated Noise session; the coordinator and repository never log the id or
payload. Supplying a conversation id does not bypass daemon authorization or its
`interactive` gate. See the [plan's security review](../../specs/architecture/626-explicit-interrupt-target.md#security-review).

## Testing

`ThreadViewModelTest`, `RelayRepositoryCoordinatorTest` and
`RemoteConversationRepositoryTest` assert B's target after distinct prior activity
in A, including exactly one callback/send for the Stop action. Repository coverage
supplies no reply fixture and preserves messages in both conversations; ViewModel
coverage preserves busy/thread state after success and failure. A single target
or a count-only recorder would miss a stale target passed from an earlier thread.

Teardown cancellation alone can pass even if a catch swallows cancellation: the
parent job is already cancelled and empty catches leave no visible side effect.
`onInterrupt_sendCancellationRemainsCancellation` throws cancellation from the
callback and checks the send job's completion cause, proving propagation.

The [affordance regression](interrupt-affordance.md#testing) checks idle absence,
thinking/responding visibility, the recorded target and continued visibility after
tapping until `turn_end`. These deterministic assertions and the existing curated
live suite do not prove the cross-device outcome. [#679](https://github.com/pyrycode/pyrycode-mobile/issues/679)
owns the pending rung-3 scenario in `InteractiveStreamE2ETest`: with real turns in A
and B and another device most recently using A, phone Stop while viewing B must end
B while A keeps running. See the [e2e coverage follow-ups](../../e2e-interactive-stream.md#follow-ups-to-ticket).

## Related

- [Interrupt affordance](interrupt-affordance.md) — visibility, rendering and tap wiring.
- [Remote control sends](remote-conversation-repository-control-sends.md) and
  [coordinator](relay-repository-coordinator.md) — transport and connection ownership.
- [Modal answer flow](modal-answer-flow.md) — the related suspend callback pattern.
- [Explicit Stop plan](../../specs/architecture/626-explicit-interrupt-target.md);
  [original send-path history](../codebase/458.md).

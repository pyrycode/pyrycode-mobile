# #1617: the status indicator returns after a mid-turn reconnect

## Files read

- `app/src/main/java/de/pyryco/mobile/data/repository/TurnPhaseProjection.kt`: `apply` and `observe`. A `StateFlow` of non-idle phases with `distinctUntilChanged`, so a late subscriber reads the held phase and a repeated phase emits nothing.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt`: the `TYPE_TURN_STATE` arm of the inbound demux. It gates on `interactive` only and never reads `Envelope.eventId`, so the reconcile frame (no `event_id`) reaches `turnPhaseProjection.apply`.
- `app/src/main/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinator.kt`: `onConnection` builds the repository right after `pump.start()`; the capability supplier reads `PumpState.Open`, which holds before any open-state frame is decrypted.
- `app/src/main/java/de/pyryco/mobile/data/network/NoiseSessionPump.kt`: `inboundChannel` is a `Channel.BUFFERED` fed by suspending `send`, so a frame decrypted before the repository's collector subscribes is held, not dropped.
- `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt`: `observeTurnPhase` goes through `switchToLive`, a `flatMapLatest` over `currentRepository`; the new repository's projection replays its current value to the switched collector.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt`: `isThinking` derives only from `observeTurnPhase`; `nextTurnOutcome` and `closeLocalSendWindow` read the live-event stream.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/RunSettingsRereads.kt`: `turnEndEdges`.
- `app/src/main/java/de/pyryco/mobile/di/ConversationAttention.kt`: `resolveAttention`.
- `../pyrycode/internal/relay/v2session_turnphasereconcile.go`: `reconcileTurnPhases`, called from `handleNoiseInit`'s success tail after `replayMissed`; `turn_state` per running turn, `EventID` nil, nothing for idle.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTurnPhaseTest.kt`: the unit test home.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/DeterministicInteractiveStreamE2ETest.kt`: `interactiveTurn_seededChannel_replySurvivesMidTurnReconnect`, `severAndRestoreLink`.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-1957

The input area's `Status area`: the existing "Thinking…" indicator above the composer. No visual change; only when the indicator shows changes, so the visual check is a no-op.

## Change

Production change is zero. Reading the path above, the daemon's reconcile `turn_state` lands in the new connection's projection: it is decrypted after the pump is `Open`, buffered until the new repository's collector reads it, applied regardless of `event_id`, and the thread's switched `observeTurnPhase` collector reads the projection's current value whether it subscribes before or after the frame. The ticket's other consumers each treat a running phase on a fresh connection correctly: `closeLocalSendWindow("turn_state")` closes a send window the daemon has now answered; `nextTurnOutcome` clears a recovery notice, right because the turn is still running; `turnEndEdges` marks the conversation running so the later `idle` fires its re-read; `resolveAttention` reads `Running`. No guards are added. The ticket adds the two proofs below. If the device scenario shows the indicator does not return, the fix lands here under `## Revisions`.

## Testing strategy

- Unit, `RemoteConversationRepositoryTurnPhaseTest`: a new test builds a fresh interactive repository, collects `observeTurnPhase("c1")` into a list, pushes the reconcile shape (`turn_state` thinking, `eventId = null`, envelope `id = 1`) as the connection's first frame, then pushes it again; asserts the emissions are exactly `[Idle, Thinking]`.
- Device, rung 4, `DeterministicInteractiveStreamE2ETest#interactiveTurn_seededChannel_replySurvivesMidTurnReconnect`: after `severAndRestoreLink` and before the second message, wait for the thinking content description and assert it displayed. Before the daemon fix the fresh projection read idle here, so the assertion fails without the reconcile frame: `isThinking` has no other source. KDoc updated. Run with `python3 scripts/android-test-gate.py scripted reconnect`.
- No rung-3 scenario: this is a reconnect-ordering fix the scripted twin holds open deterministically; a real-Claude turn cannot be held open across a sever reliably, and the live suite's reconnect coverage is unchanged.

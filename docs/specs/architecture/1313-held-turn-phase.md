# #1313 — Hold each conversation's turn phase in the repository

## Files read

- `data/repository/StallProjection.kt`, `CompactingProjection.kt` — the per-conversation projection shape to mirror: a private `MutableStateFlow` of per-conversation state, written only from the repository's inbound collector, read through `observe(conversationId)` with `distinctUntilChanged`.
- `data/repository/RemoteConversationRepository.kt` — the `interactive`-gated live-session arm in `onInbound` (`TYPE_TURN_STATE … TYPE_TURN_END`), where `stallProjection.clear` and `thinkingProgressProjection.clear` already ride each decoded `LiveSessionEvent`; the `observeStall` / `observeCompacting` overrides.
- `data/repository/ConversationRepository.kt` — `observeStall` / `observeCompacting` defaults (`flowOf(false)`) that spare the fakes an override.
- `data/repository/StableConversationRepository.kt` — `switchToLive(whenAbsent, select)`; `observeCompacting` routes through it.
- `data/model/LiveSessionEvent.kt` — `TurnState.Phase { Thinking, Responding, Idle }`, `TurnEnd`.
- `ui/conversations/thread/ThreadViewModel.kt` — `isThinking`, `isBusy`, `thinkingTransition`, `busyTransition`; `isStalled` / `isCompacting` as the repository-sourced siblings; `localSendPending` and `turnOutcome`, which keep reading `liveSessionEvents`.
- `sharedTest/.../ScriptedThreadHarness.kt` — real `RemoteConversationRepository` + `ThreadViewModel` + `ThreadScreen`; `pushResetting`'s `targetConversationId` is the precedent for scripting another conversation.
- `test/.../ThreadViewModelTest.kt` — `#406` / `#459` flag tests, `failedAndCancelledTurnEnds_clearIsThinkingAndIsBusy`, and the two `onInterrupt_*` tests that drive `isBusy` through `liveSessionEvents`; `StallControllableRepo` as the test-double shape.
- `test/.../StableConversationRepositoryTest.kt` — `observeCompacting_delegatesToLiveRepo_andDoesNotLeakAcrossSwitch` and `RecordingConversationRepository`.
- `test/.../RemoteConversationRepositoryRunReadingsTest.kt` — a small standalone real-repository test file with its own `FakeSessionPump`.
- `docs/knowledge/features/turn-state-thinking-flag.md` — records the "stale `true` on resume" as known and accepted; this ticket removes it.

Overlapping in-flight branches (#1314, #1329, #1337, #1342, #1343, #1346, #1348, #1351, #1353, #1355, #1359, #1361, #1410, #1411) touch the same files but none touch the turn flags; edits here stay additive and local.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Behaviour only: the `Status area` and the interrupt affordance render exactly as today. Only the source of `isThinking` / `isBusy` changes, so the visual check has nothing new to compare.

## Context

`isThinking` and `isBusy` are folded per `ThreadViewModel` from the `replay = 0` `liveSessionEvents`, under `WhileSubscribed(5_000)`. A chat opened mid-turn shows nothing until the next frame; a thread unsubscribed for over five seconds misses the frames that end or start a turn; nothing resets on a new connection. Desktop holds the phase per conversation outside the screen and resets it on reconnect. Mobile already holds stall, compaction and reset state that way in the connection-scoped repository, which a reconnect rebuilds. This ticket moves the turn phase there too. A decision record is not needed: it applies the established projection pattern.

## Design

**`TurnPhaseProjection`** (new, `internal`, `data/repository/`): one per `RemoteConversationRepository`.

- State: `MutableStateFlow<Map<String, LiveSessionEvent.TurnState.Phase>>`, holding only conversations whose phase is not idle. Absent means idle.
- `apply(event: LiveSessionEvent)`: `TurnState` sets that conversation's phase (`Idle` removes it); `TurnEnd` removes it, whatever its outcome; every other event is ignored. Writes are keyed by the event's own conversation id, so one conversation never moves another.
- `observe(conversationId): Flow<Phase>` — the held value, `Idle` when absent, `distinctUntilChanged`.

**`ConversationRepository.observeTurnPhase(conversationId): Flow<LiveSessionEvent.TurnState.Phase>`** — default `flowOf(Phase.Idle)`, so the fake and inline doubles need no override. Reuses the existing `Phase` enum; no new exported type.

**`RemoteConversationRepository`**: holds a `turnPhaseProjection`; the live-session arm calls `turnPhaseProjection.apply(event)` for every decoded event, beside `stallProjection.clear`, inside the existing `interactive` gate. Replayed frames after a reconnect pass through the same arm, so they rebuild the phase. Overrides `observeTurnPhase` to read the projection.

**`StableConversationRepository.observeTurnPhase`** = `switchToLive(Phase.Idle) { it.observeTurnPhase(conversationId) }`. A new connection is a new repository with an empty projection, and no connection reads idle: the reset on reconnect.

**`ThreadViewModel`**: a private `turnPhase = repository.observeTurnPhase(conversationId)`; `isThinking = turnPhase.map { it == Thinking }`, `isBusy = turnPhase.map { it == Thinking || it == Responding }`, each `stateIn(viewModelScope, WhileSubscribed(5_000), false)`. `thinkingTransition` and `busyTransition` are deleted. `turnOutcome` and `localSendPending` keep reading `liveSessionEvents` unchanged (out of scope). The `liveSessionEvents` constructor parameter stays, since those and the thread fold still use it.

`CachingConversationRepository` and the e2e `TappingConversationRepository` delegate by `ConversationRepository by delegate`, so they pass the new read through without edits.

## State and concurrency model

The projection's only writer is the repository's single inbound collector, as for `StallProjection`; `MutableStateFlow.update` keeps the read-modify-write atomic. The projection updates whether or not any screen subscribes, so a thread that resubscribes after its `WhileSubscribed` timeout re-reads the current held value from the `StateFlow` upstream. No new scopes or jobs.

## Error handling

None new. Malformed `turn_state` / `turn_end` frames are already dropped by `decodeLiveSessionEvent` before the projection sees them. A non-interactive connection never reaches the arm, so the phase stays idle.

## Testing strategy

- **`TurnPhaseProjectionTest`** (unit, new): every phase maps through `observe`; `turn_end` (clean, failed, cancelled) returns to idle; frames for B never change A; a conversation nobody observed still holds its phase when first observed; non-phase events (`AssistantDelta`, `ToolUse`, `ToolResult`, `ReplayGap`) leave the phase held; unknown conversation reads idle.
- **`RemoteConversationRepositoryTurnPhaseTest`** (unit, new, real repository over a fake pump): `turn_state` frames for c2 with nobody observing, then `observeTurnPhase("c2")` reads `Responding` and `c1` reads idle; `turn_end` returns to idle; closed `interactive` gate leaves idle.
- **`StableConversationRepositoryTest`** (extended): `observeTurnPhase` reads idle while absent; delegates to the live repo; after a switch to a fresh repository both c1 and c2 read idle (AC3).
- **`ThreadViewModelTest`** (migrated): the `#406` / `#459` blocks become a `TurnPhaseControllableRepo` (the `StallControllableRepo` shape) driving the held phase: idle default, thinking ⇒ both true, responding ⇒ `isBusy` only, idle ⇒ both false, observes only its own id. `failedAndCancelledTurnEnds_clearIsThinkingAndIsBusy` moves to the projection test. The two `onInterrupt_*` tests set the held phase instead of emitting events.
- **`ThreadViewModelTurnPhaseTest`** (unit, new; AC2): a VM over a real `RemoteConversationRepository`; collect, reach thinking, cancel the collectors and advance past the 5 s timeout; in one run the turn ends meanwhile, in the other a new turn starts from idle; on resubscription the flags read idle and running respectively, with no further frame.
- **`ScriptedTurnPhaseTest`** (Compose screen test under `sharedTest`, rung 2; AC1): with chat A open on the `ScriptedThreadHarness`, push `thinking` then `responding` for B; A shows no turn label and no stop button; `openConversation(B)` shows "Working" and the stop affordance with no further frame. The harness gains `targetConversationId` on `pushTurnState` and an `openConversation(id, name)` that swaps the composed VM over the same repository.

No device-only test: Robolectric covers the render. No rung-3 scenario: this is not a new operator flow, only the existing status reading surviving a reopen; the existing real-Claude status scenarios keep covering the live path.

## Open Questions

- Whether `isThinking` / `isBusy` should drop the `WhileSubscribed` cache on expiry (`replayExpirationMillis = 0`) to avoid one stale frame on return. Default: keep the sibling `isStalled` posture; the upstream re-emits the held value on resubscription.

## Documentation handoff

- Pending for the documentation stage: update `docs/knowledge/features/turn-state-thinking-flag.md` with the held `TurnPhaseProjection`, `observeTurnPhase` through `StableConversationRepository.switchToLive`, its reset on a new connection, and the removal of the "stale `true` on resume" caveat and of the `thinkingTransition` / `busyTransition` table.

## Revisions

- 2026-10-01 — Open Question resolved: `isThinking` / `isBusy` keep the sibling `WhileSubscribed(5_000)` default with no `replayExpirationMillis`. `ThreadViewModelTurnPhaseTest` shows the held upstream replaces the cached value as soon as the thread resubscribes, so a reset-to-false on expiry would only trade one stale frame for another. `ThreadViewModelTest` also gained `turnFlags_ignoreLiveEvents`, which pins that a live `turn_state` alone no longer moves the flags, so the per-ViewModel fold cannot come back as a second source.

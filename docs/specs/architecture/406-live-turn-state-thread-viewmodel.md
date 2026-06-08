# Spec #406 — Surface live turn-state into the conversation thread ViewModel

**Ticket:** [#406](https://github.com/pyrycode/pyrycode-mobile/issues/406) — split from #386. Data/ViewModel half of the thinking indicator; the stateless composable + placement is the sibling UI slice #407.

**Size:** S. 3 production files, ~25 production LOC, 0 new exported types, 1 changed call site. No UI added (AC #4) — no Figma / Design source section.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinator.kt:75-213` — the coordinator. The change mirrors the existing `connectionStatus` (109-111) and `activePumpFlow` (95) precedent: a private connection-scoped mirror written lock-step in `onConnection` (135-159) and `teardownActive` (193-200), and a public derived flow over it. Lines 144-153 explain why a non-interface surface (`registerPushToken`) is reached through the concrete repo handle, not the interface — same reachability shape as this slice.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:172-194` — `liveSessionEvents: SharedFlow<LiveSessionEvent>` (`replay = 0`, `extraBufferCapacity = 64`, `DROP_OLDEST`). The doc at 183-186 confirms it is on the **concrete** repo only, deliberately not on the `ConversationRepository` interface, and that facade/coordinator reachability for UI consumers is downstream consumer-slice work — i.e. exactly this ticket.
- `app/src/main/java/de/pyryco/mobile/data/model/LiveSessionEvent.kt:26-77` — the typed event surface. `TurnState(conversationId, phase)` with `Phase { Thinking, Responding, Idle }` (34-40) and `TurnEnd(conversationId, turnId, stopReason)` (73-77). `conversationId` is on every subtype (27), so routing is a field read, no `when`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:95-199` — the ViewModel. Add a defaulted trailing constructor param and a sibling `isThinking: StateFlow<Boolean>` that mirrors `connectionState` (192-199) exactly. The big `state` combine (151-190) is **not** touched.
- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsViewModel.kt:22-31` — the `connectionStatus` injected-flow precedent: a coordinator `StateFlow` taken as a constructor param and forwarded; the doc explains the "already hot, no `stateIn` re-wrap" reasoning (relevant background, though our flow is cold — see Design).
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt:78-101` — the coordinator singleton (78-88), the `SettingsViewModel` factory that already injects `…coordinator.connectionStatus` (97-99), and the `ThreadViewModel` factory (101) that gains one argument.
- `app/src/main/java/de/pyryco/mobile/data/network/NoiseSessionPump.kt:288-306` — `PumpState.Open(connId, capabilities: Set<String> = emptySet())`. The decode path is gated on `CAPABILITY_INTERACTIVE in capabilities`; the coordinator test must drive the fake pump `Open` **with** that capability for a `turn_state` to surface (see Testing strategy).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt:70,118` — the stateless screen takes `connectionState` as a **separate** parameter alongside `state`. Informational: confirms the sibling-flow pattern #407 will follow for `isThinking`. Not edited here.
- `app/src/test/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinatorTest.kt:46-116,525-665` — JUnit4 + `runTest`, `StandardTestDispatcher` driven by `runCurrent()`, hand fakes (no MockK). `Env`/`newEnv` (525-547), `FakeManagedPump` with `open(connId)` / `push(envelope)` (609-656), `StubRelayTransport` (657+). The real `RemoteConversationRepository` is built over the fake pump — the new test pushes a real `turn_state` envelope through it.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt:1-90` — `makeVm(handle, repo, source?)` helper, `runTest`, `Dispatchers.setMain(UnconfinedTestDispatcher())`. The new reduction tests inject a controllable `Flow<LiveSessionEvent>` through an extended `makeVm`.

## Context

Phase 2 structured-streaming exit-gate (pyrycode#596, ADR 025). The decode seam (#385, merged) turns the daemon's `turn_state` envelopes into typed `LiveSessionEvent.TurnState(conversationId, phase)` on the **concrete** `RemoteConversationRepository.liveSessionEvents` — a `SharedFlow` that is **connection-scoped** (rebuilt per connection, absent between connections) and **not** part of the `ConversationRepository` interface the thread ViewModel consumes. So the thread ViewModel cannot reach turn-state today.

This is the identical reachability shape already solved twice: `registerPushToken` (#359/#365) and the two-part `connectionStatus` (#392/#398). Both threaded a non-interface, connection-scoped surface up through `RelayRepositoryCoordinator` — the layer that owns the per-connection pump and repository — into a live caller. This slice does the same for live events and reduces the active conversation's latest turn phase to an `isThinking` flag on the thread ViewModel.

`responding` is already covered by the shipped streaming UI; this slice exposes `thinking` (active, pre-text) vs not-thinking.

## Design

Three additive edits, no new files, no new exported types.

### 1. `RelayRepositoryCoordinator` — expose a reconnection-surviving event seam

The coordinator builds the concrete `RemoteConversationRepository` in `onConnection` but publishes it only as the interface type on `currentRepository`. `liveSessionEvents` is not on the interface, so — exactly as `activePumpFlow` already mirrors the concrete pump for the derived `pyrycodeStatus` — add a private concrete-repo mirror and a public flow derived from it.

Contract sketch (mirrors `pyrycodeStatus` at lines 100-104):

```kotlin
// private mirror, written lock-step with mutableRepository / activePumpFlow
private val activeRemoteRepo = MutableStateFlow<RemoteConversationRepository?>(null)

@OptIn(ExperimentalCoroutinesApi::class)
val liveSessionEvents: Flow<LiveSessionEvent> =
    activeRemoteRepo.flatMapLatest { repo -> repo?.liveSessionEvents ?: emptyFlow() }
```

- **Lock-step writes.** Set `activeRemoteRepo.value = repo` in `onConnection` (alongside `mutableRepository.value = repo` at line 155) and `activeRemoteRepo.value = null` in `teardownActive` (alongside lines 194-195). Both sites are non-suspending — preserves the cancellation-atomicity invariant documented at lines 128-134; do not introduce suspension.
- **Reconnection-surviving (AC #1).** `flatMapLatest` cancels the prior connection's collection and switches to the fresh repo's `liveSessionEvents` when a new connection's repo lands — the same idiom as `pyrycodeStatus.flatMapLatest` over `activePumpFlow`. Between connections (`null`) it is `emptyFlow()`.
- **Cold, not `stateIn`'d.** Unlike `connectionStatus` (current-value state → `stateIn(Eagerly)`), these are *events* with no "current value", so expose a cold `Flow`. Each consumer's collection independently observes `activeRemoteRepo` (a `StateFlow`, multi-subscriber safe) and subscribes to the current repo's `SharedFlow` (multi-subscriber safe). No `shareIn` needed; sibling consumers #387/#337 each get a correct, independent subscription off the same public flow.
- **Generic, not turn-state-specific.** Expose the full `Flow<LiveSessionEvent>`, not an `isThinking`/turn-state projection. Reducing to "latest phase" is a consumer concern (per `LiveSessionEvent` doc lines 11-14); keeping the seam generic lets #387 (tool correlation) and #337 (assistant text) reuse it without re-plumbing the coordinator.
- Imports: add `de.pyryco.mobile.data.model.LiveSessionEvent` and `kotlinx.coroutines.flow.emptyFlow`. `RemoteConversationRepository` is same-package (no import). `flatMapLatest`, `ExperimentalCoroutinesApi`, `Flow`, `MutableStateFlow` already imported.

### 2. `ThreadViewModel` — reduce to `isThinking`, as a sibling StateFlow

Add a defaulted trailing constructor param and a sibling `isThinking` flow that mirrors `connectionState` (192-199). The `state` combine is untouched.

Contract sketch:

```kotlin
class ThreadViewModel(
    savedStateHandle: SavedStateHandle,
    private val repository: ConversationRepository,
    private val connectionStateSource: ConnectionStateSource,
    private val appPreferences: AppPreferences,
    liveSessionEvents: Flow<LiveSessionEvent> = emptyFlow(), // defaulted ⇒ existing constructions compile
) : ViewModel() {
    val isThinking: StateFlow<Boolean> =
        liveSessionEvents
            .mapNotNull { event -> reduceThinking(event) } // see below
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
}
```

The reduction is a single `mapNotNull` routing by `conversationId` then folding each event to the flag (or `null` to leave the flag unchanged):

| event for **this** `conversationId` | result | rationale |
|---|---|---|
| `TurnState(phase = Thinking)` | `true` | AC #2 — true only while latest phase is thinking |
| `TurnState(phase = Responding \| Idle)` | `false` | AC #2 |
| `TurnEnd` | `false` | AC #2 lists `turn_end` as a false case; guards the thinking→turn_end-direct edge where no `responding`/`idle` precedes it |
| `AssistantDelta` / `ToolUse` / `ToolResult` | `null` (no emission) | not a phase transition — leave the flag as-is |
| any event for a **different** `conversationId` | `null` (no emission) | AC #3 — other conversations never affect the flag |

`mapNotNull` + `stateIn` means: no matching event → no emission → the `StateFlow` retains its prior value; initial value `false` covers "before any turn-state event" (AC #2) and the inert default (AC #5).

- **Why a sibling StateFlow, not a field on `ThreadUiState`.** Live ephemeral cross-cutting signals already follow this pattern here: `connectionState` is a separate `StateFlow`, and the stateless `ThreadScreen` receives it as a parameter beside `state` (ThreadScreen.kt:70). `isThinking` is the same kind of signal (transient, connection-scoped, distinct source). Folding it into the `state` combine would force the max-arity (5) combine into a sub-combine restructure and touch the `initialValue`, risking AC #5 ("existing tests compile and pass unchanged"). The sibling flow is zero-touch to the combine.
- Imports: `de.pyryco.mobile.data.model.LiveSessionEvent`, `kotlinx.coroutines.flow.emptyFlow`, `kotlinx.coroutines.flow.mapNotNull`. `Flow`, `StateFlow`, `SharingStarted`, `stateIn`, `viewModelScope` already imported.

### 3. `AppModule` — wire the live flow

One-line change to the `ThreadViewModel` factory (101), mirroring the adjacent `SettingsViewModel` line (98):

```kotlin
viewModel {
    ThreadViewModel(get(), get(), get(), get(), get<RelayRepositoryCoordinator>().liveSessionEvents)
}
```

No new Koin binding — the flow is fetched off the already-registered concrete coordinator singleton, exactly as `connectionStatus` is.

## State + concurrency model

- **Coordinator `liveSessionEvents`** — cold `Flow`, no scope of its own. `flatMapLatest` over `activeRemoteRepo` (`StateFlow`, written only on the single non-suspending `onConnection`/`teardownActive` path) re-subscribes to the live repo's `SharedFlow` on each connection and cancels the prior on churn. No new coroutine, no `shareIn`.
- **ViewModel `isThinking`** — `stateIn(viewModelScope, WhileSubscribed(5_000), false)`, identical lifecycle to `connectionState`. While subscribed it collects the coordinator flow on the Main-bound `viewModelScope`; the reduction is pure (no dispatcher switch). On unsubscribe the upstream collection stops after 5 s; the `StateFlow` retains its last value (consistent with `connectionState`).
- **Dispatcher** — none chosen here; the source flow does its own work (the pump's inbound collector runs on the connection-scoped `Dispatchers.Default` scope inside the repo). The reduction is allocation-free per event.
- **Shutdown** — coordinator `teardownActive`/`close` nulls `activeRemoteRepo`, so `flatMapLatest` switches to `emptyFlow()`; the repo's `SharedFlow` completes when its scope is cancelled. ViewModel collection ends with `viewModelScope`.

## Error handling

None. The event stream is best-effort and never errors: `liveSessionEvents` is a `SharedFlow` that does not complete-with-error, and decode failures / unrecognized `turn_state` values are already dropped to nothing at the #385 mapper (`decodeLiveSessionEvent` returns `null`). Absence of a live source is the empty flow ⇒ the flag stays `false`. No network/IO/parse/permission failure surfaces in this layer, so no `catch`, no result type, no UI surfacing. (Contrast `SettingsViewModel.archivedDiscussionCount`, which `.catch`es a cold DataStore read — there is no analogous failing upstream here.)

## Testing strategy

All unit (`./gradlew testDebugUnitTest`), JVM, hand fakes — no instrumented tests, no MockK. The reduction logic is proven cheaply at the ViewModel layer; the reconnection plumbing is proven at the coordinator layer.

**`ThreadViewModelTest` (the bulk — AC #2/#3/#5).** Extend `makeVm` with an optional `liveSessionEvents: Flow<LiveSessionEvent> = emptyFlow()` param backed by a `MutableSharedFlow<LiveSessionEvent>()` the test emits into (no pump, no decode). Scenarios:

- Default `isThinking.value` is `false` with no source (empty flow) — also asserts existing 4-arg `makeVm` calls still compile (AC #5).
- `TurnState(convId, Thinking)` for the VM's conversation ⇒ `isThinking` becomes `true`.
- `TurnState(convId, Responding)` and `TurnState(convId, Idle)` each ⇒ `false`.
- `TurnEnd(convId, …)` ⇒ `false` (assert from a prior `true`, proving the turn_end reset).
- Sequence `Thinking` → `Responding` ⇒ ends `false` (latest-wins).
- `TurnState(OTHER_convId, Thinking)` ⇒ flag unchanged from its prior value (AC #3); pair with an interleaved same-conversation event to show only the matching one moves it.
- `AssistantDelta`/`ToolUse`/`ToolResult` for the VM's conversation ⇒ flag unchanged (no phase transition).

Drive with `runTest`; collect `isThinking` in `backgroundScope` (or assert `.value` after `advanceUntilIdle()`) so `WhileSubscribed` is active, following the existing `state`/`connectionState` test idiom.

**`RelayRepositoryCoordinatorTest` (the seam — AC #1).** The coordinator builds the real repo over `FakeManagedPump`, so a real `turn_state` envelope pushed to the pump exercises the genuine gated-decode path. The fake pump must be driven `Open` **with** `CAPABILITY_INTERACTIVE` for the decode gate to open — extend `FakeManagedPump.open()` to accept `capabilities: Set<String> = emptySet()` and set `PumpState.Open(connId, capabilities)`. Scenarios:

- Connect; `open(capabilities = setOf(CAPABILITY_INTERACTIVE))`; `push` a `turn_state` envelope (`{"conversation_id":"c1","state":"thinking"}`, type `turn_state` — reuse #385's envelope idiom); collect `coordinator.liveSessionEvents` in `backgroundScope`; `runCurrent()`; assert a `LiveSessionEvent.TurnState(c1, Thinking)` is received. (Proves events from the connection-scoped concrete repo reach the coordinator seam.)
- Reconnection: after the first connection, set `connections.value = StubRelayTransport()` again (fresh pump #2), open it with the capability, push a `turn_state` on pump #2; assert the collector receives the pump-#2 event — proving `flatMapLatest` switched to the new connection's repo (AC #1 "survives reconnection"). Optionally assert a push on the now-dead pump #1 does not surface.

End every coordinator test with `coordinator.close()` so the perpetual `connections.collect` does not hang `runTest` (existing convention, test doc 42-43).

## Open questions

- **`WhileSubscribed(5_000)` retention vs. backgrounding.** With `replay = 0` upstream, if the agent leaves `thinking` while the screen is backgrounded > 5 s and re-foregrounds before a fresh event, `isThinking` could momentarily read a stale `true` until the next event. This matches the transient "right-now" posture already accepted for the stall flag (#395) and `connectionState`; not handled here. If #407 finds it visually jarring, a `turn_end`/`idle`-on-resubscribe reset is a UI-slice follow-up, not data-layer work.
- **`makeVm` shape.** Whether to add the event param positionally or via a small overload is the developer's call; the only hard constraint is that the existing 4-arg call sites keep compiling (AC #5).

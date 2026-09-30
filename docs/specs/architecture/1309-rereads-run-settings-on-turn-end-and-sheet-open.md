# #1309 — Re-read run settings at turn end and when the sheets open

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → `sessionSettings` (the one settings subscription and its pending-write `onEach`), `settlePermission`, `sendSessionSettings`, `onOverflowEvent` (`ChannelInfo` arm), the `#778` walk-restart `init` keyed on `repositoryAvailable`, `busyTransition` (the running predicate), the `liveSessionEvents` constructor parameter.
- `app/src/main/java/de/pyryco/mobile/data/repository/SessionSettingsCommands.kt` → `observeSessionSettings`, `refreshSessionSettings`, `bumpSettingsRevision`: a bump re-reads through `flatMapLatest`, which cancels the in-flight read; nothing is sent while no collector listens.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` → `refreshSessionSettings`, `observeResetting` contracts.
- `app/src/main/java/de/pyryco/mobile/data/model/LiveSessionEvent.kt` → `TurnState.Phase`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadUiState.kt` → `ThreadEvent`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → `onStatusClick = { sheetVisible = true }`, the only place the Run configuration sheet opens.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` → the thread factory passes its own host's `coordinator.liveSessionEvents`, so the stream is host-scoped already.
- `../pyrycode-desktop/src/renderer/src/screens/conversation/runConfigLive.ts` → `createRunConfigRefreshTrigger`: per-conversation running `Set`, `delete` returns the edge, cleared on `connected`.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelAppliedEffortTest.kt` → `ScriptedRepo` delegation double counting `refreshSessionSettings`.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` → `interactiveTurn_inheritedEffort_footerShowsAppliedValueAfterTurn`, `awaitFooter`, `appliedEffortFooter`, and the permission assertion in `interactiveTurn_reconnect_footerReadingsAndModelChangeSurvive`.

Overlapping in-flight branches: #1308 and #1328 touch `ThreadViewModel.kt`, `ThreadUiState.kt`, `ThreadScreen.kt` and the e2e test in unrelated blocks; no dependency. Edits here stay additive.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=600-1694

Behaviour only: the Run configuration sheet's visuals do not change (the ticket says so). The Figma MCP was not authorised in this session, so the node was not fetched; nothing visual is built, so there is nothing to compare.

## Context

A thread opened on a conversation whose claude has not run yet gets a reading with no permission mode and no applied effort, and never asks again until a subscription, connection, session transition or its own write. Desktop re-reads on more edges; this copies them: turn end on the host, Run configuration open, Channel info open, reset end.

## Design

New file `ui/conversations/thread/RunSettingsRereads.kt`, two pure flow operators (internal, unit-testable):

- `fun turnEndEdges(events: Flow<LiveSessionEvent>): Flow<Unit>` — desktop's trigger. A running set local to each collection. `TurnState` with `Thinking`/`Responding` adds the conversation id; `TurnState(Idle)` emits one `Unit` only when removing the id actually removed it. Every other event is ignored (`TurnEnd` included, as desktop). The conversation id is only a set key: never logged, never used as the re-read's target.
- `fun resetEndEdges(resetting: Flow<ResetStatus?>): Flow<Unit>` — emits when the reading goes from non-null to `null`.

`ThreadViewModel`:

- A private `rereadRunSettings(reason: String)` logs `event=run_settings_reread reason=<static>` and calls `repository.refreshSessionSettings(conversationId)` — always the open thread's id.
- One `init` collector in `viewModelScope`: `merge` of
  - `repositoryAvailable.distinctUntilChanged().flatMapLatest { if (it) turnEndEdges(liveSessionEvents) else emptyFlow() }` — a fresh running set per connection, which is desktop's "cleared on connected". `repositoryAvailable` is the key the #778 restart already uses.
  - `resetEndEdges(repository.observeResetting(conversationId))` — a separate projection subscription; `observeResetting` sends nothing.
- `onOverflowEvent`: `ChannelInfo` re-reads only when `pendingChannelInfo` flips `false → true` (`compareAndSet`), so a repeated open event cannot double it; `ChannelInfoDismiss` unchanged. New arm `ThreadEvent.RunConfigOpen` re-reads.

`ThreadUiState.kt`: new `data object RunConfigOpen : ThreadEvent`.

`ThreadScreen.kt`: `onStatusClick` sets `sheetVisible = true` and sends `onOverflowEvent(ThreadEvent.RunConfigOpen)`. Closing sends nothing. A rotation restoring `sheetVisible` is not an open and sends nothing.

## State + concurrency model

The collector lives in `viewModelScope` from construction, like the #778 restart. A bump while the thread's `state` has no subscriber sends nothing (`SessionSettingsCommands` reads only inside a collector), and the next subscription reads on its own. All re-reads go through `refreshSessionSettings`, so the existing `flatMapLatest` cancels an in-flight read before the next starts; nothing here holds a second subscription.

Pending writes: unchanged code. A reading still replaces the whole reading. `pendingModel` still clears only on a matching or `null` reading; `settlePermission` waits for `seq > asked` after its own bump, and any later bump cancels the earlier read, so every reading it sees was read after its write. The pre-existing behaviour that any reading clears `pendingEffort` is kept, per AC 4.

`forLiveSession` still withholds a reply naming an older session; the turn-end re-read supplies the replacement.

## Error handling

None new. `refreshSessionSettings` is non-throwing and drops the bump without a collector; a failed read already folds to `null` in `SessionSettingsCommands`.

## Testing strategy

Unit, `app/src/test/.../thread/RunSettingsRereadsTest.kt` (the pure operators):
- running → idle emits once; repeated idle emits nothing; idle with no prior running emits nothing; thinking → responding → idle emits once; two conversations each emit their own edge; `TurnEnd` and non-turn events emit nothing.
- reset non-null → null emits once; null → null and null → non-null emit nothing.

Unit, `app/src/test/.../thread/ThreadViewModelSettingsRereadTest.kt` (fake repository counting `refreshSessionSettings`, `MutableSharedFlow` live events, `MutableStateFlow` availability and resetting):
- turn end on the open conversation → 1; turn end on another conversation → 1; repeated idle → 0 more; a turn starting → 0.
- a turn running when availability drops, then idle after it returns → 0 (set cleared per connection).
- `RunConfigOpen` → 1; `ChannelInfo` → 1; `ChannelInfoDismiss` → 0; reset end → 1.
- A host's other events never reach this VM: the VM only sees the stream it is given, and `AppModule` passes its own host's; no test beyond that wiring.

Existing classes rerun: `ThreadViewModelTest`, `ThreadViewModelPermissionTest`, `ThreadViewModelAppliedEffortTest`, `ThreadViewModelEffortRecallTest`.

Live (rung 3): rewrite `interactiveTurn_inheritedEffort_footerShowsAppliedValueAfterTurn` without `leaveThread`: after the reply, read `freshSettings`, assert a non-empty permission mode, then `awaitFooter` for effort and for permission in the same open thread. The dispatcher runs the live suite after verifier (`needs-real-claude`); the builder compiles it.

## Documentation handoff (pending, documentation stage)

- `docs/knowledge/features/status-sheet-readings.md` (or the thread-composer-footer / conversation-repository topic that describes the read edges): record the new re-read edges — turn end on the host, Run configuration open, Channel info open, reset end — and correct any statement that the reading refreshes only on subscription, connection, transition and writes.
- `docs/e2e-interactive-stream.md`: update the notes for `interactiveTurn_inheritedEffort_footerShowsAppliedValueAfterTurn` so they no longer describe the leave-and-reopen workaround, and mention the permission-mode assertion.

## Open questions

- None blocking.

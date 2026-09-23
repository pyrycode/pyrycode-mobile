# #861 — Key the thread's reconnect history re-ask on the repository becoming available

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → the #778 restart collector in `init`, `launchHistoryAsk` (its non-retryable `IllegalStateException` branch), `restartHistoryWalk` — the trigger this ticket moves.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadHistoryDemand.kt` → `restarted()` — restarting from a `PermanentFailure` stop re-arms the walk on the same page budget, so recovery needs no demand change.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` → `ThreadDestinationFactory.thread` — already holds the host's `bundle`, so `bundle.coordinator.currentRepository` is in reach with no new Koin definition.
- `app/src/main/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinator.kt` → `currentRepository` — non-null only once the pump is `PumpState.Open`; every new connection passes through `null` (teardown nulls `activeConnection`, and a fresh pump starts `Handshaking`).
- `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt` → `live` — throws `IllegalStateException(NOT_CONNECTED)` while `currentRepository` is `null`; the unit tests use this facade as the realistic double.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConnectionStateSource.kt` → the demo/test source; always `Connected` by default.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt` → the #778 `history_*` reconnect tests and `makeVm`.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` → `interactiveTurn_offlineRead_reconcilesPeerTurnOnReconnect` step 6, the #850 reopen work-around.

## Design source

N/A — no visual change; the thread draws the same rows, only the history re-ask's trigger moves.

## Context

For a relay host the thread's `ConnectionStateSource` is `RelayConnectionSupervisor.observe()`, whose `Connected` means socket-open. The live repository is published later, after the Noise handshake. The #778 restart therefore asks a `null` repository, the ask fails non-retryably (`DeadEnd`), and nothing restarts it. A thread opened in the socket-up/handshake window hits the same failure on its opening ask, and the restart's `drop(1)` discards its only `Connected` value.

## Design

**`ThreadViewModel`** gains one trailing constructor parameter:

```kotlin
repositoryAvailable: Flow<Boolean> = flowOf(true),
```

`true` while a live repository is published for this thread's host. The `init` restart collector switches its source from `connectionStateSource.observe().map { it == Connected }` to `repositoryAvailable`, keeping the same shape: `distinctUntilChanged()`, `drop(1)`, restart on `true`. Consequences:

- The value the thread opened on is dropped, so the repository it opened on is not a new one (the #778 budget trap).
- A first value of `false` (opened offline, or in the socket-up/handshake window) makes the repository's arrival a restart. The opening ask already failed with `NOT_CONNECTED` and stopped at `DeadEnd`; `restarted()` re-arms it. That is the recovery the `IllegalStateException` branch's comment already assumes, so the branch stays non-retryable.
- Socket-level `Connected` no longer triggers anything, so no ask can reach a `null` repository through the restart.
- The walk generation guard and the page budget are untouched, so a late page from the previous connection is still dropped and flapping still cannot launder a budget.

The default `flowOf(true)` keeps the demo path (`FakeConnectionStateSource` + `FakeConversationRepository`, available from the start) and every other construction site inert: one dropped value, never a restart. Behaviour there is unchanged, since `FakeConnectionStateSource` never leaves `Connected` in the demo.

`connectionStateSource` keeps feeding the `connectionState` banner flow and `retry()`; both are out of scope.

**`ThreadDestinationFactory.thread`** passes `repositoryAvailable = bundle?.coordinator?.currentRepository?.map { it != null } ?: flowOf(false)`. A host with no bundle is never available and never restarts, as its `flowOf(Offline)` source never did.

Considered and rejected: keying on the repository's identity rather than a Boolean, to survive a `StateFlow` conflating `A → null → B` into `A → B`. The `null` window spans a full relay round trip plus the Noise handshake, and no such conflation has been observed; the ticket names null → non-null as the signal.

## State + concurrency model

Unchanged except for the restart collector's source: one `viewModelScope.launch` collecting a hot `StateFlow`-backed Boolean in production, cancelled with the ViewModel. `restartHistoryWalk` keeps its CAS loop.

## Error handling

No new failure mode. The opening ask on a `null` repository still settles `PermanentFailure`; it is now always followed by a restart when the repository arrives.

## Testing strategy

Unit (`ThreadViewModelTest`), with a realistic double: a `MutableStateFlow<ConversationRepository?>` feeding both a `StableConversationRepository` (the VM's repository) and `repositoryAvailable` (`map { it != null }`), plus a `FakeConnectionStateSource` for the socket.

- **AC1 (new):** thread open on a published `HistoryRepo`; socket `Offline` and repository `null`; socket `Connected` first — no ask, tail not `DeadEnd`; then the repository is published — exactly one more ask, on the live repository, tail not `DeadEnd`. Fails on `main` (the restart fires on socket-up into the null facade and settles `DeadEnd`).
- **AC2 (new):** thread opened with socket `Connected` and repository `null`; the live repository receives no ask; after publication it receives exactly one newest-page ask.
- **AC3 (migrated):** `history_aNewConnection_…`, `history_theConnectionTheThreadOpenedOn_…`, `history_aReconnectMidFlight_…`, `history_aFlappingConnection_…` and the reconnect half of `history_breadcrumbs…` drive `repositoryAvailable` instead of the socket source.
- **Rung 3:** step 6 of `InteractiveStreamE2ETest#interactiveTurn_offlineRead_reconcilesPeerTurnOnReconnect` drops the back-and-reopen and waits in the still-open thread for both the peer's reply and `OFFLINE_PROMPT`, then runs the unchanged exactly-once and order checks. The live run belongs to the dispatcher's post-verifier `needs-real-claude` gate.

## File overlap

`origin/feature/859` (#863, in verification) touches `ThreadViewModel.kt` (the drop function's KDoc) and `InteractiveStreamE2ETest.kt` (the queue scenario's steps 4 and 7). Both hunks are regions this ticket does not edit; the builder checks the merge with `git merge-tree` before opening the PR rather than blocking on a file-level overlap.

## Documentation handoff

Pending for the documentation stage:

- `docs/e2e-interactive-stream.md` — the passages describing #850's reopen as a #861 work-around, and the "not yet covered live" entry "an open thread recovers a peer's reconnect-window prompt on its own": the still-open thread now receives the prompt through its reconnect re-ask, and the scenario checks this without reopening.
- `docs/knowledge/features/remote-conversation-repository-reads-and-thread-store-history-paging.md` — the section on #778's reconnect restart: the restart is keyed on the repository becoming available (`coordinator.currentRepository` null → non-null), not on socket-open.

## Open questions

None.

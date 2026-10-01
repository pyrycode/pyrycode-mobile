# #1317 — Keep a chat's pushed readings across a reconnect

## Files read

- `app/src/main/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinator.kt` → `RelayRepositoryCoordinator`, `onConnection`, `teardownActive`, `close`, `replayCursor`, `finishedBackgroundTasks`. These are the host-lifetime owner and the pattern being mirrored (#412, #677).
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` → constructor (`now`, `finishedBackgroundTasks`), the five projection fields, the `session_transition` arm's clears, and the five `observe*` overrides.
- `app/src/main/java/de/pyryco/mobile/data/repository/{AnnouncedModel,SessionFacts,ContextUsage,UsageLimit,SlashCommandMenu}Projection.kt` → each owns a private `MutableStateFlow<Map<String, T>>`. `UsageLimitProjection` takes `now`. None of them changes.
- `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt` → `switchToLive` and the five `observe*` overrides that emit `null` while nothing is live.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` → `ThreadDestinationFactory.repository`, the thread, settings and archive read path over `bundle.coordinator.currentRepository`. The singleton `StableConversationRepository(registry.currentRepository)` is the compatibility facade, which this change leaves alone.
- `app/src/main/java/de/pyryco/mobile/data/repository/CachingConversationRepository.kt` → `ConversationRepository by delegate`, so the five reads pass straight through to the stable facade.
- `docs/knowledge/features/relay-repository-coordinator.md`, `stable-conversation-repository.md` → the bundle and coordinator outlive reconnects. `bundle.close()`, called by registry reconcile on unpair or a credential change, is permanent and closes the coordinator.
- Tests: `RelayRepositoryCoordinatorTest` (`newEnv`, `FakeManagedPump`, `openInteractiveConnection`), `StableConversationRepositoryTest`, `di/RelayConnectionFactoryTest` (`Fixture`, `PeerTransport.emit`, `registry()`).

Overlap: `feature/1330` edits `AppModule.kt` (the notifier wiring) and `feature/1351` edits `RemoteConversationRepository.kt` (the `message` arm). Neither blocks this change. My edits to both files are additive and local.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

N/A for visual fidelity: the thread frame does not change. Only the lifetime of five readings it already renders changes.

## Context

Every return to the foreground is a full reconnect. Each connection builds a fresh `RemoteConversationRepository` whose five projections start empty. The thread facade also emits `null` for each reading while no repository is live. As a result, the context gauge, usage-limit notice, announced model, session facts and slash-command menu go blank on every resume. Desktop holds them for the life of the pairing, and this change does the same. The coordinator already outlives connection churn, and registry reconcile closes it on unpair or re-pair. That gives the pairing-scoped clear without any new mechanism.

## Design

### New `HostReadings` (`data/repository/HostReadings.kt`)

```kotlin
class HostReadings(now: () -> Instant = Clock.System::now) {
    internal val announcedModel: AnnouncedModelProjection
    internal val sessionFacts: SessionFactsProjection
    internal val contextUsage: ContextUsageProjection
    internal val usageLimit: UsageLimitProjection      // built with `now`
    internal val slashCommandMenu: SlashCommandMenuProjection

    fun observeAnnouncedModel(conversationId: String): Flow<AnnouncedModel?>
    fun observeSessionFacts(conversationId: String): Flow<SessionFacts?>
    fun observeContextUsage(conversationId: String): Flow<ContextUsage?>
    fun observeUsageLimit(conversationId: String): Flow<UsageLimitReading?>
    fun observeSlashCommandMenu(conversationId: String): Flow<SlashCommandMenu?>
    fun close()   // pairing ended: every observe* emits null from now on, idempotent
}
```

- The class is public so that the public constructors of `RemoteConversationRepository` and `StableConversationRepository` can take it. The projection fields are `internal` because the projection types are internal.
- `close()` flips a private `MutableStateFlow<Boolean>` gate. Each `observe*` is `gate.flatMapLatest { open -> if (open) projection.observe(id) else flowOf(null) }`. The gate is the pairing-scoped drop. It does not need a clear method on each of the five projections, which would add five production files. A frame applied after `close()` is held but never read, because the coordinator is permanently closed and no new connection can use it.

### `RemoteConversationRepository`

- Add the constructor parameter `hostReadings: HostReadings = HostReadings(now)` after `finishedBackgroundTasks`. The default keeps every existing construction compiling and per-connection, as `finishedBackgroundTasks` does.
- The five projection fields become `hostReadings.announcedModel` and so on, keeping their current names. Every `onInbound` arm, including the `session_transition` clears, the usage-limit `allowed` falling edge and its read-time expiry, stays exactly as it is.

### `RelayRepositoryCoordinator`

- Add the constructor parameter `now: () -> Instant = Clock.System::now`. Add `internal val hostReadings = HostReadings(now)` next to `finishedBackgroundTasks`, with the same doc rationale.
- `onConnection` passes `hostReadings = hostReadings` into each repository.
- `close()` calls `hostReadings.close()` after `teardownActive()`. `teardownActive()` does not touch the readings, so a reconnect keeps them.

### `StableConversationRepository`

- Add the constructor parameter `heldReadings: HostReadings? = null`.
- The five overrides become `heldReadings?.observeX(id) ?: switchToLive(null) { it.observeX(id) }`. With held readings they read the host's state directly, so the disconnected gap no longer emits `null`. Without held readings, as in the compatibility singleton and every existing test, the behaviour does not change.

### `AppModule.kt` → `ThreadDestinationFactory.repository`

- Pass `bundle?.coordinator?.hostReadings` as `heldReadings`. Thread, settings and archive share this path. Only the thread reads these five readings, and reading them elsewhere is harmless.

## State + concurrency model

- No new jobs or scopes. The projections' `MutableStateFlow`s are updated only from the one inbound collector of whichever connection is live. Connections are serialized by `onConnection`/`teardownActive`, so each projection has a single writer at a time.
- A frame still being handled when a connection is torn down may land after teardown. It is a genuine reading from the same host, which matches how `finishedBackgroundTasks` already behaves.
- The gate is a cold `flatMapLatest` on the consumer's scope. Cancellation follows the collector.

## Error handling

No new failure modes. Malformed frames are still dropped inside each projection's decoder. `close()` is idempotent. There are no logs, because the readings carry claude-authored text.

## Testing strategy

All tests are JVM unit tests under `./gradlew testDebugUnitTest`.

- `RelayRepositoryCoordinatorTest`. `newEnv` gains a `now` parameter.
  - A connection pushes all five frames, then a `null` teardown. During the gap, `coordinator.hostReadings.observe*` returns the held values. A second connection opens and the same values remain before any frame arrives (AC1).
  - After a reconnect, a new frame replaces a held reading, `session_transition` drops announced model, session facts and context usage but keeps the usage limit and slash menu, and an `allowed` frame drops the usage limit (AC2).
  - After a reconnect, the coordinator-supplied clock expires a held usage limit (AC2, proves the clock is taken at the coordinator).
  - Two coordinators: a frame on A is not visible on B (AC3).
  - `close()` makes every held reading `null` (AC3).
- `StableConversationRepositoryTest`
  - With `heldReadings` and `currentRepository = null`, all five reads emit the held values (the AC1 gap). After `heldReadings.close()` they emit `null`.
  - Without `heldReadings`, the facade still emits `null` while nothing is live. The existing tests already cover this, so no new test is needed.
- `di/RelayConnectionFactoryTest` (registry level, real Noise peers)
  - Save A and B and connect. A `model_announced` frame on A reaches `ThreadDestinationFactory.repository("A")`, and `repository("B")` shows `null` (two-host). The A transport closes and the reading stays (the reconnect gap through the production wiring). Removing A drops it from the old facade. Re-adding A gives a new bundle whose facade shows `null` (AC3).
- Existing projection and repository tests must stay green: `RemoteConversationRepositoryRunReadingsTest`, `RemoteConversationRepositoryContextUsageTest`, `RemoteConversationRepositorySlashCommandTest`, `RemoteConversationRepositoryTest` (usage limit).

No rung-3 scenario is needed. The ticket states that every criterion is proven with unit tests.

## Documentation handoff

Pending for the documentation stage: update the five readings' feature topics (`status-sheet`/composer-footer-related topics for the announced model and session facts, the context-usage topic, `usage-limit-state.md`, and `remote-conversation-repository-model-and-slash-command-menus.md`), plus `relay-repository-coordinator.md` and `stable-conversation-repository.md`. They should say these five readings are held per host pairing, not per connection.

## Open questions

- Does any other thread consumer read these five readings through the compatibility singleton? If one does, it keeps today's gap, as the ticket scopes it. I will confirm while wiring.

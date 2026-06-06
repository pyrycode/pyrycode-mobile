# Spec #352 — Stable `ConversationRepository` facade across relay connection churn

**Ticket:** [#352](https://github.com/pyrycode/pyrycode-mobile/issues/352) · split from #349 · size **S** · labels `security-sensitive`
**Depends on:** #351 (landed, PR #354) — consumes its exposed `RelayRepositoryCoordinator.currentRepository`.
**Siblings:** #350 (flag-gated Fake↔Remote binding swap — `blockedBy` this slice; flips the DI binding to the facade this slice registers).

---

## Files to read first

| Path · lines | What to extract |
|---|---|
| `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:20-120` | The **full interface the facade implements**. 3 stream reads (`observeConversations`/`observeMessages`/`observeLastMessage`); 8 no-default suspend one-shots (`createDiscussion`, `promote`, `archive`, `unarchive`, `rename`, `startNewSession`, `changeWorkspace`, `sendMessage`); 3 defaulted methods (`delete`:61, `recentWorkspaces`:100, `createWorkspaceFolder`:118). The facade overrides **all 14** so it delegates faithfully (AC #4). Note `recentWorkspaces` is a 4th stream-shaped read. |
| `app/src/main/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinator.kt:49-52` | The seam this slice consumes: `currentRepository: StateFlow<ConversationRepository?>` — live connection-scoped repo, or `null` between connections. Typed at the **interface**, not the concrete remote type. This is the facade's sole constructor dependency. |
| `app/src/main/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinator.kt:76-97` | `onConnection`/`teardownActive`: **every** connection transition passes through `mutableRepository.value = null` first (teardown clears to null before a non-null re-publish). Confirms the facade sees `…→null→repo2→…`, never a direct `repo1→repo2` swap — but the facade must be correct even if it did (see Design). |
| `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:254-267` | `sendAndAwaitReply`: a not-`Open` pump makes `pump.send` return `false` → `check(...)` throws **`IllegalStateException`**. This pins the not-connected exception type the facade reuses (AC #3) so callers see one type whether the connection was absent at call time (facade) or dropped mid-flight (remote). |
| `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:313-320` | `observeConversations` is **cold**: it `pump.send(...)`s on every subscription, then emits from a `filterNotNull()` projection. Confirms a fresh subscription per `flatMapLatest` re-switch re-issues the request — the intended reconnect behavior — and that the remote emits *nothing* until its first snapshot (so the facade's empty fallback covers the no-connection gap, not a "connected-but-loading" gap). |
| `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:529-548` | The throwing stubs (`archive`/`unarchive`/`rename`/`startNewSession`/`changeWorkspace` → `UnsupportedOperationException`). The facade delegates to these unchanged (AC #4): out-of-scope behavior passes through verbatim. |
| `app/src/main/java/de/pyryco/mobile/di/AppModule.kt:56` · `:72-78` | **Line 56** (`FakeConversationRepository … bind ConversationRepository::class`) stays untouched (AC #5). Lines 72-78 register the coordinator (`createdAtStart`); `get<RelayRepositoryCoordinator>().currentRepository` is the wiring expression for the facade's constructor arg. |
| `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt:10,20,62,72` | Existing `@OptIn(ExperimentalCoroutinesApi::class)` + `flatMapLatest` precedent in this repo — mirror the opt-in placement and import. |
| `app/src/test/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinatorTest.kt:38-73,247-359` | Test idiom to mirror: `@OptIn(ExperimentalCoroutinesApi::class)`, `StandardTestDispatcher(testScheduler)` + `runCurrent()`, hand fakes (no MockK), `backgroundScope.launch { …collect }` for cold-read collectors, `assertNotSame` for distinct-instance checks. The facade test is **simpler** — it drives a `MutableStateFlow<ConversationRepository?>` directly (no coordinator, no pump). |
| `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:48-50,87,248` | The bound default that **stays** bound (AC #5) and a shape reference for the recording test delegate. Not edited by this slice. |

---

## Context

Phase 4 backend integration. #351 (landed) builds a fresh `RemoteConversationRepository` per live relay connection and publishes the current one — or its absence between connections — on `RelayRepositoryCoordinator.currentRepository: StateFlow<ConversationRepository?>`.

There is an impedance mismatch with how consumers obtain the repository: the five ViewModels (`ChannelListViewModel`, `DiscussionListViewModel`, `ThreadViewModel`, `SettingsViewModel`, `ArchivedDiscussionsViewModel`) resolve a **single** `ConversationRepository` from Koin at construction and hold it for their lifetime. The real repository is **per-connection** — rebuilt on every reconnect, absent between connections. A ViewModel that captured a connection-scoped repository directly would hold a dead reference the moment the connection dropped.

This slice builds the stable indirection: **one long-lived `ConversationRepository` that delegates to whichever connection-scoped repository is currently live** (consumed from #351's observable), switching transparently as connections churn, and exposing defined non-crashing behaviour while no connection is live. It registers the facade as its own resolvable DI type but does **not** bind it as the `ConversationRepository` default — the Fake stays bound; the flag-gated swap is #350.

Cross-connection isolation is a correctness property (AC #2): after a reconnect a reader must never observe a previous connection's data.

---

## Design

### Package & new types

| Type | File | Kind |
|---|---|---|
| `StableConversationRepository` | `data/repository/StableConversationRepository.kt` | **new class** — implements `ConversationRepository`; the stable facade |
| — | `di/AppModule.kt` (modify) | one new `single { … }` registration; **line 56 untouched** |

**1 new exported type; 1 new production file + 1 modified (`AppModule.kt`) = 2 production files. Within size `S`.** No ViewModel is edited (they are listed in the ticket only to motivate the stable reference; they keep resolving the Fake binding until #350).

### `StableConversationRepository` — constructor contract (AC #1)

Constructor injects the observable, **not** the coordinator class — the facade needs only the published stream, mirroring how `RelayRepositoryCoordinator` itself takes `connections: StateFlow<RelayTransport?>` rather than the supervisor:

```kotlin
class StableConversationRepository(
    private val currentRepository: StateFlow<ConversationRepository?>, // = coordinator.currentRepository
) : ConversationRepository
```

The facade is a **process-lifetime singleton** (registered below); its object identity is fixed for the app's life, so a ViewModel holding it never sees the reference change when the underlying connection-scoped repo is rebuilt (AC #1). It **launches no coroutine and owns no scope** — all reads are cold flows collected on the *consumer's* scope, all one-shots run on the *caller's* coroutine. It holds no mutable state beyond the injected `StateFlow` reference.

### Stream reads — `flatMapLatest` switch with an empty fallback (AC #2, #3)

The four stream-shaped reads (`observeConversations`, `observeMessages`, `observeLastMessage`, **and** `recentWorkspaces`) share one shape. Factor it into a single private helper rather than repeating `flatMapLatest` four times:

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
private fun <T> switchToLive(whenAbsent: T, select: (ConversationRepository) -> Flow<T>): Flow<T> =
    currentRepository.flatMapLatest { repo -> repo?.let(select) ?: flowOf(whenAbsent) }
```

- **`observeConversations(filter)`** → `switchToLive(emptyList()) { it.observeConversations(filter) }`
- **`observeMessages(id)`** → `switchToLive(emptyList()) { it.observeMessages(id) }`
- **`observeLastMessage(id)`** → `switchToLive<Message?>(null) { it.observeLastMessage(id) }`
- **`recentWorkspaces()`** → `switchToLive(emptyList()) { it.recentWorkspaces() }`

**Why `flatMapLatest` gives AC #2 for free.** When `currentRepository` emits a new value (a new repo, or `null`), `flatMapLatest` **cancels the previous inner flow** and subscribes the new one. So:

- On a live repo → the inner is `repo.observeConversations(filter)` (cold; re-issues its `list_conversations`/`backfill_since` request on this fresh subscription — the intended reconnect re-request).
- On `null` → the inner is `flowOf(whenAbsent)`, emitting the defined empty projection once.
- On switch → the prior connection's inner flow is **cancelled**, so its projections are dropped the instant the value changes. Combined with #351 giving each connection a *distinct* repo instance with its own projection state, **a reader can never observe a previous connection's data after a reconnect** (AC #2). This holds even in the (currently unreachable — see Files to read, coordinator `:76-97`) hypothetical of a direct `repo1→repo2` emission with no intervening `null`: `flatMapLatest`'s cancel-old-on-new semantics alone are sufficient. The intervening `null` is belt; `flatMapLatest` is the load-bearing suspenders.

**The no-connection cold-read contract (AC #3):** while `currentRepository.value == null`, each stream emits its defined empty projection — `emptyList()` for the two list reads and `recentWorkspaces`, `null` for `observeLastMessage` — and **resumes** the moment a connection arrives (the next `flatMapLatest` switch subscribes the live repo). This deliberately renders the disconnected state as "empty", not as an error or a crash. It conflates "no connection" with "connected, genuinely zero items" at this layer; a distinct connection-status surface for the UI is **out of scope** (a follow-up, consumed by #350's ViewModels if desired). Note the remote repo emits *nothing* until its first snapshot (`observeConversations` filters `null`), so the facade's empty fallback covers only the no-connection gap — it does not paper over a connected-but-loading state.

### One-shot / mutating methods — snapshot-or-throw (AC #3, #4)

The ten suspend one-shots (`createDiscussion`, `promote`, `archive`, `unarchive`, `delete`, `rename`, `startNewSession`, `changeWorkspace`, `sendMessage`, `createWorkspaceFolder`) take a **snapshot** of the live repo at call entry and delegate to it; if none is live, they surface the defined not-connected outcome:

```kotlin
private val live: ConversationRepository
    get() = currentRepository.value ?: throw IllegalStateException(NOT_CONNECTED)
// e.g. override suspend fun sendMessage(id, text) = live.sendMessage(id, text)
```

Each method is a one-line `= live.<method>(args)` delegation.

**The not-connected contract decision (AC #3):** **throw `IllegalStateException`**. Rationale — the interface returns the domain entity directly (`suspend fun createDiscussion(): Conversation`), not a `Result`, so the only way to surface "not connected" *within the existing contract* is to throw; changing return types to a sealed result would cascade across every consumer and the Fake (out of scope). `IllegalStateException` is chosen specifically to **match the remote repo**, whose `sendAndAwaitReply` already throws `IllegalStateException` when the pump is not `Open` (`RemoteConversationRepository.kt:262`). A caller therefore catches **one** exception type whether the connection was absent at call time (facade throws) or dropped between the call and the send (remote repo throws). This is a *defined, catchable, non-crashing* outcome — a coroutine-scoped exception the caller (and #350's ViewModels) can handle, not an app crash.

**Delegation is behavior-neutral (AC #4):** the facade delegates **regardless** of whether the live repo's method is wired (`createDiscussion`/`promote`/`sendMessage`) or still a throwing stub (`archive`/`unarchive`/`rename`/`startNewSession`/`changeWorkspace` → `UnsupportedOperationException`; the inherited `delete`/`createWorkspaceFolder` defaults). The facade does **not** add, suppress, or translate any of those outcomes — when a live connection exists, the caller sees exactly what the connection-scoped repo does. Snapshotting once at entry is correct: if the connection drops *after* the snapshot, the delegate's own method handles the mid-flight drop (it throws `IllegalStateException` when its `pump.send` returns false) — the facade need not re-check.

### DI registration (AC #1, #5)

In `AppModule.kt`, add the facade as a lazy `single` resolvable by its own type. **Do not touch line 56** — `FakeConversationRepository … bind ConversationRepository::class` stays the bound `ConversationRepository`; this slice does **not** add `bind ConversationRepository::class` to the facade (that is #350's one-line flip).

```kotlin
single { StableConversationRepository(get<RelayRepositoryCoordinator>().currentRepository) }
```

Lazy (no `createdAtStart`) is correct: the facade is stateless and does no work at construction, and nothing resolves it yet (ViewModels still get the Fake until #350). Registering it now reduces #350 to binding-flip + ViewModel rewiring. `get<RelayRepositoryCoordinator>()` resolves the eager singleton already registered at lines 72-78; `currentRepository` is a stable `StateFlow` instance for the coordinator's life.

### Data flow

```
RelayRepositoryCoordinator.currentRepository : StateFlow<ConversationRepository?>
        │  null → repo1 → null → repo2 → …      (each repoN distinct; own projection state)
        ▼
StableConversationRepository  (one stable singleton; identity fixed for app life)
   cold reads   ── flatMapLatest(currentRepository) ─▶ repoN.observeX(...)  | flowOf(empty) when null
                     (switch cancels repoN-1's inner flow → no cross-connection leak, AC #2)
   one-shots    ── currentRepository.value ?: throw IllegalStateException ─▶ repoN.mutate(...)
        ▼
   ConversationRepository surface — bound as the default only by #350 (not here)
```

---

## State + concurrency model

- **No owned scope, no launched coroutine.** Cold reads are collected on the consumer's scope (ViewModel `stateIn`/`collectAsStateWithLifecycle`); one-shots run on the caller's coroutine. The facade is a pure transformer over the injected `StateFlow`.
- **Single source of truth, externally owned.** The only state is the injected `currentRepository`, owned and written by the coordinator's single non-suspending collector (#351). The facade only **reads** it — `.value` (snapshot) for one-shots, the flow itself for cold reads. No `MutableStateFlow`, no check-then-mutate, hence no lock and no TOCTOU within the facade.
- **Hot → cold.** The facade reads one hot `StateFlow` and produces **cold** per-collector flows. Each collector gets its own `flatMapLatest` chain and its own downstream subscription to the live repo's cold read — no subscriber sharing, so no data leaks across screens/ViewModels.
- **`flatMapLatest` opt-in.** Requires `@OptIn(ExperimentalCoroutinesApi::class)` (same as `ChannelListViewModel`/`RemoteConversationRepository`). Place it on the helper (or the class).
- **Snapshot semantics for one-shots.** `live` reads `currentRepository.value` exactly once per call; the connection state at call entry decides delegate-vs-throw. A drop after the snapshot is handled by the delegate, not the facade (see Design).

---

## Error handling

| Failure mode | Behaviour |
|---|---|
| One-shot called while no connection is live | Facade throws `IllegalStateException(NOT_CONNECTED)` — the defined not-connected outcome (AC #3), same type the remote repo throws on a not-`Open` pump. Caller's coroutine catches it; no crash. |
| Connection drops *during* a one-shot (after snapshot) | The snapshotted delegate's own method throws `IllegalStateException` (its `pump.send` returns false). Facade adds nothing. |
| Live repo's method is a throwing stub (`archive`/`rename`/…) | `UnsupportedOperationException` propagates verbatim through the facade (AC #4) — behavior unchanged. |
| Live repo's wired mutation throws (`RelayErrorException`, `IllegalArgumentException` for unknown conversation, decode errors) | Propagates verbatim (AC #4) — the facade does not translate. |
| Cold read collected while no connection is live | Emits the defined empty projection (`emptyList()`/`null`); resumes on the next connection (AC #3). Never errors. |
| Connection drops during a cold read | `currentRepository` emits `null` → `flatMapLatest` cancels the live inner flow and switches to the empty fallback; the prior connection's projection is dropped (AC #2). |
| Facade emits **no logs** | Consistent with the coordinator/pump/supervisor "no logs" posture. The facade handles only object references and domain values already flowing through the authenticated channel; it must not log repo contents, conversation ids, message bodies, or the not-connected event. |

---

## Testing strategy

Unit only — JVM `./gradlew test` (single class: `./gradlew testDebugUnitTest --tests "de.pyryco.mobile.data.repository.StableConversationRepositoryTest"`), JUnit4 + `runTest`, hand fakes (no MockK), `@OptIn(ExperimentalCoroutinesApi::class)`. New file `StableConversationRepositoryTest.kt` under `app/src/test/java/de/pyryco/mobile/data/repository/`. **No instrumented tests** (no Android APIs). Mirror `RelayRepositoryCoordinatorTest`'s idiom, but **simpler**: drive a `MutableStateFlow<ConversationRepository?>` directly — no coordinator, no pump.

**Test fakes (hand-rolled):**
- A `RecordingConversationRepository : ConversationRepository` — backs `observeConversations` (and as needed `observeMessages`/`observeLastMessage`/`recentWorkspaces`) with its own `MutableStateFlow` the test can push into, and records each one-shot call (e.g. captured args list). Two distinct instances stand in for two connections. Out-of-scope one-shots can just record; a "stub" variant can throw `UnsupportedOperationException` to exercise pass-through.
- A `StandardTestDispatcher(testScheduler)`-backed `runTest`; collect cold reads via `backgroundScope.launch { …collect { emissions += it } }`; advance with `runCurrent()`. (Per repo memory: `runCurrent()`, not `advanceUntilIdle()`, for pump-style fan-out — applies to these StateFlow projections.)

**Scenarios (each a `@Test`; describe inputs + expected, not full bodies):**

- **AC #1 — stable identity:** the facade instance is the same object across `currentRepository` transitions (push `null → repoA → null → repoB`); assert the facade reference is unchanged (it's a `val` in the test — assert it's the single instance the test constructed and that resolving it twice from a Koin graph, if checked, yields the same object).
- **AC #2 / #3 — cold read while absent:** with `currentRepository.value == null`, collect `observeConversations(All)`; assert the first emission is `emptyList()`. Same for `observeMessages` (`emptyList()`), `observeLastMessage` (`null`), `recentWorkspaces` (`emptyList()`).
- **AC #2 / #3 — resume on connect:** start absent (empty emitted) → set `currentRepository.value = repoA` → push a non-empty list into `repoA`'s flow → assert the collector now reflects `repoA`'s data.
- **AC #2 — transparent switch:** with `repoA` live and emitting list `[a]`, set `currentRepository.value = repoB` (emitting `[b]`); assert the collector switches to `[b]`.
- **AC #2 — cross-connection isolation (the key test):** `repoA` live, collector observes `[a]`; switch to `null` then `repoB` (which has *not yet* emitted); assert the collector shows the empty projection and **never** re-emits `repoA`'s `[a]`; then push `[b]` into `repoB` and assert only `[b]` surfaces. (Optionally: push into `repoA`'s now-cancelled flow *after* the switch and assert it surfaces nowhere — mirrors the coordinator test's "ghost" leak check.)
- **AC #3 — one-shot while absent throws:** `currentRepository.value == null`; assert `createDiscussion()` / `sendMessage(...)` / `promote(...)` each throw `IllegalStateException`. Assert no delegate was touched.
- **AC #4 — one-shot delegates when live:** `repoA` live; call `sendMessage("c1","hi")` / `createDiscussion("/ws")`; assert `repoA` recorded the call with the exact args and the facade returned `repoA`'s return value verbatim.
- **AC #4 — stub pass-through:** `repoA` live with an `archive` that throws `UnsupportedOperationException`; assert the facade rethrows the same exception type unchanged.
- **AC #5 — Fake binding untouched:** assert (by inspection of the unchanged `AppModule.kt:56` + code-review, or a lightweight `koinApplication { modules(appModule) }` check) that resolving `ConversationRepository` still yields `FakeConversationRepository`, and that `StableConversationRepository` is *separately* resolvable. Do not over-build a Koin harness if none exists to mirror.

---

## Open questions

- **Class / file name.** `StableConversationRepository` is proposed (reads well at the #350 binding site and as a singleton). Alternatives the developer may prefer: `DelegatingConversationRepository`, `ConnectionScopedConversationRepositoryFacade`. ktlint requires the file name to match the single public class (repo memory: single-public-class filename rule) — name the file after whatever class name is chosen.
- **Helper vs. inline.** The `switchToLive` helper is the recommended DRY shape for the four cold reads. If the developer finds four explicit `flatMapLatest` blocks clearer, that is acceptable — behavior is identical; keep the empty-fallback values exactly as specified.
- **`distinctUntilChanged` on the outer switch?** Not required: `StateFlow` already conflates equal consecutive values, and distinct repo instances are non-equal. Adding it would suppress nothing real. Left to the developer; not load-bearing.

---

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries]** No findings. The facade sits *above* the authenticated Noise channel and the repository's decode boundary — it only moves object references (the live `ConversationRepository` from `currentRepository`) and already-parsed domain values (`Conversation`/`Message`/`ThreadItem`/`String`) that have crossed the remote repo's `try/catch` decode boundary (`RemoteConversationRepository.kt:128-205`). It introduces **no** new untrusted→trusted boundary, parses no wire bytes, and never collects `pump.inbound`/`transport.*`. The one new input is the *presence/absence* of a delegate (`null` vs non-null), which is a trusted, internally-published signal from #351's coordinator.
- **[Tokens / secrets / keys]** N/A — the facade holds no key material, tokens, or credentials. All key material lives inside the pump/`NoiseIkSession` *below* the connection-scoped repo; the facade never reaches it. Connection churn key-wipe is #351's invariant (`pump.close()` on every teardown), unchanged and untouched here.
- **[File / storage]** N/A — the facade performs no filesystem, `DataStore`, or storage I/O. No paths are constructed; no path-traversal/TOCTOU/atomic-write/backup surface exists.
- **[Inter-process / Android surface]** N/A — no `Activity`/`Service`/`BroadcastReceiver`/`ContentProvider`/deep-link/`PendingIntent`/WebView added. The facade is a pure data-layer singleton with no exported component and no `Context`/Android API (keeping `data/` portable per the Compose-Multiplatform walk-back rule).
- **[Cryptographic primitives]** N/A — no RNG, hashing, key derivation, or comparison of attacker-controlled values. The crypto-relevant invariant (a fresh single-use pump per connection, no Noise ephemeral/AEAD-nonce reuse) is enforced one layer down by #351 and is *reinforced*, not weakened, by this slice: `flatMapLatest`'s cancel-old-on-switch drops the prior connection's repo and its subscriptions, so no consumer keeps reading a dead connection's projection.
- **[Network & I/O]** No findings. The facade opens no sockets, sets no timeouts/TLS, and lifts no frame-size cap — all owned by #306/#351, unchanged. The cold reads re-issue `list_conversations`/`backfill_since` on each `flatMapLatest` re-subscription, but the re-subscription cadence is bounded by `currentRepository` transitions, which are governed by the supervisor's existing capped-exponential backoff — the facade adds **no** retry or polling of its own, so it cannot drive a tight request loop against a hostile relay.
- **[Error messages / logs / telemetry]** No findings. The facade emits no logs and no telemetry, consistent with the coordinator/pump/supervisor "no logs" posture. The `NOT_CONNECTED` `IllegalStateException` message is a fixed generic string (`"No live relay connection"`) — it carries no conversation id, message body, token, host, or repo contents. Mandated in Design/Error-handling: never log repo contents or the not-connected event.
- **[Concurrency]** No MUST FIX. The facade owns no scope and launches no coroutine; cold reads run on the consumer's scope (cancelled by the consumer's lifecycle), one-shots on the caller's coroutine. The only shared state is the externally-owned `currentRepository`, which the facade only reads — `.value` (a lock-free `StateFlow` snapshot) for one-shots and the flow for cold reads — so there is **no** check-then-mutate, no `MutableStateFlow` write, no lock, and no TOCTOU inside the facade. The one-shot snapshot-then-delegate has a benign race (connection drops after the snapshot) that is *defined*: the delegate throws `IllegalStateException` on its own not-`Open` send — the same outcome the facade would produce — so the race has no incorrect observable result. Cold reads are per-collector (cold `flatMapLatest`), so no hot-flow subscriber-sharing leaks data across screens.
- **[Threat-model alignment]** No findings. The mobile-relevant `protocol-mobile.md` § Security-model threat this slice can touch — **cross-connection data leakage** — is precluded: `flatMapLatest` cancels the prior connection's inner flow on every switch, and #351 gives each connection a distinct repo with isolated projection state, so a reader after reconnect cannot observe a previous connection's data (AC #2, asserted by the cross-connection isolation test). UI-surface mobile threats (screenshot leakage, accessibility eavesdropping, overlay, deep-link, keyboard logging) do not apply — the facade has no UI or input. **Out of scope, named:** the flag-gated Fake↔Remote binding swap + ViewModel rewiring (#350), a distinct connection-status UI surface (follow-up; this slice renders disconnected as the empty projection per AC #3), the v2 write path (#346/#347/#348), and FCM `register_push_token` (deferred follow-up).

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-06-06

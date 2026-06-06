# Stable conversation repository — one reference across connection churn

The **stable indirection** ViewModels hold so relay connection churn never invalidates an injected
repository. The five ViewModels (`ChannelListViewModel`, `DiscussionListViewModel`, `ThreadViewModel`,
`SettingsViewModel`, `ArchivedDiscussionsViewModel`) resolve a single
[`ConversationRepository`](conversation-repository.md) at construction and hold it for their lifetime,
but the Phase 4 real repository is **per-connection** — the
[coordinator](relay-repository-coordinator.md) rebuilds a fresh
[`RemoteConversationRepository`](remote-conversation-repository.md) on every reconnect, and there is
*no* repository between connections. A ViewModel that captured a connection-scoped repository directly
would hold a dead reference the instant the connection dropped.

`StableConversationRepository` is the fix: **one process-lifetime singleton whose object identity never
changes**, delegating every call to whichever connection-scoped repository is currently live (read from
#351's `currentRepository`), switching transparently as connections come and go, and exposing a defined
non-crashing surface while no connection is live.

Package: `de.pyryco.mobile.data.repository` (`StableConversationRepository.kt`), co-located with the
[contract](conversation-repository.md) it implements and the [coordinator](relay-repository-coordinator.md)
it reads from. Landed in [#352](../codebase/352.md) (split from #349). Portable, `android.*`-free, emits
**no logs**. It is registered as its own resolvable DI type but does **not** yet bind
`ConversationRepository` — the Fake stays the default until the #350 flag-gated swap.

## Where it sits in the Phase 4 stack

```
ViewModels  ◀── hold one stable ConversationRepository reference for their lifetime
    ▲
StableConversationRepository (#352)  ◀── this doc; identity fixed for the app's life
    │  flatMapLatest over currentRepository (cold reads) ; .value snapshot (one-shots)
    ▼
RelayRepositoryCoordinator.currentRepository : StateFlow<ConversationRepository?>  (#351)
    │  null → repo1 → null → repo2 → …   (each repoN distinct; own projection state)
    ▼
RemoteConversationRepository  ◀── one per live connection
```

The facade is a **pure transformer** over the coordinator's published `StateFlow` — it adds no
behaviour, holds no scope, launches no coroutine, and stores no mutable state beyond the injected
`StateFlow` reference. It is the *only* place the "one stable reference" requirement is satisfied; the
coordinator deliberately stops at publishing the churning observable.

## Exported type

```kotlin
// data/repository/StableConversationRepository.kt
class StableConversationRepository(
    private val currentRepository: StateFlow<ConversationRepository?>,  // = coordinator.currentRepository
) : ConversationRepository
```

It overrides **all 14** interface members — the 4 stream-shaped reads (`observeConversations`,
`observeMessages`, `observeLastMessage`, **and** `recentWorkspaces`) and the 10 suspend one-shots
(`createDiscussion`, `promote`, `archive`, `unarchive`, `delete`, `rename`, `startNewSession`,
`changeWorkspace`, `sendMessage`, `createWorkspaceFolder`) — including the three that ship a default
body on the interface, so delegation is faithful and nothing silently falls back to a default.

## How it works

### Cold reads — `flatMapLatest` switch with an empty fallback

The four stream reads share one private helper rather than four repeated `flatMapLatest` blocks:

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
private fun <T> switchToLive(whenAbsent: T, select: (ConversationRepository) -> Flow<T>): Flow<T> =
    currentRepository.flatMapLatest { repo -> repo?.let(select) ?: flowOf(whenAbsent) }
```

- `observeConversations(filter)` / `observeMessages(id)` / `recentWorkspaces()` → `switchToLive(emptyList()) { … }`
- `observeLastMessage(id)` → `switchToLive<Message?>(null) { … }`

When `currentRepository` emits a new value, `flatMapLatest` **cancels the previous inner flow** and
subscribes the new one:

- **Live repo** → the inner is `repo.observeX(…)` (cold; it re-issues its `list_conversations` /
  `backfill_since` request on this fresh subscription — the intended reconnect re-request).
- **`null`** → the inner is `flowOf(whenAbsent)`, emitting the defined empty projection once.
- **Switch** → the prior connection's inner flow is cancelled, so its projections are dropped the
  instant the value changes.

This is what gives **cross-connection isolation for free** (AC #2): combined with #351 handing each
connection a *distinct* repo instance with its own projection state, a reader can never observe a
previous connection's data after a reconnect. The intervening `null` the coordinator always publishes
is belt; `flatMapLatest`'s cancel-old-on-new semantics are the load-bearing suspenders — isolation
would hold even on a hypothetical direct `repo1→repo2` emission.

### One-shots — snapshot-or-throw

```kotlin
private val live: ConversationRepository
    get() = currentRepository.value ?: throw IllegalStateException(NOT_CONNECTED)
// e.g. override suspend fun sendMessage(id, text) = live.sendMessage(id, text)
```

Each one-shot snapshots the live repo at call entry (`currentRepository.value`, read exactly once) and
delegates; with no connection live it throws `IllegalStateException("No live relay connection")`.
Snapshotting once is correct: if the connection drops *after* the snapshot, the delegate's own method
throws on its not-`Open` send — the facade need not re-check.

**Delegation is behaviour-neutral** (AC #4): a wired mutation (`createDiscussion`/`promote`/`sendMessage`),
a throwing stub (`archive`/`unarchive`/`rename`/`startNewSession`/`changeWorkspace` →
`UnsupportedOperationException` on the remote repo), and a wired error (`RelayErrorException`,
`IllegalArgumentException`) all propagate **verbatim** — the facade adds, suppresses, and translates
nothing.

### The not-connected contract

`IllegalStateException` is chosen deliberately to **match the delegate**: the remote repo's
`sendAndAwaitReply` already throws `IllegalStateException` when its pump is not `Open`
([`RemoteConversationRepository.kt:262`](remote-conversation-repository.md)). So a caller catches **one**
exception type whether the connection was absent at call time (facade throws) or dropped between the
call and the send (remote repo throws). This stays within the existing "[failures throw, they don't
`Result`-wrap](conversation-repository.md)" convention — the interface returns the domain entity
directly, so throwing is the only way to surface "not connected" without cascading a sealed-result
return type across every consumer and the Fake. It is a *defined, catchable, non-crashing* outcome, not
an app crash.

While no connection is live, **cold reads render the disconnected state as the empty projection**
(`emptyList()` / `null`) and resume on the next connection — they never error. This conflates "no
connection" with "connected, genuinely zero items" at this layer; a distinct connection-status UI
surface is a follow-up (consumed by #350's ViewModels if desired), out of scope here. Note the remote
repo emits nothing until its first snapshot, so the empty fallback covers only the no-connection gap,
not a connected-but-loading gap.

## Configuration

DI registration in `AppModule.kt` — a lazy `single` resolvable by its own type, **not** bound as
`ConversationRepository`:

```kotlin
single { StableConversationRepository(get<RelayRepositoryCoordinator>().currentRepository) }
```

Lazy (no `createdAtStart`) is correct — the facade is stateless and does no work at construction, and
nothing resolves it yet (ViewModels still get the Fake until #350). `get<RelayRepositoryCoordinator>()`
resolves the eager coordinator singleton; `currentRepository` is a stable `StateFlow` instance for the
coordinator's life. **`AppModule.kt:57` (`FakeConversationRepository … bind ConversationRepository::class`)
is untouched** (AC #5) — this slice registers the facade but does not flip the binding. That flip is
#350's one-line change: add `bind ConversationRepository::class` here and rewire the ViewModels.
(`AppModule.kt:57` is the untouched Fake binding; the facade registration is `AppModule.kt:84`.)

## Edge cases / limitations

- **No connection-status surface.** Disconnected renders as the empty projection (per the contract
  above), indistinguishable at this layer from a genuinely-empty connected repo. A distinct status
  surface is a deferred follow-up.
- **One-shot while absent throws** rather than queuing — there is no offline outbox; a not-connected
  mutation is the caller's to catch and retry.
- **No `distinctUntilChanged` on the outer switch** — `StateFlow` already conflates equal consecutive
  values and distinct repo instances are non-equal, so it would suppress nothing real.
- **Adds no retry or polling of its own.** Cold reads re-subscribe only when `currentRepository`
  transitions, whose cadence is bounded by the supervisor's capped-exponential backoff — the facade
  cannot drive a tight request loop against a hostile relay.

## Security

`security-sensitive` ticket; architect security review **PASS**. The facade sits *above* the
authenticated Noise channel and the repository's decode boundary — it moves only object references (the
live repo) and already-decoded domain values, introduces no new untrusted→trusted boundary, parses no
wire bytes, never collects `pump.inbound`/`transport.*`, and holds no key material. The one relevant
threat it could touch — **cross-connection data leakage** — is precluded by the `flatMapLatest` switch
(cancel-old-on-switch) plus #351's distinct-repo-per-connection, asserted by the cross-connection
isolation test. It emits no logs: the `NOT_CONNECTED` message is a fixed generic string carrying no
conversation id, message body, token, or host.

## Testing

`app/src/test/java/de/pyryco/mobile/data/repository/StableConversationRepositoryTest.kt` — JVM unit
tests (JUnit4 + `runTest`, hand fakes, no MockK), simpler than the coordinator test: it drives a
`MutableStateFlow<ConversationRepository?>` directly (no coordinator, no pump). `StandardTestDispatcher`
+ `runCurrent()` (not `advanceUntilIdle()` — see [`relay-repository-coordinator.md`](relay-repository-coordinator.md)),
cold reads collected on `backgroundScope`. A `RecordingConversationRepository` fake backs
`observeConversations` with a `null`-until-pushed `MutableStateFlow` (mirroring the real repo's cold
`filterNotNull()` projection, so a fresh connection emits nothing until its first snapshot) and records
each one-shot's args; its out-of-scope one-shots throw `UnsupportedOperationException` to exercise
pass-through. The eight tests map to the ACs, the key one being
`coldRead_afterReconnect_neverObservesPreviousConnectionData` — a ghost-push to the now-cancelled
`repoA` after the switch must surface nowhere (AC #2). No instrumented test — pure data-layer.

## Related

- Ticket: [#352](../codebase/352.md) — implementation record (files, line refs, patterns, lessons).
- Spec: `docs/specs/architecture/352-stable-conversation-repository-facade.md`.
- Contract: [`conversation-repository.md`](conversation-repository.md) — the interface this facade
  implements (a third implementation alongside the [Fake](conversation-repository.md) and the
  [Remote](remote-conversation-repository.md)).
- Input: [Relay repository coordinator](relay-repository-coordinator.md) ([#351](../codebase/351.md)) —
  publishes `currentRepository`, the churning observable this facade delegates over.
- Delegate: [Remote conversation repository](remote-conversation-repository.md) — the per-connection
  repo the facade switches to; the `IllegalStateException` not-connected type is matched to its
  not-`Open`-pump throw.
- Consumed by: **#350** (the flag-gated Fake↔Remote binding swap + ViewModel rewiring — this slice
  registers the facade but does not bind it as the default).
- DI: [Dependency injection](dependency-injection.md).

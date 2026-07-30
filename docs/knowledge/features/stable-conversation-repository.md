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
**no logs**. It is registered as its own resolvable DI type; as of #350 it is **flag-selected** as the
`ConversationRepository` binding by the `conversationRepositoryModule` selector — bound when
`BuildConfig.USE_RELAY_REPOSITORY` is on, with the Fake as the default-OFF binding (see
[`../codebase/350.md`](../codebase/350.md)).

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

It overrides **all** interface members — the 7 stream-shaped reads (`observeConversations`,
`observeMessages`, `observeLastMessage`, `observeStall` (#395), `observeQueue` (#460),
`observeApiRetry` (#593), **and** `recentWorkspaces`), the 12
suspend one-shots (`createDiscussion`, `promote`, `archive`, `unarchive`, `delete`, `rename`,
`startNewSession`, `changeWorkspace`, `sendMessage`, `createWorkspaceFolder`, `requestScreenSnapshot`,
**and** `setSessionSettings` ([#544](../codebase/544.md), the facade delegation [#543](../codebase/543.md)
deliberately deferred)), **and** the one capability property `mutationsSupported` (#507) — including every
member that ships a default body on the interface (`recentWorkspaces`, `createWorkspaceFolder`, `delete`,
`requestScreenSnapshot`; #375, `observeStall`; #395, `observeQueue`; #460, `observeApiRetry`; #593,
`setSessionSettings`; #543, **and**
`mutationsSupported`; #507), so delegation is faithful and nothing silently falls back to a default.
`setSessionSettings` follows the plain one-shot snapshot-or-throw shape below, like every other mutator —
it introduces no new delegation posture.

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
- `observeStall(id)` (#395) → `switchToLive(false) { … }` — no live connection reports "not stalled";
  `flatMapLatest` cancel-old-on-switch means a stall from a prior connection never leaks across a
  reconnect (each connection's remote repo starts with an empty stall set, #351). See
  [Stall state](stall-state.md).
- `observeQueue(id)` (#460) → `switchToLive(emptyList()) { … }` — no live connection reports an empty
  backlog; same cancel-old-on-switch isolation. See [Queued backlog](queued-backlog.md).
- `observeApiRetry(id)` (#593) → `switchToLive(ApiRetryStatus.NotRetrying) { … }` — no live connection
  reports "not retrying"; a retry state from a prior connection never leaks across a reconnect (each
  connection's remote repo starts with an empty `apiRetryByConversation` map, #351). See
  [API-retry status](api-retry-status.md).

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

### Capability reads — answer `false`, never throw (`mutationsSupported`, #507)

`mutationsSupported` is a third delegation posture, distinct from both the cold-read empty projection and
the one-shot throw:

```kotlin
override val mutationsSupported: Boolean
    get() = currentRepository.value?.mutationsSupported ?: false
```

A capability query must **always** return an answer — so, unlike the one-shots, it does **not** throw
`IllegalStateException` when no connection is live; it answers **fail-safe-deny `false`** (a gating consumer
should hide the impossible mutation actions rather than offer them). The `?: false` on the `.value` read is
the plain-`Boolean` analog of `observeStall`'s `switchToLive(false)` — the same *conceptual* "no connection
reports capability off" model, but `switchToLive` is `Flow`-typed and can't be reused for a scalar.

**It is a getter, not an initializer** — deliberately, for the same reason as [`live`](#one-shots--snapshot-or-throw):
the facade is a process-lifetime singleton constructed while `currentRepository.value` is `null`, so an
`override val mutationsSupported = currentRepository.value?.mutationsSupported ?: false` initializer would
latch `false` **forever** and never reflect a relay that connects later. The getter re-reads `.value` on
every access — the churn-back-to-`null` test proves it re-reads rather than snapshotting. Note the sole
consumer today, [`ThreadViewModel`](thread-screen.md), reads it **once at construction** (the mode is static
per build config), so a connection landing after the VM is built won't flip the captured snapshot — an
accepted, documented limitation, not this facade's concern. See [`../codebase/507.md`](../codebase/507.md).

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
surface is a deferred follow-up (the flag-ON production flip's concern, not #350's binding-only slice).
Note the remote
repo emits nothing until its first snapshot, so the empty fallback covers only the no-connection gap,
not a connected-but-loading gap.

## Configuration

DI registration in `AppModule.kt` — a lazy `single` resolvable by its own type, **not** bound as
`ConversationRepository`:

```kotlin
single { StableConversationRepository(get<RelayRepositoryCoordinator>().currentRepository) }
```

Lazy (no `createdAtStart`) is correct — the facade is stateless and does no work at construction. This
`single` registers the facade by its own type only; **what binds the `ConversationRepository` interface
to it is the #350 `conversationRepositoryModule` selector**, not a `bind` on this line.
`get<RelayRepositoryCoordinator>()` resolves the eager coordinator singleton; `currentRepository` is a
stable `StateFlow` instance for the coordinator's life.

As of #350 the selector binds this facade when `BuildConfig.USE_RELAY_REPOSITORY` is on (default OFF →
Fake):

```kotlin
single<ConversationRepository> {
    if (useRelay) get<StableConversationRepository>() else get<FakeConversationRepository>()
}
```

#352 (this slice) anticipated #350 as *"add `bind ConversationRepository::class` to this `single` and
rewire the ViewModels"* — it landed differently and more cleanly: a **separate selector module** (so the
binding is unit-testable in isolation) and **no ViewModel rewiring** (every consumer already resolves the
interface). See [`../codebase/350.md`](../codebase/350.md).

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
- Bound by: **[#350](../codebase/350.md)** (the flag-gated Fake↔Remote binding swap — the
  `conversationRepositoryModule` selector binds this facade when `USE_RELAY_REPOSITORY` is on; landed
  with no ViewModel changes, since consumers already resolve the interface).
- Delegated observable: [Stall state](stall-state.md) ([#395](../codebase/395.md)) — the `observeStall`
  read this facade forwards with `whenAbsent = false`, the reachability path that lets the thread
  ViewModel observe the live repo's stall state through this facade.
- Delegated observable: [Queued backlog](queued-backlog.md) ([#460](../codebase/460.md)) — the
  `observeQueue` read forwarded with `whenAbsent = emptyList()`.
- Delegated observable: [API-retry status](api-retry-status.md) ([#593](../codebase/593.md)) — the
  `observeApiRetry` read forwarded with `whenAbsent = ApiRetryStatus.NotRetrying`, the reachability path
  that will let the thread ViewModel observe the live repo's API-retry state once sibling #594 renders it.
- Delegated capability: `mutationsSupported` ([#507](../codebase/507.md)) — the fail-safe-deny `false`
  delegation (the third not-connected posture: answer, don't throw); consumed by no composable yet (#508).
- Delegated one-shot: `setSessionSettings` ([#544](../codebase/544.md)) — the plain snapshot-or-throw
  delegation [#543](../codebase/543.md) deferred to this facade's first caller, the
  [Status sheet](status-sheet.md) run-configuration controls; a not-connected change surfaces as this
  facade's `IllegalStateException`, which the ViewModel catches to revert + snackbar.
- DI: [Dependency injection](dependency-injection.md).

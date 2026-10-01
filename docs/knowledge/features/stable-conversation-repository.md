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
\#351's `currentRepository`), switching transparently as connections come and go, and exposing a defined
non-crashing surface while no connection is live.

Package: `de.pyryco.mobile.data.repository` (`StableConversationRepository.kt`), co-located with the
[contract](conversation-repository.md) it implements and the [coordinator](relay-repository-coordinator.md)
it reads from. Landed in [#352](../codebase/352.md) (split from #349). Portable, `android.*`-free, emits
**no logs**. It is registered as its own resolvable DI type and is the **normal app build's
`ConversationRepository` binding**. The `conversationRepositoryModule` selector reads
`BuildConfig.USE_RELAY_REPOSITORY`, generated from the `useRelayRepository` Gradle property
with a `true` default. `-PuseRelayRepository=false` selects the fake for a demo; tests and
previews can explicitly inject fake independently of that flag. See
[dependency injection](dependency-injection.md) and [build commands](../../../README.md#build).

## Where it sits in the Phase 4 stack

```
ViewModels  ◀── hold one stable ConversationRepository reference for their lifetime
    ▲
StableConversationRepository (#352)  ◀── this doc; identity fixed for the app's life
    │  flatMapLatest over currentRepository (cold reads) ; .value snapshot (one-shots)
    ▼
RelayConnectionRegistry.currentRepository  ◀── selected-host compatibility projection
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
    private val heldReadings: HostReadings? = null,  // (#1317) the coordinator's host-pairing-lifetime readings
) : ConversationRepository
```

**Held readings (#1317).** Five reads — `observeAnnouncedModel`, `observeSessionFacts`,
`observeContextUsage`, `observeUsageLimit`, `observeSlashCommandMenu` — take an optional `heldReadings` and
prefer it over the switch: `heldReadings?.observeX(id) ?: switchToLive<X?>(null) { it.observeX(id) }`. With
it, a reading survives a reconnect and the gap before it, and is dropped only when `heldReadings.close()`
runs — the coordinator's pairing-scoped clear, not a per-connection one. The compatibility singleton
(`AppModule.kt`'s `single { StableConversationRepository(get<RelayConnectionRegistry>().currentRepository) }`)
passes none, so it keeps the pre-#1317 switched behaviour: these five readings blank between connections,
same as every other cold read on that path. `ThreadDestinationFactory.repository` is the only caller that
supplies `heldReadings` (`bundle?.coordinator?.hostReadings`), since `ThreadViewModel` is the five
readings' only production consumer.

It overrides **all** interface members — the stream-shaped reads (`observeConversations`,
`observeMessages`, `observeLastMessage`, `observeStall` (#395), `observeQueue` (#460),
`observeApiRetry` (#593), `observeCompacting` (#596), `observeThinkingProgress` (#801),
`observeUsageLimit` (#802), **and** `recentWorkspaces`), the 16
suspend one-shots (`createDiscussion`, `promote`, `archive`, `unarchive`, `delete`, `rename`,
`startNewSession`, `changeWorkspace`, `sendMessage` (both overloads — the plain send and the
attachment-naming one, #830 — follow the identical snapshot-or-throw shape below), `createWorkspaceFolder`, `requestScreenSnapshot`,
`setSessionSettings` ([#544](../codebase/544.md), the facade delegation [#543](../codebase/543.md)
deliberately deferred), `requestHistory` (#623), `requestSystemPrompt`/`setSystemPrompt` (#823),
`setMuted` (#1000), **and**
`uploadAttachment` (#829, the one one-shot that does **not** follow the snapshot-or-throw shape below —
see [Uploads — snapshot-or-result](#uploads--snapshot-or-result-829))), **and** the one capability property
`mutationsSupported` (#507) — including every member that ships a default body on the interface
(`recentWorkspaces`, `createWorkspaceFolder`, `delete`, `requestScreenSnapshot` (#375),
`observeStall` (#395), `observeQueue` (#460), `observeApiRetry` (#593), `observeCompacting` (#596),
`observeThinkingProgress` (#801), `observeUsageLimit` (#802),
`setSessionSettings` (#543), `requestHistory` (#623), `requestSystemPrompt`/`setSystemPrompt` (#823),
`setMuted` (#1000), **and** `mutationsSupported` (#507)), so
delegation is faithful and nothing silently falls back to a default. `setSessionSettings`, `requestHistory`,
`requestSystemPrompt`/`setSystemPrompt` and `setMuted` all follow the
plain one-shot snapshot-or-throw shape below, like every other mutator — neither introduces a new
delegation posture; `requestHistory` forwards its `cursor`/`limit` verbatim, the same pass-through the
shape already gives every other multi-arg one-shot, `setSystemPrompt` forwards its `systemPrompt`
verbatim including a `null` clear — the facade neither pre-checks the 8192-byte limit nor short-circuits
an over-limit value; that stays the live repository's `require(...)` — and `setMuted` forwards its
`Boolean` verbatim, `true` and `false` alike, the same one-line hand-off `createChannel` uses.

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
- `observeCompacting(id)` (#596) → `switchToLive(false) { … }` — no live connection reports "not
  compacting"; a compaction state from a prior connection never leaks across a reconnect (each
  connection's remote repo starts with an empty `compactingConversations` set, #351). See
  [Compacting state](compacting-state.md).
- `observeThinkingProgress(id)` (#801) → `switchToLive<ThinkingProgress?>(null) { … }` — no live
  connection reports "no reading," the same absent value an unheard conversation produces, so a
  consumer has one absent case, not two. Here the switch is the **whole clearing mechanism for a
  reconnect**, not just plumbing: the reading has no falling edge on the wire, so a retained one would
  report the depth of a think that has since finished, and `flatMapLatest` dropping the previous
  connection's projection the instant the connection changes makes that structurally impossible with no
  clear written into any demux arm. See [Thinking-progress state](thinking-progress-state.md).
- `observeUsageLimit(id)` (#802) → with `heldReadings` (#1317), reads the host's held reading, kept across
  a reconnect and dropped only when the pairing ends; without it, `switchToLive<UsageLimitReading?>(null) {
  … }` — no live connection reports "nothing to read," the same absent value an unheard conversation
  produces. Either way, the reading already has two ways down on the live repo itself (a benign clearing
  frame, and the read-time expiry against `resetsAt`); the held or switched path adds the account-level
  **pairing**-scoped clear — a usage-limit posture belongs to an account, so dropping it on unpair/re-pair
  (held) or on every reconnect (switched, the compatibility singleton's path) stops one account's reading
  being attributed to the next. See [Usage-limit state](usage-limit-state.md).
- `observeAnnouncedModel(id)` / `observeSessionFacts(id)` (#890) and `observeContextUsage(id)` (#945) →
  the same held-or-switched shape as `observeUsageLimit`, `whenAbsent = null`. See [StatusSheet — running
  model and context window readings](status-sheet-readings.md) and [Thread composer footer § Context usage
  segment](thread-composer-footer-context-usage.md).
- `observeSlashCommandMenu(id)` (#882) → the same held-or-switched shape, `whenAbsent = null`. A host's
  commands are never offered for another's conversation either way. See [Remote conversation repository —
  the model-list and slash-command-list menu retentions](remote-conversation-repository-model-and-slash-command-menus.md).

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

The app binds this facade to the registry's selected-host projection. Its empty
fallback is unsuitable as input to a retained host list cache: it conflates
disconnect with an actual empty reply. The separate
[host snapshot source](dependency-injection-host-conversation-source.md#snapshot-lifetime) observes each
host's coordinator directly, retains rows through null repositories and reconnect
silence, and replaces them only on an actual list emission. This facade's
compatibility behavior remains unchanged.

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

### Uploads — snapshot-or-result (#829)

`uploadAttachment` is a **third one-shot posture**, distinct from the plain snapshot-or-throw shape
above. With a live repository it snapshots and delegates exactly like every other one-shot, so a
connection change mid-upload never moves it. But with **no** live repository it does not throw — it
returns a value, the same `AttachmentUploadResult` type the connected path can also produce:

```kotlin
override suspend fun uploadAttachment(conversationId: String, bytes: ByteArray, filename: String, mimeType: String): AttachmentUploadResult =
    currentRepository.value?.uploadAttachment(conversationId, bytes, filename, mimeType)
        ?: if (AttachmentUploadLimit.fits(bytes.size)) AttachmentUploadResult.ReconnectRequired else AttachmentUploadResult.TooLarge
```

The acceptance criteria make "no live connection" one of an upload's ordinary failure outcomes, on the
same footing as a mid-upload drop or a refused chunk — every one of those settles as a value the caller
already has to branch on, so the facade's not-connected case has to be a value in that same set rather
than an `IllegalStateException` a caller would need a second catch clause for. An oversized file still
reports `TooLarge`, not `ReconnectRequired`, even with nothing live — the local bound is checked before
connection state, matching the live repository's own order. See [Attachment upload](attachment-upload.md).

### Retrieval fetch — snapshot-or-value, no local bound to check (#899)

`fetchAttachment` follows the same snapshot-or-result shape as `uploadAttachment`, one level simpler:
there is no local bound to check against the disconnected case, since a retrieval's size claim only
exists once a chunk has arrived.

```kotlin
override suspend fun fetchAttachment(conversationId: String, attachmentId: String): AttachmentFetchResult =
    currentRepository.value?.fetchAttachment(conversationId, attachmentId) ?: AttachmentRetrievalResult.Unavailable
```

With no live repository this returns `Unavailable` — the same retryable `Failed` value a mid-stream
drop produces, not an exception. `retrieveAttachment` itself has no override here: screens read through
the host-bound `CachingConversationRepository`, not through this facade directly, so this facade only
needs the connection-level member. See [Attachment retrieval](attachment-retrieval.md).

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
connection" with "connected, genuinely zero items" at this layer; consumers use the separate
[connection status](connection-status.md) / [connection banner](connection-banner.md) surfaces
to distinguish connection state. Note the remote
repo emits nothing until its first snapshot, so the empty fallback covers only the no-connection gap,
not a connected-but-loading gap.

## Configuration

DI registration in `AppModule.kt` — a lazy `single` resolvable by its own type, **not** bound as
`ConversationRepository`:

```kotlin
single { StableConversationRepository(get<RelayConnectionRegistry>().currentRepository) }
```

Lazy (no `createdAtStart`) is correct — the facade is stateless and does no work at construction. This
`single` registers the facade by its own type only; **what binds the `ConversationRepository` interface
to it is the #350 `conversationRepositoryModule` selector**, not a `bind` on this line.
`get<RelayConnectionRegistry>()` resolves the eager registry singleton;
`currentRepository` is its stable projection of the latest-saved surviving host's
coordinator. It follows selection changes as well as that host's reconnects.

The selector binds this facade when `BuildConfig.USE_RELAY_REPOSITORY` is on, which is the
default since #631. Demo builds explicitly turn it off:

```kotlin
single<ConversationRepository> {
    if (useRelay) get<StableConversationRepository>() else get<FakeConversationRepository>()
}
```

\#352 (this slice) anticipated #350 as *"add `bind ConversationRepository::class` to this `single` and
rewire the ViewModels"* — it landed differently and more cleanly: a **separate selector module** (so the
binding is unit-testable in isolation) and **no ViewModel rewiring** (every consumer already resolves the
interface). See [`../codebase/350.md`](../codebase/350.md).

## Edge cases / limitations

- **No connection-status field on the facade.** Disconnected renders as the empty projection
  (per the contract above); connection status is supplied separately to the UI.
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

[#1317](https://github.com/pyrycode/pyrycode-mobile/issues/1317) added cases for the disconnected gap:
with `heldReadings` supplied and `currentRepository.value == null`, all five reads emit the held values
instead of the switched `null`; after `heldReadings.close()` they emit `null` like every other absent
case. Without `heldReadings` the facade is unchanged, so no new test duplicates the existing switched-`null`
coverage.

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
- Delegated observable: [Compacting state](compacting-state.md) ([#596](../codebase/596.md)) — the
  `observeCompacting` read forwarded with `whenAbsent = false`, the reachability path that will let the
  thread ViewModel observe the live repo's compaction state once sibling #597 renders it.
- Delegated observable: [Thinking-progress state](thinking-progress-state.md)
  ([#801](../../specs/architecture/801-thinking-progress-decode.md)) — the `observeThinkingProgress`
  read forwarded with `whenAbsent = null`; the reachability path that will let the thread ViewModel
  observe the live repo's thinking-progress reading once the unfiled rendering sibling consumes it, and
  the mechanism (via `flatMapLatest`) that drops a reading across a reconnect with no clear written into
  any demux arm.
- Delegated observable: [Usage-limit state](usage-limit-state.md)
  ([#802](../../specs/architecture/802-decode-rate-limited-usage-limit-state.md)) — the
  `observeUsageLimit` read forwarded with `whenAbsent = null`; the reachability path that will let the
  thread ViewModel observe the live repo's usage-limit reading once the unfiled rendering sibling
  consumes it, and the mechanism (via `flatMapLatest`) that gives the reading its account-level
  pairing-scoped clear on reconnect, on top of the live repo's own benign-frame clear and read-time
  expiry.
- Held readings: [Relay repository coordinator § `HostReadings`](relay-repository-coordinator.md)
  ([#1317](https://github.com/pyrycode/pyrycode-mobile/issues/1317)) — the host-pairing-lifetime instance
  this facade's `heldReadings` constructor parameter reads from, which lets `observeAnnouncedModel`,
  `observeSessionFacts`, `observeContextUsage`, `observeUsageLimit` and `observeSlashCommandMenu` survive a
  reconnect instead of switching to `null` in the gap.
- Delegated capability: `mutationsSupported` ([#507](../codebase/507.md)) — the fail-safe-deny `false`
  delegation (the third not-connected posture: answer, don't throw); consumed by no composable yet (#508).
- Delegated one-shot: `requestHistory` (#623) — the on-disk history page read, forwarded verbatim with
  the plain snapshot-or-throw shape; no new delegation posture. See [Remote conversation repository —
  screen snapshot, dequeue, interrupt and new session](remote-conversation-repository-control-sends.md#requesthistoryconversationid-cursor-limit--the-on-disk-history-page-read-623).
- Delegated one-shots: `requestSystemPrompt` / `setSystemPrompt` (#823) — the conversation-scoped
  system-prompt read and write, both forwarded verbatim with the plain snapshot-or-throw shape; the
  facade performs no interactive gating and no byte-limit check of its own, both of which stay on the
  live repository. See [Remote conversation repository — session settings, archive, delete and workspace
  change](remote-conversation-repository-conversation-writes.md#requestsystempromptconversationid--setsystempromptconversationid-systemprompt--the-system-prompt-read-and-write-823).
- Delegated one-shot: `setSessionSettings` ([#544](../codebase/544.md)) — the plain snapshot-or-throw
  delegation [#543](../codebase/543.md) deferred to this facade's first caller, the
  [Status sheet](status-sheet.md) run-configuration controls; a not-connected change surfaces as this
  facade's `IllegalStateException`, which the ViewModel catches to revert + snackbar.
- Delegated one-shot: `uploadAttachment` ([#829](https://github.com/pyrycode/pyrycode-mobile/issues/829)) —
  the sole one-shot with a **snapshot-or-result**, not snapshot-or-throw, no-connection case. See
  [Attachment upload](attachment-upload.md).
- Delegated one-shot: `fetchAttachment` ([#899](https://github.com/pyrycode/pyrycode-mobile/issues/899)) —
  the same snapshot-or-result shape as `uploadAttachment`, with no local bound to check; no-connection
  case is `AttachmentRetrievalResult.Unavailable`. `retrieveAttachment` has no override on this facade —
  screens read through the host-bound `CachingConversationRepository` instead. See [Attachment
  retrieval](attachment-retrieval.md).
- Delegated one-shot: `setMuted` ([#1000](https://github.com/pyrycode/pyrycode-mobile/issues/1000)) — the
  mute-flag write, forwarded verbatim with the plain snapshot-or-throw shape; no new delegation posture.
  See [Remote conversation repository — session settings, archive, delete and workspace
  change](remote-conversation-repository-conversation-writes.md#setmutedconversationid-muted--the-mute-flag-write-1000).
- DI: [Dependency injection](dependency-injection.md).

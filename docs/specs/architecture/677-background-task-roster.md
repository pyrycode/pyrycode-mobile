# #677 — Retain the background task roster on mobile

## Files read

- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` → `onInbound` (the `interactive`-gated arms, the `TYPE_QUESTION_SHOWN` arm is where the new arm sits), `questionBatches`, the constructor's `replayCursor` parameter, the `TYPE_*` constants in the companion — the per-connection seam this ticket extends.
- `app/src/main/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinator.kt` → `replayCursor`, `onConnection`, `teardownActive`, `questionBatches`, `observeQuestionBatch` — the host-lifetime owner and the switch-to-active-connection read shape (#822).
- `app/src/main/java/de/pyryco/mobile/data/repository/QueueProjection.kt` → `QueueProjection` — per-connection projection shape: own state, own decoder, drop-malformed-and-keep-collecting idiom, no logging.
- `app/src/main/java/de/pyryco/mobile/data/network/ReplayCursor.kt` → `ReplayCursor` — precedent for state the coordinator owns and threads into each repository so it outlives a reconnect.
- `app/src/main/java/de/pyryco/mobile/data/network/QuestionPayloads.kt` → strict DTO posture (every wire key required).
- `app/src/main/java/de/pyryco/mobile/data/network/InteractivePayloads.kt` → the `truncated_fields` convention (`List<String>?`, always-present key, `null` = nothing cut).
- `app/src/main/java/de/pyryco/mobile/data/model/QuestionBatch.kt` → domain types live in `data/model`, pure fold helpers beside them.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryQuestionTest.kt`, `RelayRepositoryCoordinatorTest.kt` → test harnesses (`FakeSessionPump`, `newEnv`, `openInteractiveConnection`) the new tests mirror.
- `../pyrycode/docs/protocol-mobile.md` § `background_task_started`, § `background_task_updated`, § `background_task_roster`, and "Reconcile on connect" — wire SSOT; cited, not restated.
- `../pyrycode/internal/protocol/interactive.go` → `BackgroundTaskStartedPayload`, `BackgroundTaskUpdatedPayload`, `BackgroundTaskRosterPayload`, `BackgroundTask` — no key is `omitempty`, so every key is always present; `tasks` is never `null`.
- `../pyrycode/internal/protocol/testdata/background_task_*.json` — golden fixtures the DTO tests decode verbatim.
- `../pyrycode-desktop/src/renderer/src/store/backgroundTaskRosterStore.ts` → `setRoster`, `setStartedTask`, `setUpdatedTask`, `selectLiveTaskCountFor` — merge rules followed, with the two deliberate differences named under Design.
- `docs/knowledge/features/relay-repository-coordinator-seams-and-passthroughs.md` § "Question-batch projection (#822)" — `Eagerly` for the same reason as `currentModal`; the per-conversation read is the only one a panel should use.

## Design source

**Figma:** N/A — data layer only; the Actions-menu panel and count that read this store are #678.

## Context

The daemon sends three `interactive`-gated frames about work claude left running past its turn. Mobile drops them. This ticket holds them per host and per conversation and derives a live-task count, for #678 to render. They are daemon state, not turn content, so nothing here touches the thread timeline.

The one thing that must outlive a connection is the knowledge of which tasks finished: claude does not reliably send a roster after a completion, and the daemon re-sends its retained roster on every reconnect, which mobile does on every return from the background. Without a reconnect-surviving finished mark a completed task would reappear as live on every foreground.

## Design

### Files

| File | New/modified | Content |
|---|---|---|
| `data/network/BackgroundTaskPayloads.kt` | new | Four `internal @Serializable` DTOs |
| `data/model/BackgroundTask.kt` | new | Three public domain types |
| `data/repository/BackgroundTaskProjection.kt` | new | `FinishedBackgroundTasks` (host lifetime) + `BackgroundTaskProjection` (per connection) |
| `data/repository/RemoteConversationRepository.kt` | modified | constructor param, one `onInbound` arm, `backgroundTasks` read, three type constants |
| `data/repository/RelayRepositoryCoordinator.kt` | modified | owns `FinishedBackgroundTasks`, threads it in, exposes the reads |

### Wire DTOs (`BackgroundTaskPayloads.kt`)

Strict, like `QuestionPayloads.kt`: every key the daemon always emits is required; `truncated_fields` is `List<String>?` (explicit `null` = nothing cut, the `InteractivePayloads` convention). Unknown keys tolerated through `MobileJson`.

- `BackgroundTaskStartedPayloadDto(conversationId, taskId, toolCallId, description, taskType, truncatedFields)`
- `BackgroundTaskUpdatedPayloadDto(conversationId, taskId, patch, status, summary, truncatedFields)`
- `BackgroundTaskRosterPayloadDto(conversationId, tasks: List<BackgroundTaskRowDto>, droppedTasks: Int)`
- `BackgroundTaskRowDto(taskId, taskType, description, truncatedFields)`

No mapper extensions are exported from this file; the projection maps DTO → domain privately so the repository file gains no new `network` imports.

### Domain types (`data/model/BackgroundTask.kt`)

```kotlin
data class BackgroundTaskUpdate(val patch: String, val status: String, val summary: String, val truncatedFields: List<String>?)

data class BackgroundTask(
    val taskId: String,
    val toolCallId: String?,          // null until a started frame names it (a roster row carries none)
    val taskType: String,
    val description: String,
    val truncatedFields: List<String>?, // the started frame's or the roster row's own report
    val latestUpdate: BackgroundTaskUpdate?, // last mid-life update (empty status), whole
    val finish: BackgroundTaskUpdate?,       // last terminal update (non-empty status), whole
    val isFinished: Boolean,
)

data class BackgroundTaskRoster(val tasks: List<BackgroundTask>, val droppedTasks: Int) {
    val liveCount: Int // tasks.count { !it.isFinished } + droppedTasks, saturating at Int.MAX_VALUE
}
```

Every string is an inert display string: never parsed (`patch` included), evaluated, executed, used as a key other than `taskId`/`conversationId`, or logged.

`isFinished` can be `true` with `finish == null`: after a reconnect only the finished id carries over, not the terminal frame's `status`/`summary` (ticket Technical Notes).

The two update slots follow the wire's "two lines, disjoint fields" rule: a terminal frame never erases the last mid-life `patch`, and a later mid-life frame never erases `status`/`summary`. Each slot holds its own frame's `truncatedFields` (AC 2).

### `FinishedBackgroundTasks` (host lifetime)

The `ReplayCursor` analogue. `conversationId -> Set<taskId>` in a `MutableStateFlow`, written through `update {}`.

- `fun mark(conversationId: String, taskId: String)`
- `fun contains(conversationId: String, taskId: String): Boolean`
- `fun retainOnly(conversationId: String, taskIds: Set<String>)` — called by a roster; forgets ids the roster omits, so the set stays bounded.

Owned by `RelayRepositoryCoordinator` (one per host), threaded into each `RemoteConversationRepository` like `replayCursor`; the repository's constructor default is a throwaway instance so existing constructions compile unchanged.

### `BackgroundTaskProjection` (per connection)

`internal class BackgroundTaskProjection(private val finished: FinishedBackgroundTasks)`, one per repository, like `QueueProjection`.

- `val rosters: StateFlow<Map<String, BackgroundTaskRoster>>` — absence of a key = nothing reported; an empty `BackgroundTaskRoster` = an explicit empty roster (AC 1).
- `fun apply(envelope: Envelope)` — decodes by `envelope.type`; any `IllegalArgumentException` drops the one frame unlogged.
- Private `pending: conversationId -> taskId -> (latestUpdate, finish)` holds updates for a task the conversation's set does not hold yet, so the join is order-independent. Collector-confined (single writer, like the siblings).

Merge rules (desktop's, except where noted):

- **started** — upsert the task in the conversation's set (creating the conversation's entry with `droppedTasks = 0` if absent). Descriptive fields come from the started frame; update slots are kept from the held record, else taken from `pending`.
- **updated** — non-empty `status` → `finished.mark(...)` always (even for an unknown task) and the frame fills `finish`; empty `status` → the frame fills `latestUpdate`. If the task is held, replace that slot and recompute `isFinished`; otherwise stash in `pending`. An update alone never creates a conversation entry (it would read as an empty roster, a fact the daemon did not state).
- **roster** — replaces the conversation's set with the rows, in wire order; `droppedTasks` replaced. A negative `dropped_tasks` is out of contract and drops the whole frame. Rows repeating a `task_id` collapse to the first occurrence, so every consumer can key by `taskId`. For each row: a held record with a non-null `toolCallId` is kept whole (the started frame's full-length description wins over the row's tighter cap — desktop's rule); otherwise the row's fields, carrying update slots from the held record or `pending`. Tasks the roster omits are dropped. `pending` for the conversation is cleared, and `finished.retainOnly(conversation, rowIds)`.
- `isFinished` = `finish != null || finished.contains(conversationId, taskId)`, recomputed for every task the fold touches. A roster that re-lists a finished task therefore keeps it finished (AC 4), including the reconciled roster on a new connection.

**Deliberate differences from desktop:**
1. `setUpdatedTask` there drops `status`/`summary` (desktop#1558); here they are kept on `finish`.
2. Desktop matches `completed`/`failed`/`stopped` exactly; here any non-empty `status` finishes a task (ticket AC 4, wire: "test `status != ""`").
3. Desktop's later #1563 holds a started task no roster has listed outside the visible set (`unlistedStarts`). This ticket's AC 1 says the three frames maintain *one* set, so a start is listed immediately. If #678 finds foreground starts inflate the count, that is a follow-up.

### Repository (`RemoteConversationRepository`)

- New constructor param `finishedBackgroundTasks: FinishedBackgroundTasks = FinishedBackgroundTasks()`.
- `private val backgroundTaskProjection = BackgroundTaskProjection(finishedBackgroundTasks)`.
- `val backgroundTasks: StateFlow<Map<String, BackgroundTaskRoster>>` — on the concrete repository only, like `questionBatches`.
- One `onInbound` arm for `TYPE_BACKGROUND_TASK_STARTED`, `TYPE_BACKGROUND_TASK_UPDATED`, `TYPE_BACKGROUND_TASK_ROSTER`, behind the `interactive` gate, calling `backgroundTaskProjection.apply(envelope)`. Clears no stall, folds no thread row. `background_task_progress` stays unhandled (out of scope).

### Coordinator (`RelayRepositoryCoordinator`)

- `internal val finishedBackgroundTasks = FinishedBackgroundTasks()`, passed in `onConnection`. `teardownActive` never touches it.
- `val backgroundTasks: StateFlow<Map<String, BackgroundTaskRoster>>` — `activeConnection.flatMapLatest { it?.repo?.backgroundTasks ?: flowOf(emptyMap()) }`, `stateIn(scope, Eagerly, emptyMap())`, the `questionBatches` shape.
- `fun observeBackgroundTasks(conversationId: String): Flow<BackgroundTaskRoster?>` — `null` = nothing reported.
- `fun observeLiveBackgroundTaskCount(conversationId: String): Flow<Int>` — `liveCount`, `0` when nothing reported.

Host isolation is structural: one coordinator per host, and both the per-connection sets and the finished set are keyed by `conversationId` inside it.

## State + concurrency model

- No new coroutine. All writes happen on the repository's single inbound collector (connection child scope). `FinishedBackgroundTasks` uses `MutableStateFlow.update` because a torn-down connection's collector can finish one fold while the next connection's starts — the `ReplayCursor` posture.
- The coordinator's `backgroundTasks` runs `Eagerly` on the coordinator scope (cancelled by `close`), so frames that arrive before #678 subscribes are held.
- Reconnect: `teardownActive` nulls `activeConnection` → the read goes to `emptyMap()`; the new repository's projection starts empty and only its own frames rebuild it (AC 1); the finished ids carry over.

## Error handling

Malformed payload (missing/wrong-typed key, `null` `tasks`) → `IllegalArgumentException` (`SerializationException` ⊂ it) → the one frame is dropped, state unchanged, collector keeps running. The exception is discarded unlogged (kotlinx messages can quote input). Non-`interactive` connection → frames ignored before decoding. No UI surface.

Logging: none, matching `QueueProjection` / the question arm. Every field is claude-authored text or an id; the counts alone would say nothing actionable.

## Testing strategy

Unit tests only (no UI, no operator-facing flow in this ticket → no rung-3 scenario; #678 carries the operator-facing surface):

- `BackgroundTaskPayloadsTest` — decodes the daemon's five golden fixtures (started, updated, updated_terminal, roster, roster_empty) verbatim; a missing required key fails; `"tasks":null` fails.
- `BackgroundTaskProjectionTest` (projection + `FinishedBackgroundTasks` directly, no coroutines needed):
  - start then complete; start then fail; two starts then one complete (count 1); unknown non-empty status counts as finished;
  - roster re-lists a completed task → stays finished, count excludes it;
  - same re-listing on a reconnect (new projection, shared `FinishedBackgroundTasks`) → finished, `finish == null`, count 0;
  - roster replaces set and `droppedTasks`; omitted task dropped (not finished); finished id forgotten when omitted;
  - order independence: roster before start (started fields win on arrival, then roster keeps them); terminal update before start;
  - truncation: started / row `truncatedFields` on the task; update `truncatedFields` on its slot; mid-life after terminal keeps `status`/`summary`;
  - nothing reported (`null`) vs empty roster; update alone creates no entry; conversation isolation;
  - `liveCount` includes `droppedTasks` and saturates instead of overflowing; malformed frame changes nothing;
  - negative `dropped_tasks` drops the frame; a duplicated `task_id` in one roster yields one task.
- `RemoteConversationRepositoryBackgroundTaskTest` — the arm: frames fold into `backgroundTasks` on an interactive connection; ignored without `interactive`; malformed frame then a valid one (collector survives).
- `RelayRepositoryCoordinatorTest` additions — held before any subscriber; per-conversation read + live count; finished mark survives a reconnect whose reconciled roster re-lists the task while other state resets; per-host isolation with the same conversation id.

## Documentation handoff

The ticket carries no Documentation handoff section. Pending for the documentation stage: a feature overview entry for the background-task roster store (likely beside `relay-repository-coordinator-seams-and-passthroughs.md` § "Question-batch projection (#822)"), including the three deliberate desktop differences above.

## Open questions

1. Does a started frame for a foreground Bash call (never rostered) inflate the count until the next roster? Resolved for this ticket by AC 1 (one set); flagged for #678.

## Security review

**Verdict:** PASS (after one revision: the first pass found two MUST FIX items, both folded into Design above, and the re-run from the top found none left)

**Findings:**

- [Trust boundaries] MUST FIX, fixed in the plan: a roster repeating a `task_id` would have put two tasks with one id in the list, so #678 would crash on a `LazyColumn` duplicate key. A buggy or hostile daemon could cause that with one frame. Rows now collapse to the first occurrence in `BackgroundTaskProjection`'s roster fold. A started frame for a held id upserts in place.
- [Trust boundaries] MUST FIX, fixed in the plan: `dropped_tasks` is a daemon-asserted `int`. A negative value would lower or negate `liveCount`, and a value near `Int.MAX_VALUE` would overflow it. A negative value now drops the frame, and `liveCount` saturates.
- [Trust boundaries] The only untrusted-to-typed boundary is `BackgroundTaskProjection.apply`, which uses strict DTOs through `MobileJson`. Downstream code holds only domain types. `description`, `patch`, `status` and `summary` stay inert `String`s. They are never parsed, executed, used as a key or logged. `taskId` and `conversationId` are the only keys. The finished set is keyed by conversation and task, so an id in one conversation cannot finish a task in another. Host isolation is structural because there is one coordinator per host.
- [Trust boundaries] OUT OF SCOPE, deferred to #678: rendering. The daemon caps every string at construction, and the transport's frame cap bounds the rest. The store keeps what arrived. #678 must render these strings as plain length-bounded text and never as a URL, a link, markup or a shell action. `description` and `summary` can be literal command lines.
- [Tokens] No findings. No token, key or credential is created, stored or read. Task and conversation ids are not credentials.
- [File / storage] No findings. The store is in memory only and writes nothing to disk. That no-disk design is why the finished mark is lost on a process restart, which is the ticket's stated known limit.
- [Android attack surface] No findings. The ticket adds no component, intent, deep link, push path or WebView.
- [Crypto] No findings. The frames arrive inside the existing Noise session, and nothing here touches keys or nonces.
- [Network & I/O] No findings. The change adds no outbound send and no new socket. Inbound frames arrive through the existing size-capped transport. SHOULD FIX, accepted with reasoning: the task set, `pending` and the finished set grow with task ids until a roster prunes them. That growth is bounded by the number of tasks claude actually starts. Each id is capped by the daemon. A hostile authenticated daemon can already grow the thread store without bound, so this adds no new class of exhaustion.
- [Logs] No findings as designed. Nothing logs, and caught exceptions are discarded without reading `message`, because kotlinx messages can quote the input. The verifier should confirm that the diff contains no `Log`, `RelayLog` or `e.message` in the new code.
- [Concurrency] No findings. No coroutine is launched. Writes run on the single inbound collector. `FinishedBackgroundTasks` writes through `MutableStateFlow.update` because a torn-down connection's collector can finish one fold after the next connection starts. The worst case is one stale `retainOnly` or `mark` from that connection, which is the same daemon state and does not cross hosts. The coordinator read is a per-host hot `StateFlow`. #678 must use the per-conversation reads to avoid showing one conversation's tasks in another.
- [Threat model] Hostile daemon frames are decoded strictly, and a malformed frame is dropped while the collector survives. A hostile relay cannot forge or read frames inside Noise. It can drop them, which only delays a finish until a later terminal update or roster. That is an availability effect, not a leak.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-23

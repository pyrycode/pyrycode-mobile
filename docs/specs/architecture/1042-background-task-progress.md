# #1042 — Retain background task progress on mobile

## Files read

- `app/src/main/java/de/pyryco/mobile/data/network/BackgroundTaskPayloads.kt` → `BackgroundTaskStartedPayloadDto`, `BackgroundTaskUpdatedPayloadDto` — the strict, every-key-required DTO posture the new payload follows.
- `app/src/main/java/de/pyryco/mobile/data/model/BackgroundTask.kt` → `BackgroundTask`, `BackgroundTaskUpdate` — the task record progress rides on; `BackgroundTaskUpdate` is the "one frame held whole, with its own `truncatedFields`" shape to mirror.
- `app/src/main/java/de/pyryco/mobile/data/repository/BackgroundTaskProjection.kt` → `BackgroundTaskProjection.apply`, `applyStarted`, `applyUpdated`, `applyRoster`, `rowTask`, `task`, `withSlots`, the private `Slots` and `pending`; `FinishedBackgroundTasks.contains` — the gate, join, pending slots and malformed-frame drop this ticket extends.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` → the `TYPE_BACKGROUND_TASK_*` arm of `onInbound` and the `TYPE_BACKGROUND_TASK_*` constants — the `interactive` gate.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/BackgroundTaskPanel.kt`, `app/src/sharedTest/.../BackgroundTaskPanelTest.kt` → the only other `BackgroundTask(...)` constructor calls (preview + test helper, one positional). A defaulted trailing field keeps them compiling untouched.
- `app/src/test/.../BackgroundTaskProjectionTest.kt`, `RemoteConversationRepositoryBackgroundTaskTest.kt`, `data/network/BackgroundTaskPayloadsTest.kt` — the test fixtures and helpers the new tests extend.
- `docs/specs/architecture/677-background-task-roster.md` — the prior design and its security review.
- Wire SSOT: pyrycode `docs/protocol-mobile.md` § `background_task_progress`; fixture `internal/protocol/testdata/background_task_progress.json`.

In-flight overlap: `feature/1049` adds an unrelated override to `RemoteConversationRepository`; no dependency, build through it.

## Design source

N/A — data only. The panel reads progress in a follow-up ticket; no UI changes here.

## Context

The daemon's fourth background-task frame, `background_task_progress`, says what a running task is doing right now. Mobile currently falls through to the unknown-type path and drops it. This ticket decodes it and keeps the latest one on the `BackgroundTask` the #677 store already carries per host and per conversation, so the follow-up panel ticket only has to read a field.

## Design

**Wire type** (`BackgroundTaskPayloads.kt`): `BackgroundTaskProgressPayloadDto` with all nine keys required — `conversation_id`, `task_id`, `description`, `subagent_type`, `last_tool_name` (strings), `total_tokens`, `tool_uses`, `duration_ms`, and nullable `truncated_fields`. The counters decode as `Long`: the daemon's `int` is Go's 64-bit int, and a `Long` keeps a legitimate large reading (a long `duration_ms`, a large `total_tokens`) from failing the frame.

**Model** (`BackgroundTask.kt`):

```kotlin
data class BackgroundTaskProgress(
    val description: String,      // current activity, never the task's opening description
    val subagentType: String,
    val lastToolName: String,
    val totalTokens: Long,
    val toolUses: Long,
    val durationMs: Long,
    val truncatedFields: List<String>?,  // this frame's own cut report
)
```

`BackgroundTask` gains a trailing `val progress: BackgroundTaskProgress? = null`. The default keeps both existing constructor sites unchanged. The task's own `description` and `truncatedFields` are never touched by a progress frame.

**Projection** (`BackgroundTaskProjection.kt`):

- `apply` gains a `TYPE_BACKGROUND_TASK_PROGRESS` branch decoding the DTO into `applyProgress`. The existing `IllegalArgumentException` catch (kotlinx's `SerializationException` is one) covers a malformed payload.
- `Slots` gains `progress: BackgroundTaskProgress? = null`, so a held task, a pending (not-yet-held) task and the joins all carry progress through the paths #677 already built: `takePending` in `applyStarted`, `waiting` in `rowTask`, and `pending.remove(conversationId)` in `applyRoster` discarding the pending progress of a task the roster omits.
- `applyProgress(dto)`:
  1. If `finished.contains(conversationId, taskId)` → ignore. This covers a task finished on this connection and on an earlier one, and a pending terminal update (a terminal update marks `finished` before anything else).
  2. If the conversation has no roster or does not hold the task → store in the task's pending slot, replacing any earlier pending progress. Never creates a roster.
  3. Otherwise replace the held task's progress whole and publish.
- **Finished carries no progress** as one invariant in the two places a `BackgroundTask` is built, `task` and `withSlots`: `progress = if (isFinished) null else slots.progress`. `Slots.with(update, terminal)` also drops `progress` when `terminal`, so a pending slot does not hold a cleared value either. So a terminal update clears progress, and a roster relisting a task marked finished (this connection or an earlier one) never shows progress.
- Replacement is whole: no field is merged or summed, and a lower counter than the last reading is taken as sent.

**Repository** (`RemoteConversationRepository.kt`): a `TYPE_BACKGROUND_TASK_PROGRESS = "background_task_progress"` constant, added to the existing background-task arm of `onInbound`, so it sits behind the same `CAPABILITY_INTERACTIVE` check and reaches no thread row and clears no stall.

## State + concurrency model

Unchanged from #677: no coroutine launched; every write runs on the single inbound collector; `mutableRosters` is published through `MutableStateFlow.update`; `pending` is collector-confined. In memory only; after a reconnect the fresh projection rebuilds progress from new frames, and `FinishedBackgroundTasks` keeps a task finished on an earlier connection from taking progress.

## Error handling

A malformed payload (missing key, wrong type, `null` string) throws inside `MobileJson.decodeFromJsonElement`, is caught by `apply`'s existing catch and discarded unread — the exception message can quote the input. The collector keeps running. Nothing logs.

## Testing strategy

Unit tests only (pure fold, synchronous `apply`):

- `BackgroundTaskPayloadsTest`: the daemon fixture decodes every field; a missing counter key fails the frame; a string in place of a counter fails the frame.
- `BackgroundTaskProjectionTest`:
  - join: started then progress → task holds progress; opening `description` and `truncatedFields` unchanged, progress keeps its own `truncatedFields`.
  - replacement: two frames → the second whole, including a lower `tool_uses`/`total_tokens` reading.
  - out of order: progress before started joins on start; progress before a roster listing the task joins on the roster; progress alone creates no roster entry; a roster omitting the task discards it (a later start shows no progress).
  - finished: terminal update clears progress; progress after the terminal is ignored; progress for a task finished on an earlier connection (new projection, shared `FinishedBackgroundTasks`) is ignored after a roster relists it; progress then terminal both pending → start shows finished with no progress.
  - malformed progress payload changes nothing.
- `RemoteConversationRepositoryBackgroundTaskTest`: an `interactive` connection folds a progress frame; a non-interactive one ignores it; a malformed progress frame is dropped and a later frame still folds.

No rung-3 scenario: this is a data-layer ticket with no operator-facing flow; the panel follow-up owns that.

## Documentation handoff

The ticket names no documentation requirement. Pending for the documentation stage: fold the progress frame into `docs/knowledge/features/` for the background-task store (the #677 overview), noting the finished-carries-no-progress invariant.

## Open questions

None.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings. The only untrusted-to-typed boundary stays `BackgroundTaskProjection.apply`, via the strict `BackgroundTaskProgressPayloadDto` through `MobileJson`. `description`, `subagentType` and `lastToolName` are held as inert `String`s in `BackgroundTaskProgress`: never parsed, used as a key, logged, or put in an exception message. `conversationId` and `taskId` are the only keys, and the finished check is keyed by both, so a frame in one conversation cannot attach to another's task. Host isolation is structural (one coordinator and one `FinishedBackgroundTasks` per host).
- [Trust boundaries] No findings on the counters. They decode as `Long`, so no Kotlin arithmetic here can overflow; the store does no arithmetic on them at all. A negative or non-monotonic reading is taken as sent per the wire contract.
- [Trust boundaries] OUT OF SCOPE, deferred to the panel follow-up ticket: rendering. `description` names host file paths in the captured frames and arrives repeatedly for one row. The panel must render all three strings as plain, length-bounded text (never markup, a link, a URL or an action), and must tolerate negative or decreasing counters.
- [Tokens] No findings. No token, key or credential is created, stored or read.
- [File / storage] No findings. In memory only; nothing is written to disk.
- [Android attack surface] No findings. No component, intent, deep link, push path or WebView.
- [Crypto] No findings. The frame arrives inside the existing Noise session.
- [Network & I/O] SHOULD FIX, accepted with reasoning (same as #677): a hostile daemon can put progress for many unknown task ids into `pending` until a roster for the conversation clears it. Repeated frames for one task replace rather than grow, so growth is bounded by distinct task ids, each capped by the daemon, inside the transport's frame cap. An authenticated hostile daemon can already grow other stores this way; no new class of exhaustion.
- [Logs] No findings as designed. Nothing logs; the caught exception is discarded unread. The verifier should confirm the diff adds no `Log`, `RelayLog` or `e.message`.
- [Concurrency] No findings. No coroutine; writes on the single inbound collector; `FinishedBackgroundTasks` is only read here.
- [Threat model] Hostile daemon frames decode strictly and a malformed one is dropped with the collector alive. A hostile relay can drop progress frames, which only leaves stale progress until the next frame or the finish — the wire already says absence proves nothing. A stale progress on a task that finished is prevented by the finished invariant, not by frame order.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-25

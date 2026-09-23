# #812 — retain claude's elapsed-seconds reading on an open tool row

## Files read

- `app/src/main/java/de/pyryco/mobile/data/network/InteractivePayloads.kt` → `ToolDeniedPayloadDto`, `toDenial`, `ApiRetryPayloadDto` — the decode seam; `ToolDeniedPayloadDto` is the shape this frame copies (a thread-store-only verb, not a `LiveSessionEvent`), and `ApiRetryPayloadDto`'s KDoc names the tree decoder's quoted-primitive latitude, which the malformed-frame tests must not lean on.
- `app/src/main/java/de/pyryco/mobile/data/model/Message.kt` → `ToolCall`, `ToolCallStatus` — the retained shape; every field after the first three is defaulted so constructions need no cascade.
- `app/src/main/java/de/pyryco/mobile/data/repository/HistoryPageReducer.kt` → `withToolResult`, `withToolDenied`, `withHistoryEntry`, `indexOfMessage` — the shared folds both lanes run, and the replay lane's per-type arms with their `interactive` gate.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` → `onInbound`'s `TYPE_TOOL_DENIED` arm, `applyToolDenied`, the `TYPE_TOOL_*` constants — the live lane's demux and the precedent fold wrapper (never mints a slice for an unseen conversation).
- `app/src/test/java/de/pyryco/mobile/data/repository/HistoryPageReducerTest.kt` → the `#811` block and its `entry` / `toolUsePayload` / `toolResultPayload` fixtures — where the replay-lane tests go.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryQuestionTest.kt` → `FakeSessionPump` — the self-contained split-test-file pattern the live-lane tests follow.
- `docs/knowledge/features/live-tool-call.md` § "Denied", § "Tolerating a misbehaving stream", § "Security" — the `Role.Tool`-namespaced join, the no-row-no-op rule, the unbounded-growth guard, and the "folds log nothing" posture.
- `../pyrycode/docs/protocol-mobile.md` § `tool_progress` — the wire contract (cited, not restated).
- `../pyrycode/cmd/pyry/interactive_turn_v2.go` → `interactiveTurnEmitterV2.emit` — every mapped turn event, `tool_progress` included, is appended to the durable conversation history before the wire fan-out, so **the frame is replayed in history** and the replay arm must fold it, not merely tolerate it.
- `../pyrycode-desktop/src/renderer/src/store/threadTimeline.ts` → the `toolProgress` / `toolDenied` reducer cases, and `store/toolProgress.test.ts` — desktop applies progress only to an open call, clears the reading on result and on denial, and ignores late progress.

## Design source

**Figma:** N/A — data-layer only; the ticket's scope boundary hands formatting and display to #658.

## Context

A tool row sits static between `tool_use` and `tool_result`. The daemon now forwards claude's own elapsed-seconds heartbeat for an open call as `tool_progress` (pyrycode#2324); mobile drops it as an unknown verb on the live lane and in the history reducer's `else`. This ticket retains the latest reading on the row. It does not render it (#658).

Deliberate non-changes:

- **Not a `LiveSessionEvent`**, for #811's reason: nothing on the live stream consumes it, and a new subtype would force arms into `ThreadViewModel`'s exhaustive `when`s. It folds into the thread store only.
- **Does not clear the stall projection.** Whether a heartbeat counts as forward progress for the stall banner is a product question outside this ticket's AC; the `tool_denied` arm does not clear it either.
- **Not persisted to the disk cache** (`FileConversationCache.CachedToolCall`), the `denial` / `inputFields` precedent; a row restored from cache has no reading, which is the "absence proves nothing" state anyway.

No ADR warranted: this is one more verb on an established seam.

## Design

**Model.** `ToolCall` gains a trailing defaulted field:

```kotlin
val elapsedSeconds: Int? = null   // claude's latest signed reading; null = none received for an open call
```

`null` and `0` are different: zero is a reading claude sent. Invariant: **non-null only while `status == Running`.** KDoc states the verbatim rule (no clamping, no subtraction, not timing evidence, absence proves nothing) and that rendering belongs to #658.

**Decode.** `ToolProgressPayloadDto` in `InteractivePayloads.kt`, all four fields strict-required (the daemon pins all four keys with no `omitempty`):

```kotlin
@Serializable
internal data class ToolProgressPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("turn_id") val turnId: String,
    @SerialName("tool_use_id") val toolUseId: String,
    @SerialName("elapsed_seconds") val elapsedSeconds: Int,
)
```

There is no domain type to map into — the retained value is one `Int` — so the shared fold takes the decoded DTO directly and is the single mapper both lanes call.

**Fold** (beside `withToolDenied` in `HistoryPageReducer.kt`):

```kotlin
internal fun List<ThreadItem>.withToolProgress(progress: ToolProgressPayloadDto): List<ThreadItem>
```

Find the `Role.Tool` row whose id is `progress.toolUseId` (the existing `indexOfMessage` namespacing). No row → `this`. Row not `Running` (`Done`, `Failed`, `Denied`) → `this`: a late heartbeat neither reopens nor overwrites a closed row. Same reading already held → `this`. Otherwise copy `elapsedSeconds = progress.elapsedSeconds` in place, position, timestamp and every other field untouched. `turnId` is not part of the match, consistent with the other tool folds (see live-tool-call.md § "Edge cases": `toolUseId` is globally unique per the wire SSOT).

**Closing clears the reading.** `withToolResult` and `withToolDenied` each also set `elapsedSeconds = null`. This is desktop's behaviour, and it keeps the invariant above so a `Done` row never carries a stale "running for 60s" number that #658 would have to remember to ignore. Each is one extra named argument in an existing `copy`.

**Live lane** (`RemoteConversationRepository`):

- `TYPE_TOOL_PROGRESS = "tool_progress"` beside `TYPE_TOOL_DENIED`.
- An `onInbound` arm beside `TYPE_TOOL_DENIED`, inside the same `CAPABILITY_INTERACTIVE` gate, calling `applyToolProgress(envelope)`.
- `applyToolProgress` mirrors `applyToolDenied`: decode in a `try`/`catch (IllegalArgumentException)` that returns (exception discarded, nothing logged); one `threadByConversation.update` that returns `current` unchanged when the frame's conversation holds no rows, else writes `rows.withToolProgress(dto)` into that conversation only.

**Replay lane** (`withHistoryEntry`): a `TYPE_TOOL_PROGRESS` arm beside `TYPE_TOOL_DENIED`, gated on `interactive`, decoding `ToolProgressPayloadDto` and calling `withToolProgress`. A malformed entry costs one entry via the function's existing `try`. Routing is the caller's, as for every replay arm: the payload's `conversation_id` is never read on this lane.

## State + concurrency model

No new jobs, flows or scopes. Both writes happen on the existing single inbound collector (live) or inside the existing pure page reduction (replay). The live write is one atomic `MutableStateFlow.update`. `observeMessages`'s `distinctUntilChanged` re-emits when the reading changes and stays quiet when a repeated reading returns the same list.

## Error handling

| Case | Result |
|---|---|
| Unknown `tool_use_id` in a known conversation | no-op, no row added |
| Conversation mobile holds no rows for | map returned unchanged, no empty slice minted |
| Row already `Done` / `Failed` / `Denied` | no-op |
| Missing field, wrong type, `elapsed_seconds` outside `Int` range | decode fails → that envelope / entry dropped silently, collector and page survive |
| `interactive` not negotiated | never decoded (both lanes) |

Nothing logs on any branch; the frame carries a conversation id and an untrusted join handle, and this seam's standing posture is silent drops.

## Testing strategy

Unit tests only; no UI changes, no operator-visible flow yet (display is #658), so no rung-3 scenario fires here.

`HistoryPageReducerTest` (replay lane + the pure fold):

- use, then progress 30 and 60 → one `Running` row holding 60.
- the pure fold keeps 30, 0, -65, 12 verbatim in sequence; a repeated reading returns the identical list.
- use, progress, result → `Done` with output and `elapsedSeconds == null`; a later progress leaves it unchanged.
- use, progress, denial → `Denied`, `null`; a later progress leaves it unchanged.
- progress naming no row, or before its `tool_use` → no row added; the later use opens with `null`.
- a malformed progress entry (field missing; `elapsed_seconds` a non-numeric string) drops only that entry.
- `interactive = false` → ignored.

New `RemoteConversationRepositoryToolProgressTest` (live lane; own `FakeSessionPump`, the split-file pattern of `RemoteConversationRepositoryQuestionTest`, so this ticket does not edit the shared 9000-line test file):

- use + repeated progress frames → one row, latest value, `Running`.
- progress after `tool_result` → row stays `Done` with its output, no reading.
- progress for another conversation with the same `tool_use_id`, and progress naming an unknown id → nothing changes.
- malformed progress → dropped, a following `tool_result` still closes the row.
- capability gate closed → ignored.

## Open questions

- Should a `tool_progress` clear an active stall? Resolved: out of scope (see Context); unchanged.

## Documentation handoff

Pending for the documentation stage: fold `tool_progress` into `docs/knowledge/features/live-tool-call.md` (the model block, the state-machine table, the tolerance table, and § Security) and add `ToolCall.elapsedSeconds` to `docs/knowledge/features/data-model.md`.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings — one decode point, `ToolProgressPayloadDto`, reached from exactly two arms (`onInbound`'s `TYPE_TOOL_PROGRESS` and `withHistoryEntry`'s), both behind the `interactive` gate. The frame carries no text that reaches Compose; the only retained value is an `Int`. `tool_use_id` is used solely as an equality key against rows this client already holds, through `indexOfMessage`'s `Role.Tool` namespacing, so a forged id can neither reach a message or assistant row nor create a row. The worst a hostile or buggy daemon achieves is a wrong number on a row it already opened in the same conversation — the protocol's own stated ceiling ("can at worst mislabel a visible counter").
- [Trust boundaries] No findings — the value is never treated as timing evidence, authorization, or routing input: no subtraction, no timer, no branch on it anywhere in the data layer. Negative and zero readings are stored verbatim, so no "validation" code exists to get wrong. `Int` bounds the decode; an out-of-range number fails the strict decode and drops the frame.
- [Tokens / crypto] No findings — no secrets, keys or randomness touched; the frame arrives inside the existing Noise session.
- [File / storage] No findings — in-memory only; `CachedToolCall` deliberately does not persist the reading.
- [Android surface] No findings — no intents, providers, WebViews or notifications.
- [Network & I/O] No findings — frame size is bounded by the existing envelope cap; a heartbeat flood is bounded by the same authenticated-daemon threat model as `message` and `tool_result`. It cannot grow state: a frame for an unknown row or an unseen conversation returns the store unchanged (the `applyToolDenied` guard), so it can never add a map entry or a row.
- [Errors & logs] No findings — decode failures are caught and discarded without logging, and neither fold nor arm logs anything, matching the seam's posture (a logged `conversation_id` is a cross-conversation correlation leak).
- [Concurrency] No findings — single atomic `update` on the existing collector; no new coroutine, scope or shared mutable state.
- [Threat model] OUT OF SCOPE — UI-side rendering of the reading (formatting, hiding on close, never starting an invented timer) belongs to #658.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-23

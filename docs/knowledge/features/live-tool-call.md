# Live tool-call — the correlated, status-carrying tool row in the thread

A single thread row that shows a tool the remote agent runs **as it starts** and updates **in place
when it finishes**. A `tool_use` event opens a `Running` `Role.Tool` [`Message`](data-model.md)
carrying the tool name + input; the matching `tool_result` (correlated by `toolUseId`) updates that
same row to `Done` (or `Failed`) and attaches the output. Landed in [#387](../codebase/387.md) (split
from #368, the data slice; blocked by [#385](../codebase/385.md)). The **visible** status affordance —
the running/done/failed leading slot on the row — shipped in [#388](../codebase/388.md), which consumes
this (see [ToolCallRow § Status affordance](tool-call-row.md#status-affordance-388)).

This is the **data layer only**: correlate the event pair into one evolving `ToolCall` and interleave
it into the thread stream. It renders nothing; output-encoding the untrusted strings is the
[#388](../codebase/388.md) renderer's job (it renders them through inert Compose `Text`/`CodeBlock`).

## The model — a status on the existing `ToolCall`

```kotlin
enum class ToolCallStatus { Running, Done, Failed }

data class ToolCall(
    val toolName: String,
    val input: String,
    val output: String,
    val status: ToolCallStatus = ToolCallStatus.Done,   // #387
)
```

The static `ToolCall` payload (`toolName`/`input`/`output`) already existed from #191 and already
rendered (a `Role.Tool` `Message` → [`ToolCallRow`](tool-call-row.md)). #387 adds **only** the
correlation + status: a trailing **defaulted** field. `Done` is the deliberate default — every prior
`ToolCall(...)` construction (the fake's seed, the previews) represents a *finished* call with output,
so `Done` keeps them compiling **and** semantically correct with **no fixture cascade** (and
`ToolCall(a,b,c) == ToolCall(a,b,c, status=Done)` equalities stay green). `ToolCallStatus` is the only
new exported type in the slice. See [Data model](data-model.md).

## The state machine

| Event | Fold | Effect |
|---|---|---|
| **`tool_use`** (start) | `applyToolUse` | append a `Running` `Role.Tool` row, `id = toolUseId`, `toolCall = ToolCall(name, inputSummary, output="", Running)` — **if absent** |
| **`tool_result`** (`isError == false`) | `applyToolResult` | update the matching row in place: `output = resultSummary`, `status = Done` |
| **`tool_result`** (`isError == true`) | `applyToolResult` | update the matching row in place: `output = resultSummary`, `status = Failed` |

`failed ⟺ ToolResult.isError == true`; `done` otherwise. Correlation is by **`toolUseId`, not by
position** — and the match is namespaced `id == toolUseId && role == Role.Tool` so a server-supplied
`toolUseId` can never collide with a real `message_id` and clobber a message. The `toolUseId` is the
row's `Message.id`.

## Tolerating a misbehaving stream (AC #3)

The correlation does **not** assume well-formed pairing. Every malformed case is absorbed without a
crash, a duplicate row, or an orphan:

| Case | Behaviour |
|---|---|
| `tool_result` with no matching `tool_use` | `applyToolResult` finds no row → **no-op** (no orphan half-row) |
| Result **before** use (out-of-order) | same as above — the early result is **dropped**; the later `tool_use` opens a fresh `Running` row (left permanently `Running`, see Limitations) |
| Duplicate `tool_use` (same id) | existing row left **untouched** — no second row, and a finished row is **not** reset to `Running` |
| Duplicate `tool_result` (same id) | in-place update **re-applied** (idempotent / last-write-wins, one row) |
| Malformed `tool_use`/`tool_result` payload | dropped at the existing `decodeLiveSessionEvent` `catch` (a missing/wrong-typed field fails the strict decode) → the fold never runs, the single inbound collector survives |

## Chronological interleave (AC #4) — why it's free

Tool rows **are** thread rows, so they fold into the **same** `messagesByConversation` the live
`message` arm writes. [`observeMessages`](remote-conversation-repository.md#observemessagesconversationid--the-live-thread-read-313)'s
projection maps that list to `ThreadItem.MessageItem`s **in arrival order, with no re-sort** — so a
tool row interleaves chronologically with messages by **arrival position**, with **no merge, no second
flow, no timestamp sort**. A ViewModel-side merge could not satisfy AC #4 because ordering is owned by
the repository surface; this is the structural reason the correlation lives in the repository, not a
composable or ViewModel. Because `ThreadItem.MessageItem` is a `data class`, the in-place
`Running → Done/Failed` flip makes the projected list structurally unequal → `distinctUntilChanged`
re-emits and the status transition propagates.

## How it surfaces in the repository

All of the behaviour lives in [`RemoteConversationRepository`](remote-conversation-repository.md#live-tool-call-rows--applytooluse--applytoolresult-387)
on the **single existing** inbound collector — see that doc for the dispatch and the two folds. In
short: the `tool_use`/`tool_result` dispatch is folded into the **existing** #385 live-session demux
arm (alongside the unchanged #395 stall-clear and the #385 `tryEmit` — **no second subscription**), and
each fold is one atomic `messagesByConversation.update {}`. The `Clock.System.now()` timestamp is the
established locally-assembled-row clock (`sendMessage`); the `tool_result` update preserves the
original `tool_use` timestamp. Connection-scoped, in-memory: a fresh repo per connection (#351) starts
empty, so live tool rows are re-derived from the live stream on reconnect — transient "right now"
state, not durable.

## Capability gate (fail-closed)

The dispatch sits inside the **existing** `CAPABILITY_INTERACTIVE in negotiatedCapabilities()` gate —
the same gate [#385](../codebase/385.md) / [#395](../codebase/395.md) use. The server already fans tool
events out **only** to phones that advertised `interactive` (#401), but the mobile gate is **defence in
depth**: a non-interactive phone that receives spurious tool events from a buggy/hostile daemon never
folds a tool row.

## Edge cases & limitations

- **Out-of-order result leaves the row `Running` forever.** A `tool_result` before its `tool_use` is
  dropped (no row to update yet), and the later `tool_use` cannot know a result already came and went.
  This is the conservative AC-#3 contract; a well-ordered stream never hits it. A pending-results map
  (keyed by `toolUseId`, drained on the matching `tool_use`) would recover it — flagged to the #388
  architect only if the UX needs it, not pre-built.
- **`turnId` is carried but unused for correlation** — `toolUseId` is globally unique per the wire SSOT.
  If the daemon ever reuses a `toolUseId` across turns, add `turnId` to the match key (not observed).
- **`content` for a tool row is the tool name** — a non-empty fallback the UI ignores (it renders
  `toolCall`). Tool rows are **not** folded into `lastMessages`, so a tool invocation never becomes a
  conversation-list preview.
- **Not durable.** Lost on connection drop / process death; re-derived from the live stream on reconnect.

## Security

`security-sensitive`; architect self-review **PASS**, code review **PASS** (zero findings). This slice
adds **no new parse point** — it consumes the already-typed [`LiveSessionEvent`](live-session-events.md)
decoded by #385 and copies `name`/`inputSummary`/`resultSummary` **verbatim** into
`toolCall.toolName`/`input`/`output` with **no trim, parse, or sanitize**. Output-encoding — treating
those server-authored strings as inert, non-active content at render — was the **#388** UI consumer's
job, named here as the hand-off (exactly as #385/#395 named it); [#388](../codebase/388.md) landed it by
rendering the strings through inert Compose `Text`/`CodeBlock` (never parsed or treated as markup), so it
stayed render-only and **not** `security-sensitive`. The folds and every drop/no-op branch
**log nothing** (the strings can carry sensitive session content — commands, paths, command output);
grep-confirmed zero `Log`/`println`/`print` touch tool-derived data. In-memory only, fail-closed gate,
behind the authenticated Noise channel; the unbounded-growth posture (a daemon flooding distinct
fabricated `toolUseId`s) is identical to the existing `message_id` path under the same
authenticated-paired-daemon threat model. UI-surface threats (screenshot/overlay/accessibility leakage
of rendered tool input/output) belong to the [#388](../codebase/388.md) renderer.

## Related

- [#387 implementation notes](../codebase/387.md) — files, line refs, design choices, verification.
- [Data model](data-model.md) — `ToolCall` + the new `ToolCallStatus` enum.
- [Remote conversation repository](remote-conversation-repository.md) — hosts the `applyToolUse` /
  `applyToolResult` folds and the dispatch inside the live-session arm.
- [Live-session events](live-session-events.md) ([#385](../codebase/385.md)) — the decoded
  `ToolUse`/`ToolResult` events this consumes; the gate + single-collector substrate this reuses.
- [Stall state](stall-state.md) ([#395](../codebase/395.md)) — the sibling that mirrors this posture
  (per-conversation state from the one inbound collector), with the opposite surfacing decision (a
  separate `observeStall` flow vs folding a row into the thread stream).
- [ToolCallRow](tool-call-row.md) ([#131](../codebase/131.md)/[#191](../codebase/191.md)) — the leaf
  renderer that reads `toolCall`; [#388](../codebase/388.md) extends it to render `status`.
- Consumer (was blockedBy this, now **shipped**): [#388](../codebase/388.md) — the tool-row status
  affordance (running spinner / done icon / failed glyph; Figma 16-28 design-owed for running/failed).
- Server SSOT: pyrycode#607 (wire types + capabilities), #616 (capability-gated fan-out), ADR 025
  § Phase 2 structured streaming, EPIC pyrycode#596.

# Live tool-call — the correlated, status-carrying tool row in the thread

A single thread row that shows a tool the remote agent runs **as it starts** and updates **in place
when it finishes**. A `tool_use` event opens a `Running` `Role.Tool` [`Message`](data-model.md)
carrying the tool name + input; the matching `tool_result` (correlated by `toolUseId`) updates that
same row to `Done` (or `Failed`) and attaches the output. Landed in [#387](../codebase/387.md) (split
from #368, the data slice; blocked by [#385](../codebase/385.md)). The **visible** status affordance —
the running/done/failed/denied trailing slot on the row — shipped in [#388](../codebase/388.md) and was
restyled with its own per-state glyph for `Denied` and an elapsed-time reading in #895, which consume
this (see [ToolCallRow § Trailing status](tool-call-row.md#trailing-status)).

This is the **data layer only**: correlate the event pair into one evolving `ToolCall` and interleave
it into the thread stream. It renders nothing; output-encoding the untrusted strings is the
[#388](../codebase/388.md) renderer's job (it renders them through inert Compose `Text`/`CodeBlock`).

## The model — a status on the existing `ToolCall`

```kotlin
enum class ToolCallStatus { Running, Done, Failed, Denied }         // Denied: #811

data class ToolCall(
    val toolName: String,
    val input: String,
    val output: String,
    val status: ToolCallStatus = ToolCallStatus.Done,   // #387
    val inputFields: Map<String, String> = emptyMap(),  // #810
    val parentToolUseId: String = "",                   // #810
    val denial: ToolDenial? = null,                     // #811
    val elapsedSeconds: Int? = null,                    // #812
    val resultDetail: String? = null,                   // #1316
)

data class ToolDenial(                                  // #811
    val toolName: String,
    val decisionReasonType: String,
    val decisionReason: String,
    val message: String,
    val truncatedFields: List<String>?,
    val droppedFields: List<String>?,
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
| **`tool_use`** (start) | `applyToolUse` | append a `Running` `Role.Tool` row, `id = toolUseId`, `toolCall = ToolCall(name, inputSummary, output="", Running, inputFields, parentToolUseId)` — **if absent** |
| **`tool_result`** (`isError == false`) | `applyToolResult` | update the matching row in place, **first result only** ([#1316](#first-result-wins-and-first-denial-wins-1316)): `output = resultSummary`, `resultDetail = resultDetail`, `status = Done` unless already `Denied`, `parentToolUseId` per the [#810 precedence rule](#tool_use-input-fields-and-parent_tool_use_id-810) |
| **`tool_result`** (`isError == true`) | `applyToolResult` | update the matching row in place, **first result only** ([#1316](#first-result-wins-and-first-denial-wins-1316)): `output = resultSummary`, `resultDetail = resultDetail`, `status = Failed` unless already `Denied`, `parentToolUseId` per the [#810 precedence rule](#tool_use-input-fields-and-parent_tool_use_id-810) |
| **`tool_denied`** ([#811](https://github.com/pyrycode/pyrycode-mobile/issues/811)) | `withToolDenied` | update the matching row in place, **first denial only** ([#1316](#first-result-wins-and-first-denial-wins-1316)): `status = Denied`, `denial = ToolDenial(...)` verbatim — wins over whatever status the row held, output/input/parent/position untouched; **no matching row → no-op**, a denial never adds a row |
| **`tool_progress`** ([#812](https://github.com/pyrycode/pyrycode-mobile/issues/812)) | `withToolProgress` | update the matching **`Running`** row in place: `elapsedSeconds = elapsedSeconds` verbatim (zero/negative/backwards kept as sent); **no matching row, or the row is no longer `Running` → no-op** — a heartbeat never adds a row, reopens a closed call, or overwrites its outcome |

`failed ⟺ ToolResult.isError == true`; `done` otherwise; a row already `Denied` stays `Denied`
regardless of which `tool_result` arrives afterwards. Correlation for `tool_result` / `tool_denied` /
`tool_progress` is by **`toolUseId`, not by position** — and the match is namespaced
`id == toolUseId && role == Role.Tool` so a server-supplied `toolUseId` can never collide with a real
`message_id` and clobber a message. The `toolUseId` is the row's `Message.id`.

**`tool_use`'s own repeat check is id-only, not role-namespaced
([#1350](https://github.com/pyrycode/pyrycode-mobile/issues/1350)).** `withToolUse`'s "if absent" guard
checks whether *any* `Message` — tool, user or assistant — already carries this `toolUseId`, not only an
existing `Role.Tool` row. This is the mirror image of the segment-key guard
[Remote conversation repository § Assistant reply segments](remote-conversation-repository-assistant-reply-segments.md#assistant-reply-segments-the-key-the-seam-join-and-the-turn-seq-dedupe-1350)
needed once a turn's text can be keyed `"<turnId>#<seq>"`: a hostile or buggy daemon could otherwise send a
`tool_use` whose id equals an assistant segment's key (or another message's id), and the thread's `"msg:<id>"`
`LazyColumn` key would collide. Widening the check costs nothing on the honest path — an id a `tool_use`
would otherwise legitimately reuse never also names a message or segment — and only ever suppresses a row,
never completes or reopens one.

### First result wins and first denial wins (#1316)

Before #1316, both `withToolResult` and `withToolDenied` were last-write-wins: any later frame for
the same `toolUseId` re-applied its own fold. Desktop (`fillResult` / the `toolDenied` arm in
`threadTimeline.ts`) is first-write-wins instead, so #1316 brought mobile's reducer in line:

- **`withToolResult`** now folds a `tool_result` only when the row is still `Running`, or is
  `Denied` with no result yet (`ToolCall.resultDetail == null`). Any other case — a second result on
  an already-`Done`/`Failed` row, or a result on a `Denied` row that already attached one — returns
  the list **unchanged**: output, `resultDetail`, status and `parentToolUseId` all stay at their
  first-result values. A row restored from the disk cache has `resultDetail == null` regardless of
  its status, but its status is never `Running`/`Denied`-with-no-result unless genuinely reopened by
  a later `tool_use`, so a duplicate result against a cache-restored `Done`/`Failed` row is still
  rejected by the status gate, not the `resultDetail` check.
- **`withToolDenied`** now folds a `tool_denied` only when the row holds no denial yet
  (`ToolCall.denial == null`). A row already `Denied` returns the list unchanged on a second denial.

The two folds still compose the same way they did before #1316: a denial **after** a result still
marks the row `Denied` and keeps the result's output and `resultDetail` (the `Denied` status check
inside `withToolResult`'s `status` branch, unchanged); a result **after** a denial still fills output
and `resultDetail` on that `Denied` row, because the row's `resultDetail` is still `null` at that
point — a refused call is never turned back into a merely failed one by the result claude writes
after refusing, and the result it does carry is still shown. A result, then a second result, then a
denial still lands the denial (first-result-wins governs the two results; the denial's own
first-denial check only looks at `denial`, so it still applies on top). See [ToolCallRow § Result
count](tool-call-row.md#trailing-status) for how `resultDetail` renders, and
`HistoryPageReducerTest`'s result→result / denial→denial / result→denial / denial→result /
denial→result→result cases for the fold's full order matrix.

### Denied ([#811](https://github.com/pyrycode/pyrycode-mobile/issues/811))

claude's refusal reaches mobile as a separate `tool_denied` frame — sent on a line *before* the
`tool_result`, and also emitted by the daemon's result-line recovery for a call whose `tool_result`
already shipped — rather than as fields on `tool_result`, because either arrival order must produce
the same retained state. Before #1316, `withToolDenied` always won regardless of repeat frames;
since #1316 it is **first-denial-wins** instead (see above) — a row already `Denied` ignores a
second `tool_denied`. `withToolResult`'s denial-interaction rule is unchanged: when a row is already
`Denied`, a `tool_result` for the same `toolUseId` still attaches `output` (and, since #1316,
`resultDetail`) when none has arrived yet, but leaves `status` and `denial` alone — a refused call is
never turned back into a merely failed one by the result claude writes after refusing.

`ToolDenial` carries `toolName`, `decisionReasonType`, `decisionReason`, `message`, and the two report
arrays `truncatedFields` / `droppedFields`, all copied **verbatim** from the wire frame — no trim,
parse, sanitize or token interpretation. The two arrays are what keep three readings of a field
decidable: named in neither array, claude sent it as-is (every captured denial has both reason fields
empty, so this is the ordinary case); empty and named in `droppedFields`, the daemon emptied an
over-cap value; present and named in `truncatedFields`, the daemon cut claude's text. The DTO shape
(`ToolDeniedPayloadDto`) follows `RateLimitedPayloadDto.truncatedFields` — `List<String>? = null` —
so a wire `null` and a wire `[]` decode to distinct values; `QueueStatePayloadDto.toQueue`'s
coalescing mapper is the pattern **not** followed here. `message` and `decisionReason` are
claude-authored prose that may quote a refused command line and name absolute host paths; `toolName`
and `decisionReasonType` are open-set tokens. Rendering them — inert attributed text, control/escape
stripping, switching the tokens against known values with an unknown fallback — is out of scope here
and belongs to [#658](https://github.com/pyrycode/pyrycode-mobile/issues/658), which owns the denied
row's visual design; that obligation travels with the type in `ToolDenial`'s KDoc.

A denial is **not** a `LiveSessionEvent`: nothing on the live stream consumes it, so — like
`unrecognized_message` — it folds into the thread store only and is never emitted on
`liveSessionEvents`. That keeps it out of `ThreadViewModel`'s four exhaustive `when`s over
`LiveSessionEvent`, which a new subtype would otherwise have forced to grow an arm for an event they
don't need. The row's trailing-status `when` over `ToolCallStatus` in `ToolCallRow.kt` reused the
`Failed` presentation for `Denied` as a stop-gap until #895 (split from #658) gave `Denied` its own
glyph and content description — see [ToolCallRow § Trailing status](tool-call-row.md#trailing-status).

The disk cache (`FileConversationCache.CachedToolCall`) persists `status` by enum name, so a `Denied`
row survives a cache round-trip; `denial` itself is not persisted (out of scope, the #810
`inputFields` precedent), so a row restored from cache is `Denied` with `denial = null`. `resultDetail`
(#1316) is likewise not persisted, so a cache-restored row's `resultDetail` is always `null`
regardless of status — see [§ First result wins and first denial wins](#first-result-wins-and-first-denial-wins-1316).

### Progress ([#812](https://github.com/pyrycode/pyrycode-mobile/issues/812))

claude reports its own elapsed-seconds reading for an open call as a `tool_progress` frame
(pyrycode#2324), carrying only `conversationId`/`turnId`/`toolUseId`/`elapsedSeconds` — no session id,
tool name or sequence number, because the row it updates already supplies the rest. `withToolProgress`
retains the reading **verbatim** on the matching `Running` row: zero, negative and backwards values are
upstream readings the daemon forwards without computing, rate-limiting, deduplicating or clamping, not
a validation result, so none of that happens on this client either. `turnId` is not part of the match,
the same as every other tool fold (`toolUseId` is the wire's own globally-unique join key).

**Absence proves nothing.** A call that finishes before claude's first heartbeat emits no frame at
all, and a later frame can be lost independently of the never-droppable lifecycle frames — so a gap
between readings, or no reading ever arriving, never means the call stalled, restarted or failed. A
`tool_progress` naming an unknown row, or a row that is `Done`/`Failed`/`Denied`, is a **no-op**: it
never adds a row, never reopens a closed one, and never touches `output`/`status`/`denial`. Closing a
row — `withToolResult` or `withToolDenied`, whichever fires — also clears `elapsedSeconds` back to
`null`, so a finished row never carries a stale "still running" number forward; a `tool_progress` that
arrives after closing is simply ignored, it does not need to un-set anything itself. `elapsedSeconds`
is never persisted to the disk cache, the same posture as `denial`.

Like `tool_denied`, this is **not** a `LiveSessionEvent` — nothing on the live stream consumes it, so
it folds into the thread store only and stays out of `ThreadViewModel`'s exhaustive `when`s. It also
does not clear the stall projection; whether a heartbeat should count as forward progress for the
stall banner is a product question outside this ticket, left for a future ticket to decide. **The
daemon replays `tool_progress` in conversation history** the same way it replays `tool_denied` — every
mapped turn event is appended to durable history before the wire fan-out — so
`HistoryPageReducer.withHistoryEntry`'s `TYPE_TOOL_PROGRESS` arm decodes and folds the identical frame
through the identical `ToolProgressPayloadDto` + `withToolProgress` on the replay lane.

Rendering the reading — formatting, showing it only while running, never inventing a timer when no
reading has arrived — is [#658](https://github.com/pyrycode/pyrycode-mobile/issues/658)'s; this ticket
ends at the retained model.

## `tool_use.input` fields and `parent_tool_use_id` (#810)

[#810](https://github.com/pyrycode/pyrycode-mobile/issues/810) carries two more wire fields into the
retained row, through the same DTO/mapper/fold seam described above — no new decode point and no
change to the correlation or tolerance rules above:

- **`inputFields`** — `tool_use.input`'s own top-level fields, so a future renderer can show what a
  call acts on (e.g. an `Edit`'s `file_path`) instead of hunting for it inside `input`, the one-line
  précis. Decoded from a raw `JsonElement` rather than a typed map: an absent, `null`, empty or
  non-object `input` all yield `emptyMap()` rather than a decode failure, and within an object only
  JSON-**string** values are kept (a non-string value is off-contract — the daemon stringifies every
  field — and is skipped, not rewritten). `tool_result` carries no input, so `withToolResult` never
  touches `inputFields`.
- **`parentToolUseId`** — the `Agent`/`Task` call that spawned the subagent making this call; `""`
  means the main thread. Present on both `tool_use` and `tool_result`. **Precedence when the two
  frames disagree:** `withToolResult` replaces the row's `parentToolUseId` when the result's is
  non-empty, and keeps the use's when the result's is empty — it never un-nests a row because the key
  was merely absent. A conforming daemon sends the same value on both frames, so this only matters
  across a mid-stream daemon upgrade/downgrade where one frame lacks the key.

Both fields are **lenient-defaulted** at the DTO (`= ""` / `= null`), a deliberate departure from the
strict-required posture the sibling DTO fields use (see [Live-session events](live-session-events.md)).
The daemon always emits both keys, so an absent one means an older binary — which meant "main thread"
and "no fields" — so decoding to `""` / `{}` reproduces what that binary said, instead of dropping the
whole `tool_use`/`tool_result` frame. A wire `null` for `parent_tool_use_id` itself still fails the
strict decode; the contract never sends one.

The parent **join** — matching `parentToolUseId` against another row's `toolUseId` within the same
conversation's thread, and falling back to a top-level row when nothing matches — is out of scope here
and belongs to the [#658](https://github.com/pyrycode/pyrycode-mobile/issues/658) renderer, alongside
the rest of rendering (see the file header). Both values are inert display/grouping data the daemon
neither resolved nor validated: an input value may be a literal shell command line or a relative,
traversing path, and the parent id may name a call this client never saw.

## Tolerating a misbehaving stream (AC #3)

The correlation does **not** assume well-formed pairing. Every malformed case is absorbed without a
crash, a duplicate row, or an orphan:

| Case | Behaviour |
|---|---|
| `tool_result` with no matching `tool_use` | `applyToolResult` finds no row → **no-op** (no orphan half-row) |
| Result **before** use (out-of-order) | same as above — the early result is **dropped**; the later `tool_use` opens a fresh `Running` row (left permanently `Running`, see Limitations) |
| Duplicate `tool_use` (same id) | existing row left **untouched** — no second row, and a finished row is **not** reset to `Running` |
| Duplicate `tool_result` (same id) | **no-op since #1316** (first-result-wins, one row) — the row's output/`resultDetail`/status/`parentToolUseId` stay at their first-result values |
| Duplicate `tool_denied` (same id) | **no-op since #1316** (first-denial-wins) — the row stays `Denied` with its first denial's fields |
| Malformed `tool_use`/`tool_result` payload | dropped at the existing `decodeLiveSessionEvent` `catch` (a missing/wrong-typed field fails the strict decode) → the fold never runs, the single inbound collector survives |
| `tool_denied` naming no known row ([#811](https://github.com/pyrycode/pyrycode-mobile/issues/811)) | `withToolDenied` finds no row → **no-op**, no row is added |
| Malformed `tool_denied` payload ([#811](https://github.com/pyrycode/pyrycode-mobile/issues/811)) | dropped at `applyToolDenied`'s own `catch (IllegalArgumentException)` (live lane) or the replay lane's existing `try` — that one envelope/entry is dropped, the collector survives |
| `tool_denied` for a `conversation_id` mobile holds no rows for ([#811](https://github.com/pyrycode/pyrycode-mobile/issues/811)) | `applyToolDenied` returns the map **unchanged** rather than minting an empty slice — a denial can never add a row, so it must not grow the map either (a hostile daemon sending random conversation ids would otherwise cause unbounded growth) |
| `tool_progress` naming no known row, or a row that is `Done`/`Failed`/`Denied` ([#812](https://github.com/pyrycode/pyrycode-mobile/issues/812)) | `withToolProgress` finds no matching `Running` row → **no-op**, no row added, no outcome touched |
| Malformed `tool_progress` payload ([#812](https://github.com/pyrycode/pyrycode-mobile/issues/812)) | dropped at `applyToolProgress`'s own `catch (IllegalArgumentException)` (live lane) or the replay lane's existing `try` — that one envelope/entry is dropped, the collector survives |
| `tool_progress` for a `conversation_id` mobile holds no rows for ([#812](https://github.com/pyrycode/pyrycode-mobile/issues/812)) | `applyToolProgress` returns the map **unchanged**, the same no-empty-slice guard as `tool_denied` |

**Text after a tool row opens a new reply bubble below it
([#1350](https://github.com/pyrycode/pyrycode-mobile/issues/1350)).** Before #1350, an `assistant_delta`
always extended the one assistant row of its turn wherever that row sat, so a turn that wrote text, ran a
tool, then wrote more text drew one bubble above the tool row. `HistoryPageReducer.withAssistantDelta` now
extends the thread's **last** row only when that row is already an assistant segment of the same turn — a
tool row (or any other row) in between makes the next delta open a new segment at the end, so the second
piece of text draws **below** the tool row this call opened. See
[Streaming assistant turns § Finished rows are now per-segment](streaming-assistant-turns.md#finished-rows-are-now-per-segment-not-one-bubble-per-turn-1350)
and [Remote conversation repository § Assistant reply segments](remote-conversation-repository-assistant-reply-segments.md#assistant-reply-segments-the-key-the-seam-join-and-the-turn-seq-dedupe-1350).

## Chronological interleave (AC #4) — why it's free

Tool rows **are** thread rows, so they fold into the **same** `threadByConversation` the live
`message` arm writes (a tool row is a `ThreadItem.MessageItem` wrapping a `Role.Tool` `Message`).
[`observeMessages`](remote-conversation-repository-reads-and-thread-store.md#observemessagesconversationid--the-live-thread-read-313)'s
projection exposes that `List<ThreadItem>` **in arrival order, with no re-sort** (since #336 the store
already holds `ThreadItem`s — no per-row wrap) — so a tool row interleaves chronologically with
messages by **arrival position**, with **no merge, no second flow, no timestamp sort**. A ViewModel-side merge could not satisfy AC #4 because ordering is owned by
the repository surface; this is the structural reason the correlation lives in the repository, not a
composable or ViewModel. Because `ThreadItem.MessageItem` is a `data class`, the in-place
`Running → Done/Failed` flip makes the projected list structurally unequal → `distinctUntilChanged`
re-emits and the status transition propagates.

## How it surfaces in the repository

All of the behaviour lives in [`RemoteConversationRepository`](remote-conversation-repository-thread-observables.md#live-tool-call-rows--applytooluse--applytoolresult--applytooldenied-387-811)
on the **single existing** inbound collector — see that doc for the dispatch and the two folds. In
short: the `tool_use`/`tool_result` dispatch is folded into the **existing** #385 live-session demux
arm (alongside the unchanged #395 stall-clear and the #385 `tryEmit` — **no second subscription**), and
each fold is one atomic `threadByConversation.update {}`. The `Clock.System.now()` timestamp is the
established locally-assembled-row clock (`sendMessage`); the `tool_result` update preserves the
original `tool_use` timestamp. Connection-scoped, in-memory: a fresh repo per connection (#351) starts
empty, so live tool rows are re-derived from the live stream on reconnect — transient "right now"
state, not durable.

`tool_denied` ([#811](https://github.com/pyrycode/pyrycode-mobile/issues/811)) is a **separate** verb
(`TYPE_TOOL_DENIED`, beside `TYPE_TOOL_RESULT`), its own arm inside the same gated dispatch, and its
own private fold (`applyToolDenied`), because it is not a `LiveSessionEvent` — the DTO is decoded and
mapped to `ToolDenial` directly inside the fold, not through `decodeLiveSessionEvent`. It does not
clear the stall projection and is not emitted on `liveSessionEvents`; it is a report, not forward
progress the rest of the thread store needs to know about. **The daemon also replays `tool_denied`
in conversation history** (it goes out through the same emit path as `tool_result`, appended to
history beside the ring), so `HistoryPageReducer.withHistoryEntry` decodes and folds the identical
frame through the identical `toDenial()` + `withToolDenied` on the replay lane — a history page
carrying `tool_use`, `tool_denied` and `tool_result` for one call replays to a `Denied` row exactly
like the live sequence does.

`tool_progress` ([#812](https://github.com/pyrycode/pyrycode-mobile/issues/812)) is the same shape of
addition as `tool_denied`: its own verb (`TYPE_TOOL_PROGRESS`), its own arm inside the same gated
dispatch, its own private fold (`applyToolProgress`) that decodes `ToolProgressPayloadDto` directly
rather than through `decodeLiveSessionEvent`, and it is replayed in history the same way — see
[§ Progress](#progress-812) above for the retained-value rules.

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
`toolCall.toolName`/`input`/`output` with **no trim, parse, or sanitize**. [#810](https://github.com/pyrycode/pyrycode-mobile/issues/810)
adds `inputFields`/`parentToolUseId` on the same posture — copied verbatim, never trimmed, parsed,
re-ordered or treated as a path/command/URL to act on (self-reviewed **PASS**; see that ticket's plan
for the full boundary analysis). Output-encoding — treating
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

[#811](https://github.com/pyrycode/pyrycode-mobile/issues/811) (`tool_denied`, self-reviewed **PASS**,
verifier **PASS**) copies `toolName`/`decisionReasonType`/`decisionReason`/`message` and the two report
arrays into `ToolDenial` on the same verbatim, no-trim/parse/sanitize posture — `message` and
`decisionReason` are claude prose that may quote a refused command line and name absolute host paths;
render obligations belong to [#658](https://github.com/pyrycode/pyrycode-mobile/issues/658), named in
`ToolDenial`'s own KDoc rather than only here. The frame can at worst **relabel a row it already
opened** in the same conversation as `Denied` (its `Role.Tool`-namespaced match can't reach an
assistant/message row, and a miss adds no row) — a hostile or buggy daemon gains no new capability
beyond what a `tool_result` it fabricates already has. `applyToolDenied` never mints an empty
conversation slice for an unseen `conversation_id` (the same unbounded-growth guard `applyToolResult`
already needed, but a denial can *only* ever relabel, never add, so the guard here returns the map
unchanged rather than an empty entry). `ToolDenial` is never persisted to the disk cache.

[#812](https://github.com/pyrycode/pyrycode-mobile/issues/812) (`tool_progress`, self-reviewed
**PASS**) retains one `Int` verbatim — no clamping, subtraction or use as timing evidence anywhere in
the data layer, so there is no "validation" logic to get wrong. `toolUseId` is used solely as an
equality key against rows this client already holds, through the same `Role.Tool`-namespaced
`indexOfMessage` every other tool fold uses, so a forged id can neither reach a message/assistant row
nor create one; the worst a hostile or buggy daemon achieves is a wrong number on a row it already
opened in the same conversation. `applyToolProgress` never mints an empty conversation slice for an
unseen `conversation_id`, the same guard `applyToolDenied` needed. Decode failures are caught and
discarded without logging, matching this seam's standing posture; `elapsedSeconds` is never persisted
to the disk cache.

[#1316](https://github.com/pyrycode/pyrycode-mobile/issues/1316) (`result_detail`, first-result/
first-denial-wins) adds one more verbatim-copied string (`resultDetail`) on the same no-trim/parse
posture as `output`/`input` — it is daemon-authored display text, never parsed into a number, logged
or treated as a link. The first-result/first-denial-wins change is a **pure fold-order** change: it
narrows which frames a fold accepts (an already-resolved row's later frames become no-ops instead of
re-applying) but adds no new parse point, no new persisted field, and no new unbounded-growth surface
— the existing `indexOfMessage` match and the existing per-conversation map guards are unchanged.
`resultDetail` is never persisted to the disk cache, the same posture as `denial` and `elapsedSeconds`.

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
- [#810](https://github.com/pyrycode/pyrycode-mobile/issues/810) — adds `ToolCall.inputFields` /
  `parentToolUseId` (see [§ `tool_use.input` fields and `parent_tool_use_id`](#tool_use-input-fields-and-parent_tool_use_id-810)
  above). Rendering and the parent join are deferred to
  [#658](https://github.com/pyrycode/pyrycode-mobile/issues/658).
- [#811](https://github.com/pyrycode/pyrycode-mobile/issues/811) — adds `ToolCallStatus.Denied` and
  `ToolCall.denial: ToolDenial?` from the `tool_denied` frame (see [§ Denied](#denied-811) above),
  folded on both the live lane (`applyToolDenied`) and the replay lane (`HistoryPageReducer`'s
  `TYPE_TOOL_DENIED` arm). Rendering the denied row is [#658](https://github.com/pyrycode/pyrycode-mobile/issues/658).
- [#812](https://github.com/pyrycode/pyrycode-mobile/issues/812) — adds `ToolCall.elapsedSeconds` from
  the `tool_progress` frame (see [§ Progress](#progress-812) above), folded on both the live lane
  (`applyToolProgress`) and the replay lane (`HistoryPageReducer`'s `TYPE_TOOL_PROGRESS` arm). Rendering
  the reading is [#658](https://github.com/pyrycode/pyrycode-mobile/issues/658).
- [#1316](https://github.com/pyrycode/pyrycode-mobile/issues/1316) — adds `ToolCall.resultDetail`
  from `tool_result`'s `result_detail` field, and makes `withToolResult`/`withToolDenied`
  first-result-wins / first-denial-wins instead of last-write-wins (see [§ First result wins and
  first denial wins](#first-result-wins-and-first-denial-wins-1316) above). Rendering the count is
  [ToolCallRow § Trailing status](tool-call-row.md#trailing-status).
- Server SSOT: pyrycode#607 (wire types + capabilities), #616 (capability-gated fan-out), ADR 025
  § Phase 2 structured streaming, EPIC pyrycode#596, pyrycode#2024 (`result_detail`).
